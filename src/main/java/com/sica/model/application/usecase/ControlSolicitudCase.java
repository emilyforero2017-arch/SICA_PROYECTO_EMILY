package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.EstadoVisita;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.TipoPersona;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.policy.HorarioIngreso;
import com.sica.model.domain.policy.HorarioCarnetOlvidado;
import com.sica.model.domain.policy.HorarioVisitaNoAnunciada;
import com.sica.model.domain.repository.SolicitudEnPorteria;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// Consulta el resultado y confirma entradas de solicitudes sin agenda o por carnet olvidado.
public class ControlSolicitudCase {

    private final EjecutorOperaciones ejecutor;
    private final Clock reloj;

    public ControlSolicitudCase(EjecutorOperaciones ejecutor) {
        this(ejecutor, Clock.system(ZoneId.of("America/Bogota")));
    }

    public ControlSolicitudCase(EjecutorOperaciones ejecutor, Clock reloj) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
        this.reloj = Objects.requireNonNull(reloj);
    }

    public List<RespuestaEnPorteria> listarRespuestas(Integer guardaId) {
        return ejecutor.ejecutarConPermisos(guardaId,
                List.of("consultar_visita_porteria", "consultar_persona_porteria"), (repositorios, guarda) -> {
                    List<SolicitudEnPorteria> solicitudes = repositorios.crearVisitaRepository().listarSolicitudesEnviadas(guarda.getId());
                    LocalDateTime ahora = LocalDateTime.now(reloj);
                    return solicitudes.stream()
                            .map(solicitud -> resumirRespuesta(solicitud, ahora))
                            .toList();
                });
    }

    private RespuestaEnPorteria resumirRespuesta(SolicitudEnPorteria fila, LocalDateTime ahora) {
        Visita visita = fila.visita();
        String estado = fila.estado();
        HorarioIngreso horario = "Aprobado".equals(estado) ? horarioAutorizado(visita) : null;
        if (horario != null && ahora.isAfter(horario.getHasta())) {
            estado = "Expirado";
        }
        return new RespuestaEnPorteria(visita.getId(), fila.persona(), fila.documento(), fila.empresa(),
                fila.funcionario(), estado, horario == null ? null : horario.getHasta(),
                horario != null && horario.contiene(ahora), visita.getTipoIngreso(), visita.getFechaAprobacion());
    }

    public record RespuestaEnPorteria(int id, String persona, String documento, String empresa,
            String funcionario, String estado, LocalDateTime hasta, boolean enHorario,
            TipoIngreso tipo, LocalDateTime aprobacion) { }

    public ResultadoSolicitudPorteria consultarResultado(Integer guardaId, Integer solicitudId) {
        if (solicitudId == null) {
            throw new IllegalArgumentException("La solicitud a consultar es obligatoria.");
        }
        IntentoGuardado intento = new IntentoGuardado("ACTUALIZACION_VIGENCIA_FALLIDA", "visitas", solicitudId);
        return ejecutor.ejecutarGuardado(guardaId, "consultar_visita_porteria", intento, (repositorios, guarda) -> {
            if (!repositorios.crearPermisoRepository().tienePermiso(guarda.getRolId(), "consultar_persona_porteria")) {
                throw new SecurityException("La cuenta no tiene permiso para consultar a la persona en portería.");
            }
            return evaluar(repositorios, solicitudId, null, intento);
        });
    }

    public Visita registrarEntrada(Integer guardaId, Integer solicitudId, String documento) {
        if (solicitudId == null || documento == null || documento.isBlank() || documento.length() > 20) {
            throw new IllegalArgumentException("La solicitud y un documento válido de hasta 20 caracteres son obligatorios.");
        }
        IntentoGuardado intento = new IntentoGuardado("REGISTRO_ENTRADA_FALLIDO", "visitas", solicitudId,
                "Documento presentado: " + documento);
        Optional<Visita> resultado = ejecutor.ejecutarGuardado(guardaId, "registrar_entrada", intento, (repositorios, guarda) -> {
            // No se utiliza como autorización un aviso ni una consulta anterior: volvemos a comprobar todo.
            ResultadoSolicitudPorteria actual = evaluar(repositorios, solicitudId, documento, intento);
            if (!actual.isPermiteIngreso()) {
                if ("Expirado".equals(actual.getEstado().getNombreEstado())) {
                    return Optional.empty();
                }
                throw new IllegalStateException(actual.getMensaje());
            }
            Visita solicitud = actual.getSolicitud();
            Integer dentroId = estadoId(repositorios, "Dentro");
            Optional<Visita> anterior = repositorios.crearVisitaRepository()
                    .buscarIngresoAbiertoPorPersonaParaActualizar(actual.getPersona().getId());
            Integer cierreId = anterior.isPresent()
                    ? estadoId(repositorios, "Cerrada por Sistema - Salida no registrada") : null;
            LocalDateTime ahora = LocalDateTime.now(reloj);

            // Se comprueba el límite otra vez después de obtener los registros necesarios para guardar.
            if (!actual.getHorario().orElseThrow().contiene(ahora)) {
                if (VencimientoVisitasCase.expirarSiCorresponde(repositorios, solicitud, ahora, intento)) {
                    return Optional.empty();
                }
                throw new IllegalStateException("La autorización está fuera de su horario de ingreso.");
            }
            if (anterior.isPresent() && (anterior.get().getId().equals(solicitudId)
                    || anterior.get().getFechaEntrada().isAfter(ahora))) {
                throw new IllegalStateException("El ingreso anterior no se puede regularizar en este momento.");
            }

            intento.iniciar();
            if (anterior.isPresent()) {
                // El nuevo ingreso confirma que volvió, pero no conocemos la hora real de la salida anterior.
                Visita abierta = anterior.get();
                abierta.actualizarMovimiento(cierreId, abierta.getFechaEntrada(), null, ahora);
                actualizar(repositorios, abierta);
                auditar(repositorios, guardaId, ahora, "CIERRE_SALIDA_NO_REGISTRADA", abierta.getId(),
                        "Se regulariza al confirmar un nuevo ingreso. La hora real de salida se desconoce.");
            }
            solicitud.actualizarMovimiento(dentroId, ahora, null, null);
            actualizar(repositorios, solicitud);
            auditar(repositorios, guardaId, ahora, "REGISTRO_ENTRADA", solicitudId,
                    "Persona: " + solicitud.getPersonaId() + "; empresa: " + solicitud.getEmpresaDestinoId()
                    + "; funcionario: " + solicitud.getFuncionarioDestinatarioId() + "; entrada: " + ahora);
            return Optional.of(solicitud);
        });
        // La expiración se confirma antes de comunicar que la autorización ya no permite entrar.
        return resultado.orElseThrow(() -> new IllegalStateException(
                "La autorización está expirada y ya no permite ingresar."));
    }

    private Integer estadoId(FabricarRepositorios repositorios, String nombre) {
        return repositorios.crearEstadoVisitaRepository().buscarPorNombre(nombre)
                .orElseThrow(() -> new IllegalStateException("No está configurado el estado: " + nombre)).getId();
    }

    private void actualizar(FabricarRepositorios repositorios, Visita visita) {
        if (!repositorios.crearVisitaRepository().actualizar(visita)) {
            throw new IllegalStateException("No fue posible guardar el ingreso o su regularización.");
        }
    }

    private void auditar(FabricarRepositorios repositorios, Integer guardaId, LocalDateTime ahora,
            String accion, Integer visitaId, String detalles) {
        repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                guardaId, ahora, accion, "visitas", visitaId, detalles));
    }

    // Reutiliza las mismas comprobaciones dentro de la transacción de consulta o de entrada.
    private ResultadoSolicitudPorteria evaluar(
            FabricarRepositorios repositorios, Integer solicitudId, String documento, IntentoGuardado intento) {
        Visita referencia = repositorios.crearVisitaRepository().buscarPorId(solicitudId)
                .orElseThrow(() -> new IllegalArgumentException("La solicitud no existe."));
        if (!esSolicitud(referencia.getTipoIngreso())) {
            throw new IllegalArgumentException("Esta operación corresponde a solicitudes sin agenda o por carnet olvidado.");
        }

        // Leemos los datos actuales en el mismo orden que las demás operaciones de acceso.
        Empresa empresa = repositorios.crearEmpresaRepository().buscarPorIdParaActualizar(referencia.getEmpresaDestinoId())
                .orElseThrow(() -> new IllegalStateException("La empresa de destino no existe."));
        Persona persona = (documento == null
                ? repositorios.crearPersonaRepository().buscarPorIdParaActualizar(referencia.getPersonaId())
                : repositorios.crearPersonaRepository().buscarPorDocumentoParaActualizar(documento))
                .orElseThrow(() -> new IllegalArgumentException("La persona no está registrada con ese documento."));
        if (!persona.getId().equals(referencia.getPersonaId())) {
            throw new IllegalArgumentException("La solicitud no corresponde al documento presentado.");
        }
        Visita solicitud = repositorios.crearVisitaRepository().buscarPorIdParaActualizar(solicitudId)
                .orElseThrow(() -> new IllegalArgumentException("La solicitud no existe."));
        if (!esSolicitud(solicitud.getTipoIngreso())
                || !empresa.getId().equals(solicitud.getEmpresaDestinoId())
                || !persona.getId().equals(solicitud.getPersonaId())) {
            throw new IllegalStateException("Los datos de la solicitud cambiaron; vuelve a consultarla.");
        }
        Usuario funcionario = repositorios.crearUsuarioRepository().buscarPorIdParaActualizar(solicitud.getFuncionarioDestinatarioId())
                .orElseThrow(() -> new IllegalStateException("El funcionario destinatario no existe."));
        String rol = repositorios.crearRolRepository().buscarPorId(funcionario.getRolId())
                .orElseThrow(() -> new IllegalStateException("El rol del destinatario no existe.")).getNombreRol();
        String estadoAcceso = repositorios.crearEstadoAccesoPersonaRepository().buscarPorId(persona.getEstadoAccesoId())
                .orElseThrow(() -> new IllegalStateException("El estado de acceso de la persona no está configurado.")).getNombreEstado();
        LocalDateTime ahora = LocalDateTime.now(reloj);
        HorarioIngreso horario = horarioAutorizado(solicitud);
        if (horario != null) {
            VencimientoVisitasCase.expirarSiCorresponde(repositorios, solicitud, ahora, intento);
        }
        EstadoVisita estado = repositorios.crearEstadoVisitaRepository().buscarPorId(solicitud.getEstadoVisitaId())
                .orElseThrow(() -> new IllegalStateException("El estado de la solicitud no está configurado."));
        String impedimento = impedimento(solicitud, persona, estado, estadoAcceso, empresa, funcionario, rol, horario, ahora);
        return new ResultadoSolicitudPorteria(solicitud, persona, estado, empresa.getNombre(), funcionario.getNombre(),
                horario, impedimento == null, impedimento == null ? "Aprobada y vigente. Puede confirmar la entrada." : impedimento);
    }

    private HorarioIngreso horarioAutorizado(Visita solicitud) {
        if (solicitud.getFechaAprobacion() == null || solicitud.getAprobadaPorId() == null) {
            return null;
        }
        // El pase por carné olvidado termina, como máximo, al finalizar el día de aprobación.
        return solicitud.getTipoIngreso() == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO
                ? new HorarioCarnetOlvidado().calcular(solicitud) : new HorarioVisitaNoAnunciada().calcular(solicitud);
    }

    private boolean esSolicitud(TipoIngreso tipo) {
        return tipo == TipoIngreso.INVITADO_NO_ANUNCIADO || tipo == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO;
    }

    private String impedimento(Visita solicitud, Persona persona, EstadoVisita estado, String estadoAcceso,
            Empresa empresa, Usuario funcionario, String rol, HorarioIngreso horario, LocalDateTime ahora) {
        if ("Pendiente de Aprobación".equals(estado.getNombreEstado())
                || "Pendiente de Aprobación por Olvido".equals(estado.getNombreEstado())) {
            return "La solicitud está pendiente de la decisión del funcionario.";
        }
        if ("Rechazado".equals(estado.getNombreEstado())) {
            return "El funcionario rechazó la solicitud; no permite ingresar.";
        }
        if ("Expirado".equals(estado.getNombreEstado())) {
            return "La autorización está expirada; ya terminó su horario de ingreso.";
        }
        if (!"Aprobado".equals(estado.getNombreEstado()) || solicitud.getFechaEntrada() != null
                || solicitud.getFechaSalida() != null || solicitud.getFechaCierreSistema() != null) {
            return "La solicitud ya tiene un movimiento o no conserva una autorización aprobada.";
        }
        if (horario == null || !funcionario.getId().equals(solicitud.getAprobadaPorId())
                || solicitud.getFechaAprobacion().isAfter(ahora)) {
            return "Los datos de la aprobación no son válidos.";
        }
        if (!"Activo".equals(estadoAcceso)) {
            return "La persona tiene una restricción de ingreso; debe revisarla el supervisor de seguridad.";
        }
        // El pase sustituye al carnet olvidado; el trabajador debe conservar su vínculo con esa empresa.
        if (solicitud.getTipoIngreso() == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO
                && (persona.getTipoPersona() != TipoPersona.TRABAJADOR
                || !empresa.getId().equals(persona.getEmpresaId())
                || !Boolean.TRUE.equals(persona.getVinculacionLaboralActiva()))) {
            return "El trabajador ya no tiene una vinculación laboral activa con la empresa del pase.";
        }
        if (!empresa.isEstaActiva()) {
            return "La empresa de destino está desactivada.";
        }
        if (!funcionario.isEstaActivo() || !empresa.getId().equals(funcionario.getEmpresaId())
                || !"Funcionario de Empresa".equals(rol)) {
            return "El funcionario destinatario ya no está habilitado para recibir la visita.";
        }
        if (!horario.contiene(ahora)) {
            return "La autorización está fuera de su horario de ingreso.";
        }
        return null;
    }
}
