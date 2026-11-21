package com.sica.integration;

import com.sica.infrastructure.database.GestorTransaccionesJdbc;
import com.sica.infrastructure.security.ServicioContrasenasPbkdf2;
import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.application.usecase.*;
import com.sica.model.domain.entity.*;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sica.pruebas.jdbc", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Recorridos: registro e historial de incidentes")
class IncidentesIT {

    private static final ZoneId ZONA = ZoneId.of("America/Bogota");
    private static final LocalDateTime HECHO = LocalDateTime.of(2026, 1, 12, 10, 0);
    private static final LocalDateTime REGISTRO = HECHO.plusDays(2);
    private final Clock reloj = Clock.fixed(REGISTRO.atZone(ZONA).toInstant(), ZONA);
    private final List<Aplicacion> aplicaciones = new ArrayList<>();
    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private Aplicacion supervisor;
    private Visita visita;
    private Set<Long> auditoriasIniciales;

    private final class Aplicacion implements AutoCloseable {
        final ContextoSesion contexto = new ContextoSesion();
        final EjecutorOperaciones ejecutor = new EjecutorOperaciones(gestor, contexto);
        final IncidenteCase incidentes = new IncidenteCase(ejecutor, reloj);
        final SesionUsuario sesion;

        Aplicacion(String correo) {
            char[] clave = "SicaDemo2026!".toCharArray();
            try {
                sesion = new IniciarSesionCase(gestor, new ServicioContrasenasPbkdf2()).iniciar(contexto, correo, clave);
            } finally { Arrays.fill(clave, '\0'); }
        }

        Integer id() { return sesion.getUsuarioId(); }
        @Override public void close() { new CerrarSesionCase(contexto).cerrar(); }
    }

