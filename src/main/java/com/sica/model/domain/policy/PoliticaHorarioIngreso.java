package com.sica.model.domain.policy;

import com.sica.model.domain.entity.Visita;

// Cada tipo de autorización tendrá su propia regla para calcular el horario de entrada.
public interface PoliticaHorarioIngreso {

    HorarioIngreso calcular(Visita visita);
}
