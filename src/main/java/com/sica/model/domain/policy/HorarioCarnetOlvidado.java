package com.sica.model.domain.policy;

import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Visita;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

// El pase incluye el minuto final de sus 30 minutos y termina como máximo al acabar el día de aprobación.
public class HorarioCarnetOlvidado implements PoliticaHorarioIngreso {

    @Override
    public HorarioIngreso calcular(Visita visita) {
        Objects.requireNonNull(visita, "La solicitud es obligatoria.");
        if (visita.getTipoIngreso() != TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO
                || visita.getFechaAprobacion() == null) {
            throw new IllegalArgumentException("Esta regla requiere un pase por carnet olvidado aprobado.");
        }
        LocalDateTime aprobacion = visita.getFechaAprobacion();
        LocalDateTime treintaMinutos = aprobacion.plusMinutes(30);
        LocalDateTime finDelDia = aprobacion.toLocalDate().atTime(LocalTime.MAX);
        // Si los 30 minutos alcanzan el día siguiente, el pase termina al acabar el día actual.
        return new HorarioIngreso(aprobacion, treintaMinutos.isBefore(finDelDia) ? treintaMinutos : finDelDia);
    }
}
