package com.sica.model.application.usecase;

import com.sica.model.domain.policy.HorarioIngreso;
import com.sica.model.domain.policy.HorarioVisitaProgramada;
import com.sica.model.domain.repository.MovimientoPorteria;
import com.sica.model.domain.repository.ResumenPorteria;
import com.sica.model.domain.repository.VisitaPrevista;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

public final class ResumenPorteriaCase {
    private static final ZoneId ZONA = ZoneId.of("America/Bogota");
    private static final int MAX_LLEGADAS = 5;
    private final EjecutorOperaciones ejecutor;
    private final Clock reloj;
    private final HorarioVisitaProgramada horarioVisita = new HorarioVisitaProgramada();

    public ResumenPorteriaCase(EjecutorOperaciones ejecutor) {
        this(ejecutor, Clock.system(ZONA));
    }

    public ResumenPorteriaCase(EjecutorOperaciones ejecutor, Clock reloj) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
        this.reloj = Objects.requireNonNull(reloj);
    }

    public Inicio consultar(Integer guardaId) {
        return ejecutor.ejecutar(guardaId, "consultar_visita_porteria", (repositorios, guarda) -> {
            LocalDateTime ahora = LocalDateTime.now(reloj);
            LocalDateTime desde = ahora.toLocalDate().atStartOfDay();
            LocalDateTime hasta = desde.plusDays(1);
            var visitas = repositorios.crearVisitaRepository();
            ResumenPorteria totales = visitas.resumirPorteria(guarda.getId(), desde, hasta);
            // Para mostrar la identidad se requiere el mismo permiso que en la búsqueda por documento.
            boolean consultaPersonas = repositorios.crearPermisoRepository()
                    .tienePermiso(guarda.getRolId(), "consultar_persona_porteria");
            List<Llegada> llegadas = consultaPersonas
                    ? visitas.listarVisitasPrevistas(desde, hasta).stream()
                            .map(visita -> new Llegada(visita, horarioVisita.calcular(visita.fechaProgramada())))
                            .filter(llegada -> !ahora.isAfter(llegada.horario().getHasta()))
                            .limit(MAX_LLEGADAS)
                            .toList()
                    : List.of();
            return new Inicio(totales, llegadas, consultaPersonas);
        });
    }

    public List<MovimientoPorteria> consultarMovimientos(Integer guardaId, MovimientoPorteria.Tipo tipo) {
        Objects.requireNonNull(tipo, "El tipo de movimiento es obligatorio.");
        return ejecutor.ejecutarConPermisos(guardaId, List.of("consultar_visita_porteria", "consultar_persona_porteria"),
                (repositorios, guarda) -> {
                    LocalDateTime desde = LocalDateTime.now(reloj).toLocalDate().atStartOfDay();
                    return repositorios.crearVisitaRepository()
                            .listarMovimientosPorteria(guarda.getId(), desde, desde.plusDays(1), tipo);
                });
    }

    public List<ResumenPorteria> consultarActividad(Integer guardaId, boolean porDia) {
        return ejecutor.ejecutar(guardaId, "consultar_visita_porteria", (repositorios, guarda) -> {
            var hoy = LocalDateTime.now(reloj).toLocalDate().atStartOfDay();
            var inicio = porDia ? hoy.minusDays(hoy.getDayOfWeek().getValue() - 1L) : hoy;
            var visitas = repositorios.crearVisitaRepository();
            int bloques = porDia ? 7 : 6;
            return java.util.stream.IntStream.range(0, bloques).mapToObj(indice -> {
                var desde = porDia ? inicio.plusDays(indice) : inicio.plusHours(indice * 4L);
                var hasta = porDia ? desde.plusDays(1) : desde.plusHours(4);
                return visitas.resumirPorteria(guarda.getId(), desde, hasta);
            }).toList();
        });
    }

    public record Inicio(ResumenPorteria totales, List<Llegada> llegadas, boolean consultaPersonas) { }

    public record Llegada(VisitaPrevista visita, HorarioIngreso horario) { }
}
