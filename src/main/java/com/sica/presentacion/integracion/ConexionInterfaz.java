package com.sica.presentacion.integracion;

import com.sica.config.AplicacionSica;
import com.sica.model.application.usecase.*;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.repository.MovimientoPorteria;
import com.sica.model.domain.repository.ResumenPorteria;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static com.sica.presentacion.integracion.SicaData.*;

public final class ConexionInterfaz {
    public final AplicacionSica app;
    public final SesionUsuario sesion;
    public final int usuario;
    public final Profile perfil;

    public ConexionInterfaz(AplicacionSica app, SesionUsuario sesion) {
        this.app = app;
        this.sesion = sesion;
        usuario = sesion.getUsuarioId();
        perfil = SicaData.perfil(sesion.getNombreRol());
    }

    public List<Movement> movimientosPorteria(MovimientoPorteria.Tipo tipo) {
        Status estado = switch (tipo) {
            case ENTRADA_INVITADO -> Status.ENTRY;
            case INGRESO_LABORAL -> Status.WORK;
            case SALIDA -> Status.EXIT;
        };
        return app.getResumenPorteria().consultarMovimientos(usuario, tipo).stream()
                .map(m -> new Movement(persona(m.persona(), "", m.empresa()), m.hora().toLocalDate(),
                        m.hora().toLocalTime(), estado)).toList();
    }

    public List<Audit> auditoria(LocalDate desde, LocalDate hasta, boolean propia) {
        var inicio = desde.atStartOfDay();
        var fin = hasta.plusDays(1).atStartOfDay();
        return (propia ? app.getAuditoria().consultarPropia(usuario, inicio, fin)
                : app.getAuditoria().consultarGeneral(usuario, inicio, fin)).stream()
                .map(ConexionInterfaz::auditoria).toList();
    }

    public Portada portada(LocalDate dia) {
        var ahora = dia.atStartOfDay();
        return switch (perfil) {
            case ADMIN -> new Portada(List.of(), List.of(), auditoria(dia.minusDays(1), dia, false), List.of(),
                    app.getUsuarios().listar(usuario).size(), 0, 0, null, null);
            case EMPLOYEE -> {
                var solicitudes = app.getSolicitudes().listarAsignadas(usuario).stream().map(this::solicitud).toList();
                var agenda = app.getVisitas().listarProgramadas(usuario).stream().map(this::programada).toList();
                yield new Portada(Stream.concat(agenda.stream(), solicitudes.stream()).toList(), List.of(), List.of(),
                        List.of(), 0, 0, (int) solicitudes.stream().filter(v -> v.status() == Status.PENDING).count(), null, null);
            }
            case GUARD -> {
                var resumen = app.getResumenPorteria().consultar(usuario);
                var previstas = resumen.llegadas().stream().map(llegada -> {
                    var v = llegada.visita();
                    return visita(v.id(), persona(v.invitado(), v.documento(), v.empresa()),
                            v.funcionario() + " · " + v.empresa(), v.fechaProgramada(), Status.SCHEDULED);
                }).toList();
                var movimientos = Stream.of(MovimientoPorteria.Tipo.values()).flatMap(tipo -> movimientosPorteria(tipo).stream())
                        .sorted(Comparator.comparing(Movement::date).thenComparing(Movement::time).reversed()).limit(9).toList();
                var t = resumen.totales();
                yield new Portada(previstas, movimientos, List.of(), List.of(), Math.toIntExact(t.entradas()),
                        Math.toIntExact(t.salidas()), Math.toIntExact(t.solicitudesPendientes()),
                        app.getResumenPorteria().consultarActividad(usuario, true), app.getResumenPorteria().consultarActividad(usuario, false));
            }
            case SUPERVISOR -> {
                var lunes = dia.minusDays(dia.getDayOfWeek().getValue() - 1L).atStartOfDay();
                var registros = app.getSeguridad().consultarHistorial(usuario, lunes, ahora.plusDays(1));
                var semana = new java.util.ArrayList<ResumenPorteria>();
                var hoy = new java.util.ArrayList<ResumenPorteria>();
                for (int i = 0; i < 7; i++) {
                    var inicio = lunes.plusDays(i);
                    semana.add(conteo(registros, inicio, inicio.plusDays(1)));
                }
                for (int i = 0; i < 6; i++) {
                    var inicio = ahora.plusHours(i * 4L);
                    hoy.add(conteo(registros, inicio, inicio.plusHours(4)));
                }
                var conteo = conteo(registros, ahora, ahora.plusDays(1));
                var incidentes = app.getIncidentes().consultarHistorial(usuario, ahora.minusDays(29), ahora.plusDays(1))
                        .stream().map(ConexionInterfaz::incidente).toList();
                yield new Portada(List.of(), List.of(), auditoria(dia.minusDays(1), dia, false), incidentes,
                        Math.toIntExact(conteo.entradas()), Math.toIntExact(conteo.salidas()), 0, semana, hoy);
            }
        };
    }

    private static ResumenPorteria conteo(List<DetalleMovimientoAcceso> registros, LocalDateTime desde, LocalDateTime hasta) {
        var periodo = registros.stream().filter(m -> !m.getFechaMovimiento().isBefore(desde)
                && m.getFechaMovimiento().isBefore(hasta)).toList();
        return new ResumenPorteria(periodo.stream().filter(m -> m.getTipo() == TipoMovimientoAcceso.ENTRADA).count(),
                periodo.stream().filter(m -> m.getTipo() == TipoMovimientoAcceso.SALIDA).count(), 0);
    }

