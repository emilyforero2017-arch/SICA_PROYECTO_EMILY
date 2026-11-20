package com.sica.model.application.usecase;

import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.entity.Visita;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

// Avisa al funcionario de solicitudes pendientes y al guarda de las decisiones confirmadas.
public class AvisosSolicitudCase {

    private static final Logger LOG = Logger.getLogger(AvisosSolicitudCase.class.getName());
    private final EjecutorOperaciones ejecutor;
    private final SolicitudIngresoCase solicitudes;

    public AvisosSolicitudCase(EjecutorOperaciones ejecutor) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
        this.solicitudes = new SolicitudIngresoCase(this.ejecutor);
    }

    public List<AvisoSolicitud> consultarAvisos(Integer guardaId, Set<Long> yaRecibidos) {
        Set<Long> recibidos = Set.copyOf(Objects.requireNonNull(yaRecibidos, "Los avisos ya recibidos son obligatorios."));
        return ejecutor.ejecutar(guardaId, "consultar_visita_porteria", (repositorios, guarda) -> {
            if (!repositorios.crearPermisoRepository().tienePermiso(guarda.getRolId(), "consultar_persona_porteria")) {
                throw new SecurityException("La cuenta no tiene permiso para consultar a la persona en portería.");
            }
            List<AvisoSolicitud> avisos = new ArrayList<>();
            Map<Integer, String> nombresFuncionarios = new HashMap<>();
            Set<Integer> solicitudes = new HashSet<>();
            for (BitacoraAuditoria registro : repositorios.crearBitacoraAuditoriaRepository().listarPorUsuario(guardaId)) {
                if (guardaId.equals(registro.getUsuarioId()) && "visitas".equals(registro.getTablaAfectada())
                        && (SolicitudIngresoCase.SOLICITUD_CREADA.equals(registro.getAccionRealizada())
                            || SolicitudIngresoCase.SOLICITUD_OLVIDO_CREADA.equals(registro.getAccionRealizada()))
                        && registro.getRegistroIdAfectado() != null) {
                    solicitudes.add(registro.getRegistroIdAfectado());
                }
            }
            for (Integer solicitudId : solicitudes) {
                Visita solicitud = repositorios.crearVisitaRepository().buscarPorId(solicitudId)
                        .orElseThrow(() -> new IllegalStateException("La solicitud del aviso no existe."));
                if (solicitud.getTipoIngreso() != TipoIngreso.INVITADO_NO_ANUNCIADO
                        && solicitud.getTipoIngreso() != TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO) {
                    continue;
                }
                Persona persona = repositorios.crearPersonaRepository().buscarPorId(solicitud.getPersonaId())
                        .orElseThrow(() -> new IllegalStateException("La persona de la solicitud no existe."));
                for (BitacoraAuditoria decision : repositorios.crearBitacoraAuditoriaRepository().listarPorRegistro("visitas", solicitudId)) {
                    boolean aprobada = SolicitudIngresoCase.SOLICITUD_APROBADA.equals(decision.getAccionRealizada());
                    boolean rechazada = SolicitudIngresoCase.SOLICITUD_RECHAZADA.equals(decision.getAccionRealizada());
                    if (!"visitas".equals(decision.getTablaAfectada()) || !solicitudId.equals(decision.getRegistroIdAfectado())
                            || (!aprobada && !rechazada) || decision.getId() == null || recibidos.contains(decision.getId())
                            || !Objects.equals(solicitud.getFuncionarioDestinatarioId(), decision.getUsuarioId())) {
                        continue;
                    }
                    String resultado = aprobada ? "Aprobado" : "Rechazado";
                    String funcionario = nombresFuncionarios.computeIfAbsent(decision.getUsuarioId(), id ->
                            repositorios.crearUsuarioRepository().buscarPorId(id).map(Usuario::getNombre).orElse("Funcionario no disponible"));
                    avisos.add(new AvisoSolicitud(decision.getId(), guardaId, solicitudId, decision.getFechaHora(), resultado, funcionario,
                            funcionario + " " + (aprobada ? "aprobó" : "rechazó") + " la solicitud de " + persona.getNombre()
                            + ". Consulta su estado actual en portería."));
                }
            }
            // Recordamos identificadores concretos: un aviso que se confirme después no se pierde aunque tenga un número menor.
            return avisos.stream().sorted(Comparator.comparing(AvisoSolicitud::getFecha).thenComparing(AvisoSolicitud::getId)).toList();
        });
    }

    public List<AvisoSolicitudPendiente> consultarAvisosFuncionario(Integer funcionarioId, Set<Integer> yaRecibidas) {
        Set<Integer> recibidas = Set.copyOf(Objects.requireNonNull(yaRecibidas, "Las solicitudes ya avisadas son obligatorias."));
        // La consulta existente comprueba la cuenta, la empresa, el permiso y el destinatario de cada solicitud.
        // No se abre otra transacción alrededor de ella: devuelve solo solicitudes ya confirmadas.
        return solicitudes.listarPendientes(funcionarioId).stream()
                .filter(detalle -> !recibidas.contains(detalle.getSolicitud().getId()))
                .map(detalle -> {
                    Visita solicitud = detalle.getSolicitud();
                    Persona persona = detalle.getPersona();
                    String situacion = solicitud.getTipoIngreso() == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO
                            ? " olvidó su carnet y está esperando autorización para ingresar."
                            : " está esperando autorización para ingresar.";
                    return new AvisoSolicitudPendiente(solicitud.getId(), solicitud.getFuncionarioDestinatarioId(),
                            persona.getId(), persona.getNombre(), solicitud.getTipoIngreso(),
                            "Nueva solicitud: " + persona.getNombre() + situacion);
                }).toList();
    }

    // Revisa en segundo plano cada segundo; el receptor será utilizado después por la presentación.
    public AutoCloseable observar(Integer guardaId, Consumer<AvisoSolicitud> receptor, Consumer<RuntimeException> manejarError) {
        Objects.requireNonNull(guardaId, "El guarda es obligatorio.");
        return observarCambios(guardaId, "sica-avisos-solicitudes", recibidos -> consultarAvisos(guardaId, recibidos),
                AvisoSolicitud::getId, receptor, manejarError);
    }

    public AutoCloseable observarFuncionario(Integer funcionarioId, Consumer<AvisoSolicitudPendiente> receptor,
            Consumer<RuntimeException> manejarError) {
        Objects.requireNonNull(funcionarioId, "El funcionario es obligatorio.");
        return observarCambios(funcionarioId, "sica-avisos-funcionario", recibidas -> consultarAvisosFuncionario(funcionarioId, recibidas),
                AvisoSolicitudPendiente::getSolicitudId, receptor, manejarError);
    }

    // Aviso representa el tipo de mensaje y Clave su identificador, para compartir el seguimiento de ambos roles.
    private <Aviso, Clave> AutoCloseable observarCambios(Integer usuarioId, String nombreHilo,
            Function<Set<Clave>, List<Aviso>> consultar, Function<Aviso, Clave> identificar,
            Consumer<Aviso> receptor, Consumer<RuntimeException> manejarError) {
        Objects.requireNonNull(receptor, "El receptor de avisos es obligatorio.");
        Objects.requireNonNull(manejarError, "El receptor de errores es obligatorio.");
        ScheduledExecutorService programador = Executors.newSingleThreadScheduledExecutor(trabajo -> {
            Thread hilo = new Thread(trabajo, nombreHilo);
            hilo.setDaemon(true);
            return hilo;
        });
        Set<Clave> recibidos = new HashSet<>();
        // Se vincula antes de iniciar las revisiones para que cerrar sesión pueda detenerlas desde el comienzo.
        AutoCloseable cierre = ejecutor.vincularSeguimiento(usuarioId, programador::shutdownNow);
        try {
            programador.scheduleWithFixedDelay(() -> {
                try {
                    // consultar recibe los identificadores avisados y devuelve los mensajes que siguen pendientes de entregar.
                    List<Aviso> avisos = consultar.apply(recibidos);
                    // La consulta ya terminó y confirmó su transacción antes de comunicar el aviso.
                    for (Aviso aviso : avisos) {
                        if (programador.isShutdown()) {
                            return;
                        }
                        try {
                            ejecutor.conSesion(usuarioId, () -> {
                                if (!programador.isShutdown()) {
                                    receptor.accept(aviso);
                                    // Si el receptor falla, no se marca como recibido y se reintenta.
                                    recibidos.add(identificar.apply(aviso));
                                }
                                return null;
                            });
                        } catch (RuntimeException error) {
                            if (!programador.isShutdown()) {
                                comunicarError(usuarioId, programador, manejarError, error);
                            }
                        }
                    }
                } catch (RuntimeException error) {
                    if (!programador.isShutdown()) {
                        comunicarError(usuarioId, programador, manejarError, error);
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);
        } catch (RuntimeException error) {
            try {
                cierre.close();
            } catch (Exception falloCierre) {
                error.addSuppressed(falloCierre);
            }
            throw error;
        }
        return cierre;
    }

    private void comunicarError(Integer usuarioId, ScheduledExecutorService programador,
            Consumer<RuntimeException> receptor, RuntimeException error) {
        try {
            ejecutor.conSesion(usuarioId, () -> {
                if (!programador.isShutdown()) {
                    comunicarError(receptor, error);
                }
                return null;
            });
        } catch (SecurityException sesionTerminada) {
            // El cierre también evita entregar errores pendientes a la sesión anterior.
        }
    }

    private void comunicarError(Consumer<RuntimeException> receptor, RuntimeException error) {
        try {
            receptor.accept(error);
        } catch (RuntimeException falloReceptor) {
            LOG.log(Level.WARNING, "No fue posible comunicar el error de seguimiento de solicitudes.", falloReceptor);
        }
    }
}
