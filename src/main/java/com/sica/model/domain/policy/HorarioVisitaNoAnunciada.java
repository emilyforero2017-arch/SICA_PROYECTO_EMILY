package com.sica.model.domain.policy;

import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Visita;
import java.time.LocalDateTime;
import java.util.Objects;

// La visita sin agenda permite entrar desde su aprobación, con 30 minutos y el minuto final incluido.
public class HorarioVisitaNoAnunciada implements PoliticaHorarioIngreso {

    @Override
    public HorarioIngreso calcular(Visita visita) {
        Objects.requireNonNull(visita, "La visita es obligatoria.");
        if (visita.getTipoIngreso() != TipoIngreso.INVITADO_NO_ANUNCIADO
                || visita.getFechaAprobacion() == null) {
            throw new IllegalArgumentException(
                    "Esta regla requiere una visita no anunciada con fecha de aprobación.");
        }

        // El tiempo empieza cuando el funcionario aprueba, no cuando llega el invitado.
        LocalDateTime aprobacion = visita.getFechaAprobacion();
        return new HorarioIngreso(aprobacion, aprobacion.plusMinutes(30));
    }
}