    public record Portada(List<Visit> visitas, List<Movement> movimientos, List<Audit> auditoria,
            List<Incident> incidentes, int entradas, int salidas, int pendientes,
            List<ResumenPorteria> semana, List<ResumenPorteria> hoy) { }

    public Visit programada(DetalleVisitaProgramada d) {
        return visita(d.getVisita().getId(), persona(d.getPersona(), sesion.getNombreEmpresa()), "Visita programada",
                d.getVisita().getFechaProgramada(), estado(d.getEstado().getNombreEstado(), true));
    }

    public Visit solicitud(DetalleSolicitudIngreso d) {
        var v = d.getSolicitud();
        return visita(v.getId(), persona(d.getPersona(), sesion.getNombreEmpresa()), tipo(v.getTipoIngreso()),
                fecha(v), estado(d.getEstado().getNombreEstado(), false));
    }

    public static Visit acceso(DetalleAccesoSeguridad d) {
        var v = d.getVisita();
        return visita(v.getId(), persona(d.getPersona(), d.getEmpresa()), tipo(v.getTipoIngreso()),
                fecha(v), estado(d.getEstado().getNombreEstado(), v.getTipoIngreso() == TipoIngreso.INVITADO_PREREGISTRADO));
    }

    public static Incident incidente(DetalleIncidente d) {
        var i = d.getIncidente();
        var visita = visita(d.getVisita().getId(), persona(d.getPersona(), d.getEmpresa()),
                tipo(d.getVisita().getTipoIngreso()), fecha(d.getVisita()), Status.LOGGED);
        return new Incident(visita, i.getFechaIncidente().toLocalDate(), i.getFechaIncidente().toLocalTime(), i.getDescripcion());
    }

    public static Audit auditoria(DetalleAuditoria d) {
        var r = d.getRegistro();
        String codigo = r.getAccionRealizada();
        String grupo = switch (Objects.toString(r.getTablaAfectada(), "")) {
            case "usuarios", "empresas", "rol_permisos" -> codigo.startsWith("LOGIN") ? "Sesiones" : "Administración";
            case "incidentes", "personas" -> "Seguridad";
            case "visitas" -> codigo.contains("SOLICITUD") ? "Autorizaciones" : "Registros";
            default -> codigo.startsWith("LOGIN") ? "Sesiones" : "Administración";
        };
        return new Audit(codigo.replace('_', ' '), d.getNombreResponsable(), Objects.toString(r.getDetalles(), ""),
                r.getFechaHora().toLocalDate(), r.getFechaHora().toLocalTime(), grupo);
    }

    public static String tipo(TipoIngreso tipo) {
        return switch (tipo) {
            case INVITADO_PREREGISTRADO -> "Visita programada";
            case INVITADO_NO_ANUNCIADO -> "Visita sin cita";
            case TRABAJADOR_CON_CARNET -> "Ingreso laboral";
            case TRABAJADOR_CON_CARNET_OLVIDADO -> "Carné olvidado";
        };
    }

    public static Status estado(String estado, boolean programada) {
        if (estado.startsWith("Pendiente")) return Status.PENDING;
        if (estado.startsWith("Cerrada")) return Status.CLOSED;
        return switch (estado) {
            case "Aprobado" -> programada ? Status.SCHEDULED : Status.APPROVED;
            case "Rechazado" -> Status.REJECTED;
            case "Expirado" -> Status.EXPIRED;
            case "Cancelada", "Cancelado" -> Status.CANCELLED;
            case "Dentro" -> Status.INSIDE;
            default -> Status.LOGGED;
        };
    }

    public static LocalDateTime fecha(Visita v) {
        if (v.getFechaProgramada() != null) return v.getFechaProgramada();
        if (v.getFechaAprobacion() != null) return v.getFechaAprobacion();
        return v.getFechaEntrada();
    }

    public static Visit visita(int id, Person persona, String tipo, LocalDateTime fecha, Status estado) {
        return new Visit(id, persona, tipo, fecha == null ? null : fecha.toLocalDate(),
                fecha == null ? null : fecha.toLocalTime(), estado);
    }

    public static Person persona(Persona p, String empresa) {
        return persona(p.getNombre(), p.getDocumentoIdentidad(), empresa);
    }

    public static Person persona(String nombre, String documento, String empresa) {
        return new Person(nombre, documento, Objects.toString(empresa, "Zona ACME"), -1, Status.WAITING);
    }

    public static Company empresa(Empresa empresa) {
        var nombre = empresa.getNombre();
        String icono = nombre.toLowerCase(java.util.Locale.ROOT).endsWith("luna") ? "moon"
                : nombre.toLowerCase(java.util.Locale.ROOT).endsWith("sol") ? "sun" : "building";
        return new Company(empresa.getId(), nombre, Objects.toString(empresa.getContactoPrincipal(), "Sin contacto"),
                empresa.isEstaActiva() ? Status.ACTIVE : Status.INACTIVE, icono, empresa.getLogo());
    }
}
