package com.sica.model.domain.policy;

import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Visita;
import java.time.LocalDateTime;
import java.util.Objects;

// La visita permite entrar una hora antes y una después de la cita, incluyendo todo el minuto final.
public class HorarioVisitaProgramada implements PoliticaHorarioIngreso {

    @Override
    public HorarioIngreso calcular(Visita visita) {
        Objects.requireNonNull(visita, "La visita es obligatoria.");
        if (visita.getTipoIngreso() != TipoIngreso.INVITADO_PREREGISTRADO
                || visita.getFechaProgramada() == null) {
            throw new IllegalArgumentException("Esta regla requiere una visita preregistrada con fecha.");
        }

        return calcular(visita.getFechaProgramada());
    }

    public HorarioIngreso calcular(LocalDateTime cita) {
        Objects.requireNonNull(cita, "La fecha de la cita es obligatoria.");
        return new HorarioIngreso(cita.minusHours(1), cita.plusHours(1));
    }
}