    @BeforeEach
    void preparar() throws Exception {
        base = BaseDatosTemporal.crear();
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        supervisor = ingresar("supervisor@sica.local");
        visita = prepararVisita("ana@sol.sica.local", false);
        // Los datos de demostración ya incluyen un incidente: comparamos solo las acciones nuevas.
        auditoriasIniciales = gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(supervisor.id()))
                .stream().map(BitacoraAuditoria::getId).collect(Collectors.toSet());
    }

    @AfterEach
    void limpiar(TestInfo prueba) throws SQLException {
        try {
            for (Aplicacion app : aplicaciones) app.close();
        } finally {
            if (base != null) base.close();
            System.out.println("Recorrido revisado: " + prueba.getDisplayName());
        }
    }

    @Test
    @DisplayName("El supervisor registra un hecho de una visita terminada y conserva su fecha, autor y auditoría")
    void registraYConsultaUnHechoAnterior() {
        Incidente registrado = registrar(visita, HECHO, "Laura comunicó la pérdida de un objeto.");
        gestor.ejecutar(r -> {
            Incidente guardado = r.crearIncidenteRepository().buscarPorId(registrado.getId()).orElseThrow();
            assertEquals(visita.getId(), guardado.getVisitaId());
            assertEquals(supervisor.id(), guardado.getRegistradoPorId());
            assertEquals(HECHO, guardado.getFechaIncidente());
            assertEquals(registrado.getDescripcion(), guardado.getDescripcion());
            List<BitacoraAuditoria> auditorias = auditoriasDeIncidente(r, registrado.getId());
            assertEquals(1, auditorias.size());
            BitacoraAuditoria auditoria = auditorias.get(0);
            assertEquals("REGISTRO_INCIDENTE", auditoria.getAccionRealizada());
            assertEquals(supervisor.id(), auditoria.getUsuarioId());
            assertEquals(REGISTRO, auditoria.getFechaHora());
            assertTrue(auditoria.getDetalles().contains("Visita: " + visita.getId()));
            assertTrue(auditoria.getDetalles().contains(HECHO.toString()));
            comprobarAccesoSinCambios(r, visita, "Activo");
            return null;
        });
        List<DetalleIncidente> historial = supervisor.incidentes.consultarPorVisita(supervisor.id(), visita.getId());
        assertEquals(1, historial.size());
        DetalleIncidente detalle = historial.get(0);
        assertEquals(registrado.getId(), detalle.getIncidente().getId());
        assertEquals(visita.getId(), detalle.getVisita().getId());
        assertEquals(visita.getPersonaId(), detalle.getPersona().getId());
        assertEquals("Empresa Sol", detalle.getEmpresa());
        assertEquals(supervisor.sesion.getNombre(), detalle.getSupervisor());
        assertTrue(supervisor.incidentes.consultarHistorial(supervisor.id(), REGISTRO, REGISTRO.plusDays(1)).isEmpty());
        assertEquals(List.of(registrado.getId()), supervisor.incidentes.consultarHistorial(
                supervisor.id(), HECHO, HECHO.plusDays(1)).stream().map(d -> d.getIncidente().getId()).toList());
    }

    @Test
    @DisplayName("El historial de una visita no mezcla otras y ordena por fecha y luego por identificador")
    void ordenaYSeparaLosIncidentesDeCadaVisita() {
        Incidente anterior = registrar(visita, HECHO.minusHours(1), "Primer hecho.");
        Incidente mismoMomento = registrar(visita, HECHO, "Segundo hecho.");
        Incidente ultimo = registrar(visita, HECHO, "Tercer hecho, misma hora.");
        Visita otra = prepararVisita("ana@sol.sica.local", false);
        registrar(otra, HECHO.plusHours(1), "Corresponde a otra visita.");
        assertEquals(List.of(ultimo.getId(), mismoMomento.getId(), anterior.getId()),
                supervisor.incidentes.consultarPorVisita(supervisor.id(), visita.getId())
                        .stream().map(d -> d.getIncidente().getId()).toList());
        assertEquals(1, supervisor.incidentes.consultarPorVisita(supervisor.id(), otra.getId()).size());
    }

    @Test
    @DisplayName("El período incluye su inicio y excluye su final usando la fecha del hecho")
    void respetaLosLimitesDelHistorial() {
        registrar(visita, HECHO.minusSeconds(1), "Antes del período.");
        Incidente inicio = registrar(visita, HECHO, "Inicio del período.");
        Incidente siguiente = registrar(visita, HECHO.plusSeconds(1), "Dentro del período.");
        registrar(visita, HECHO.plusHours(1), "Final excluido.");
        assertEquals(List.of(siguiente.getId(), inicio.getId()), supervisor.incidentes.consultarHistorial(
                supervisor.id(), HECHO, HECHO.plusHours(1)).stream().map(d -> d.getIncidente().getId()).toList());
        assertEquals(List.of(siguiente.getId()), supervisor.incidentes.consultarHistorial(
                supervisor.id(), HECHO.plusNanos(1), HECHO.plusSeconds(1).plusNanos(1))
                .stream().map(d -> d.getIncidente().getId()).toList());
        assertEquals(List.of(inicio.getId()), supervisor.incidentes.consultarHistorial(
                supervisor.id(), HECHO, HECHO.plusSeconds(1)).stream().map(d -> d.getIncidente().getId()).toList());
    }

    @Test
    @DisplayName("Un supervisor consulta incidentes de distintas empresas del conjunto")
    void consultaElHistorialDeVariasEmpresas() {
        Visita luna = prepararVisita("marta@luna.sica.local", false);
        Incidente sol = registrar(visita, HECHO, "Hecho en Empresa Sol.");
        Incidente otro = registrar(luna, HECHO.plusMinutes(1), "Hecho en Empresa Luna.");
        List<DetalleIncidente> historial = supervisor.incidentes.consultarHistorial(
                supervisor.id(), HECHO, HECHO.plusDays(1));
        assertEquals(List.of(otro.getId(), sol.getId()), historial.stream().map(d -> d.getIncidente().getId()).toList());
        assertEquals(List.of("Empresa Luna", "Empresa Sol"), historial.stream().map(DetalleIncidente::getEmpresa).toList());
        assertEquals(luna.getPersonaId(), historial.get(0).getPersona().getId());
        assertEquals(visita.getPersonaId(), historial.get(1).getPersona().getId());
    }

    @Test
    @DisplayName("Registrar incidentes no impone ni retira prohibiciones ni altera la vinculación laboral")
    void mantieneSeparadosIncidentesYProhibiciones() {
        Visita trabajador = prepararVisita("ana@sol.sica.local", true);
        registrar(trabajador, HECHO, "El supervisor observó una situación.");
        gestor.ejecutar(r -> { comprobarAccesoSinCambios(r, trabajador, "Activo"); return null; });
        RestriccionAccesoCase restricciones = new RestriccionAccesoCase(supervisor.ejecutor);
        restricciones.imponerProhibicion(supervisor.id(), trabajador.getPersonaId(), "Decisión de seguridad independiente");
        registrar(trabajador, HECHO.plusMinutes(1), "Otra persona comunicó información adicional.");
        gestor.ejecutar(r -> { comprobarAccesoSinCambios(r, trabajador, "Con Prohibición de Ingreso"); return null; });
        restricciones.retirarProhibicion(supervisor.id(), trabajador.getPersonaId(), "Situación revisada");
        assertEquals(2, supervisor.incidentes.consultarPorVisita(supervisor.id(), trabajador.getId()).size());
        gestor.ejecutar(r -> { comprobarAccesoSinCambios(r, trabajador, "Activo"); return null; });
        assertEquals(2, restricciones.consultarSituacion(supervisor.id(), documento(trabajador)).orElseThrow().getHistorial().size());
    }

    @Test
    @DisplayName("Las cuentas sin los permisos de incidentes no pueden registrarlos ni consultar su historial")
    void respetaLosPermisosAcordados() {
        for (String correo : List.of("guarda@sica.local", "ana@sol.sica.local", "admin@sica.local")) {
            Aplicacion otra = ingresar(correo);
            assertThrows(SecurityException.class, () -> otra.incidentes.registrar(
                    otra.id(), visita.getId(), HECHO, "Intento sin permiso."));
            assertThrows(SecurityException.class, () -> otra.incidentes.consultarPorVisita(otra.id(), visita.getId()));
            assertThrows(SecurityException.class, () -> otra.incidentes.consultarHistorial(otra.id(), HECHO, HECHO.plusDays(1)));
        }
        assertTrue(supervisor.incidentes.consultarPorVisita(supervisor.id(), visita.getId()).isEmpty());
    }

    @Test
    @DisplayName("Rechaza datos incompletos o visitas inexistentes y mantiene los registros sin cambios")
    void validaLosDatosAntesDeGuardar() {
        assertThrows(IllegalArgumentException.class, () -> supervisor.incidentes.registrar(supervisor.id(), null, HECHO, "Hecho."));
        assertThrows(IllegalArgumentException.class, () -> supervisor.incidentes.registrar(supervisor.id(), visita.getId(), null, "Hecho."));
        for (String descripcion : Arrays.asList(null, "", "  ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> supervisor.incidentes.registrar(supervisor.id(), visita.getId(), HECHO, descripcion));
        }
        assertThrows(IllegalArgumentException.class,
                () -> supervisor.incidentes.registrar(supervisor.id(), Integer.MAX_VALUE, HECHO, "Visita inexistente."));
        assertThrows(IllegalArgumentException.class,
                () -> supervisor.incidentes.consultarPorVisita(supervisor.id(), Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> supervisor.incidentes.consultarHistorial(supervisor.id(), HECHO, HECHO));
        assertTrue(supervisor.incidentes.consultarPorVisita(supervisor.id(), visita.getId()).isEmpty());
        assertTrue(auditoriaDelSupervisor("REGISTRO_INCIDENTE").isEmpty());
        assertTrue(auditoriaDelSupervisor("REGISTRO_INCIDENTE_FALLIDO").isEmpty());
    }

    @Test
    @DisplayName("Si falla la auditoría, deshace el incidente y conserva el intento fallido por separado")
    void deshaceIncidenteSiFallaLaAuditoria() {
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> registrar(visita, HECHO, "No debe quedar guardado a medias."));
        assertTrue(supervisor.incidentes.consultarPorVisita(supervisor.id(), visita.getId()).isEmpty());
        assertTrue(auditoriaDelSupervisor("REGISTRO_INCIDENTE").isEmpty());
        List<BitacoraAuditoria> fallos = auditoriaDelSupervisor("REGISTRO_INCIDENTE_FALLIDO");
        assertEquals(1, fallos.size());
        assertNull(fallos.get(0).getRegistroIdAfectado());
        assertEquals("incidentes", fallos.get(0).getTablaAfectada());
        assertTrue(fallos.get(0).getDetalles().contains("Visita: " + visita.getId()));
        gestor.ejecutar(r -> { comprobarAccesoSinCambios(r, visita, "Activo"); return null; });
        Incidente reintento = registrar(visita, HECHO, "Registro confirmado después del reintento.");
        assertEquals(1, supervisor.incidentes.consultarPorVisita(supervisor.id(), visita.getId()).size());
        assertEquals(1, gestor.ejecutar(r -> auditoriasDeIncidente(r, reintento.getId())).size());
        assertEquals(1, auditoriaDelSupervisor("REGISTRO_INCIDENTE_FALLIDO").size());
    }

    @Test
    @DisplayName("Conserva el historial cuando se retiran la empresa y la cuenta del supervisor que lo registró")
    void conservaInformacionDeEmpresasYAutoresInactivos() {
        Usuario cuenta = gestor.ejecutar(r -> {
            Usuario modelo = r.crearUsuarioRepository().buscarPorId(supervisor.id()).orElseThrow();
            return r.crearUsuarioRepository().guardar(new Usuario("Supervisor histórico", UUID.randomUUID() + "@prueba.invalid",
                    modelo.getPasswordHash(), modelo.getRolId(), null));
        });
        Aplicacion autor = ingresar(cuenta.getEmail());
        Incidente registrado = autor.incidentes.registrar(autor.id(), visita.getId(), HECHO, "Información que debe conservarse.");
        Aplicacion admin = ingresar("admin@sica.local");
        new EmpresaUseCase(admin.ejecutor).desactivar(admin.id(), visita.getEmpresaDestinoId(), "Empresa retirada");
        new UsuarioCase(admin.ejecutor, new ServicioContrasenasPbkdf2()).desactivar(admin.id(), autor.id(), "Terminó su función");
        assertThrows(IllegalStateException.class, () -> autor.incidentes.consultarPorVisita(autor.id(), visita.getId()));
        DetalleIncidente detalle = supervisor.incidentes.consultarPorVisita(supervisor.id(), visita.getId()).get(0);
        assertEquals(registrado.getId(), detalle.getIncidente().getId());
        assertEquals("Supervisor histórico", detalle.getSupervisor());
        assertEquals("Empresa Sol", detalle.getEmpresa());
        assertEquals(visita.getPersonaId(), detalle.getPersona().getId());
        assertEquals(1, gestor.ejecutar(r -> r.crearIncidenteRepository().listarPorRegistradoPor(autor.id())).size());
        assertEquals(1, gestor.ejecutar(r -> auditoriasDeIncidente(r, registrado.getId())).size());
    }

    @Test
    @DisplayName("Exige una sesión vigente para registrar o consultar, incluso después de cerrar sesión")
    void requiereSesionParaOperar() {
        ContextoSesion vacio = new ContextoSesion();
        IncidenteCase sinSesion = new IncidenteCase(new EjecutorOperaciones(gestor, vacio), reloj);
        assertThrows(SecurityException.class, () -> sinSesion.registrar(supervisor.id(), visita.getId(), HECHO, "Sin sesión."));
        assertThrows(SecurityException.class, () -> sinSesion.consultarPorVisita(supervisor.id(), visita.getId()));
        Incidente guardado = registrar(visita, HECHO, "Hecho confirmado antes del cierre de sesión.");
        supervisor.close();
        assertThrows(SecurityException.class, () -> registrar(visita, HECHO, "Sesión cerrada."));
        assertThrows(SecurityException.class, () -> supervisor.incidentes.consultarPorVisita(supervisor.id(), visita.getId()));
        Aplicacion nueva = ingresar("supervisor@sica.local");
        assertEquals(List.of(guardado.getId()), nueva.incidentes.consultarPorVisita(nueva.id(), visita.getId())
                .stream().map(d -> d.getIncidente().getId()).toList());
    }

    // Son visitas ya terminadas; los recorridos de entrada y salida se comprobaron en los bloques anteriores.
    private Visita prepararVisita(String correoFuncionario, boolean trabajador) {
        return gestor.ejecutar(r -> {
            Usuario anfitrion = r.crearUsuarioRepository().buscarPorEmail(correoFuncionario).orElseThrow();
            Persona persona = r.crearPersonaRepository().guardar(new Persona(
                    trabajador ? "Pedro prueba" : "Laura prueba", "IC" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    trabajador ? anfitrion.getEmpresaId() : null,
                    trabajador ? TipoPersona.TRABAJADOR : TipoPersona.INVITADO,
                    r.crearEstadoAccesoPersonaRepository().buscarPorNombre("Activo").orElseThrow().getId(),
                    "foto-prueba.png", trabajador ? Boolean.TRUE : null));
            return r.crearVisitaRepository().guardar(new Visita(
                    persona.getId(), anfitrion.getEmpresaId(), trabajador ? null : anfitrion.getId(),
                    trabajador ? TipoIngreso.TRABAJADOR_CON_CARNET : TipoIngreso.INVITADO_PREREGISTRADO,
                    trabajador ? null : HECHO.minusHours(1), trabajador ? null : HECHO.minusHours(2),
                    HECHO.minusHours(1), HECHO.plusHours(1), null,
                    r.crearEstadoVisitaRepository().buscarPorNombre("Fuera").orElseThrow().getId(),
                    "ABC123", trabajador ? null : anfitrion.getId()));
        });
    }

    private Aplicacion ingresar(String correo) {
        Aplicacion app = new Aplicacion(correo);
        aplicaciones.add(app);
        return app;
    }

    private Incidente registrar(Visita visita, LocalDateTime fecha, String descripcion) {
        return supervisor.incidentes.registrar(supervisor.id(), visita.getId(), fecha, descripcion);
    }

    private void comprobarAccesoSinCambios(FabricarRepositorios r, Visita anterior, String estadoAcceso) {
        Visita actual = r.crearVisitaRepository().buscarPorId(anterior.getId()).orElseThrow();
        assertEquals(anterior.getEstadoVisitaId(), actual.getEstadoVisitaId());
        assertEquals(anterior.getFechaEntrada(), actual.getFechaEntrada());
        assertEquals(anterior.getFechaSalida(), actual.getFechaSalida());
        assertEquals(anterior.getFechaCierreSistema(), actual.getFechaCierreSistema());
        assertEquals(anterior.getEmpresaDestinoId(), actual.getEmpresaDestinoId());
        Persona persona = r.crearPersonaRepository().buscarPorId(anterior.getPersonaId()).orElseThrow();
        assertEquals(estadoAcceso, r.crearEstadoAccesoPersonaRepository().buscarPorId(persona.getEstadoAccesoId()).orElseThrow().getNombreEstado());
        if (persona.getTipoPersona() == TipoPersona.TRABAJADOR) {
            assertEquals(Boolean.TRUE, persona.getVinculacionLaboralActiva());
            assertEquals(anterior.getEmpresaDestinoId(), persona.getEmpresaId());
        } else {
            assertNull(persona.getEmpresaId());
            assertNull(persona.getVinculacionLaboralActiva());
        }
    }

    private List<BitacoraAuditoria> auditoriasDeIncidente(FabricarRepositorios r, Integer incidenteId) {
        return r.crearBitacoraAuditoriaRepository().listarPorRegistro("incidentes", incidenteId);
    }

    private List<BitacoraAuditoria> auditoriaDelSupervisor(String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(supervisor.id()))
                .stream().filter(a -> !auditoriasIniciales.contains(a.getId()))
                .filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }

    private String documento(Visita visita) {
        return gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorId(visita.getPersonaId()).orElseThrow().getDocumentoIdentidad());
    }
}
