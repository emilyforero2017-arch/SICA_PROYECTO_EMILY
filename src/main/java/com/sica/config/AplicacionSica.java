package com.sica.config;

import com.sica.infrastructure.database.ConexionBaseDatos;
import com.sica.infrastructure.database.GestorTransaccionesJdbc;
import com.sica.infrastructure.security.ServicioContrasenasPbkdf2;
import com.sica.model.application.port.GestorTransacciones;
import com.sica.model.application.usecase.*;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

// Reúne las piezas reales de una instancia de SICA. No contiene reglas de visitas ni SQL.
public final class AplicacionSica implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AplicacionSica.class.getName());
    private final GestorTransacciones gestor;
    private final ContextoSesion contexto = new ContextoSesion();
    private final CountDownLatch finalizacion = new CountDownLatch(1);
    private final IniciarSesionCase inicioSesion;
    private final CerrarSesionCase cierreSesion;
    private final ProgramadorRevisionesAutomaticas revisiones;
    private final EmpresaUseCase empresas;
    private final UsuarioCase usuarios;
    private final RolPermisoCase rolesPermisos;
    private final PersonaCase personas;
    private final VisitaCase visitas;
    private final DestinoVisitaCase destinos;
    private final SolicitudIngresoCase solicitudes;
    private final ControlAccesoProgramadoCase accesoProgramado;
    private final ControlSolicitudCase accesoPorSolicitud;
    private final ControlAccesoTrabajadorCase accesoTrabajador;
    private final ControlSalidaCase salidas;
    private final RestriccionAccesoCase restricciones;
    private final IncidenteCase incidentes;
    private final SeguridadAccesosCase seguridad;
    private final AuditoriaCase auditoria;
    private final AvisosSolicitudCase avisos;
    private boolean iniciada;
    private boolean cerrada;

    public AplicacionSica(ConfiguracionBasedatos configuracion) {
        // El gestor proporcionará una fábrica JDBC y una conexión propia para cada operación.
        gestor = new GestorTransaccionesJdbc(new ConexionBaseDatos(configuracion));
        var contrasenas = new ServicioContrasenasPbkdf2();
        var ejecutor = new EjecutorOperaciones(gestor, contexto);
        inicioSesion = new IniciarSesionCase(gestor, contrasenas);
        cierreSesion = new CerrarSesionCase(contexto);
        empresas = new EmpresaUseCase(ejecutor);
        usuarios = new UsuarioCase(ejecutor, contrasenas);
        rolesPermisos = new RolPermisoCase(ejecutor);
        personas = new PersonaCase(ejecutor);
        visitas = new VisitaCase(ejecutor);
        destinos = new DestinoVisitaCase(ejecutor);
        solicitudes = new SolicitudIngresoCase(ejecutor);
        accesoProgramado = new ControlAccesoProgramadoCase(ejecutor);
        accesoPorSolicitud = new ControlSolicitudCase(ejecutor);
        accesoTrabajador = new ControlAccesoTrabajadorCase(ejecutor);
        salidas = new ControlSalidaCase(ejecutor);
        restricciones = new RestriccionAccesoCase(ejecutor);
        incidentes = new IncidenteCase(ejecutor);
        seguridad = new SeguridadAccesosCase(ejecutor);
        auditoria = new AuditoriaCase(ejecutor);
        avisos = new AvisosSolicitudCase(ejecutor);
        revisiones = new ProgramadorRevisionesAutomaticas(
                new VencimientoVisitasCase(gestor), new RegularizacionSalidasCase(gestor));
    }

    public synchronized void iniciar() {
        if (cerrada) throw new IllegalStateException("Esta aplicación ya está cerrada.");
        if (iniciada) return;
        // Comprobamos la conexión y los datos básicos antes de activar trabajos automáticos.
        gestor.ejecutar(repositorios -> {
            if (repositorios.crearRolRepository().listar().isEmpty()
                    || repositorios.crearPermisoRepository().listar().isEmpty()) {
                throw new IllegalStateException("Faltan los roles o permisos iniciales de SICA.");
            }
            Set<String> estados = repositorios.crearEstadoVisitaRepository().listar().stream()
                    .map(estado -> estado.getNombreEstado()).collect(Collectors.toSet());
            if (!estados.containsAll(Set.of("Aprobado", "Expirado", "Dentro", "Cerrada por Sistema - Salida no registrada"))) {
                throw new IllegalStateException("Faltan estados necesarios para las revisiones automáticas.");
            }
            return null;
        });
        revisiones.iniciar();
        iniciada = true;
    }

    // El futuro controlador de login llamará aquí; todos los casos de uso comparten este contexto.
    public synchronized SesionUsuario iniciarSesion(String correo, char[] contrasena) {
        if (!iniciada || cerrada) throw new IllegalStateException("SICA debe estar iniciada para abrir una sesión.");
        return inicioSesion.iniciar(contexto, correo, contrasena);
    }

    public void cerrarSesion() { cierreSesion.cerrar(); }
    public Optional<SesionUsuario> getSesionActual() { return contexto.consultarActual(); }

    // Los controladores recibirán los casos de uso de esta misma aplicación.
    public EmpresaUseCase getEmpresas() { return empresas; }
    public UsuarioCase getUsuarios() { return usuarios; }
    public RolPermisoCase getRolesPermisos() { return rolesPermisos; }
    public PersonaCase getPersonas() { return personas; }
    public VisitaCase getVisitas() { return visitas; }
    public DestinoVisitaCase getDestinos() { return destinos; }
    public SolicitudIngresoCase getSolicitudes() { return solicitudes; }
    public ControlAccesoProgramadoCase getAccesoProgramado() { return accesoProgramado; }
    public ControlSolicitudCase getAccesoPorSolicitud() { return accesoPorSolicitud; }
    public ControlAccesoTrabajadorCase getAccesoTrabajador() { return accesoTrabajador; }
    public ControlSalidaCase getSalidas() { return salidas; }
    public RestriccionAccesoCase getRestricciones() { return restricciones; }
    public IncidenteCase getIncidentes() { return incidentes; }
    public SeguridadAccesosCase getSeguridad() { return seguridad; }
    public AuditoriaCase getAuditoria() { return auditoria; }
    public AvisosSolicitudCase getAvisos() { return avisos; }

    public void esperarCierre() throws InterruptedException { finalizacion.await(); }

    @Override
    public void close() {
        synchronized (this) {
            if (cerrada) return;
            cerrada = true;
        }
        try {
            // Se intenta detener todo aunque falle el cierre de algún seguimiento de sesión.
            cerrarRecurso(cierreSesion::cerrar);
            cerrarRecurso(revisiones);
        } finally { finalizacion.countDown(); }
    }

    private void cerrarRecurso(AutoCloseable recurso) {
        try { recurso.close(); }
        catch (Exception error) {
            LOG.log(Level.WARNING, "No fue posible cerrar por completo un recurso de SICA.", error);
        }
    }
}
