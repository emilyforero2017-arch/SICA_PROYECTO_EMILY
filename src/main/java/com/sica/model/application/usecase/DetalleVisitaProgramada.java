package com.sica.model.application.usecase;

import com.sica.model.domain.entity.EstadoVisita;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.policy.HorarioIngreso;
import java.util.Objects;

// Reúne la cita y el nombre de la persona para que el funcionario encuentre su visita.
public final class DetalleVisitaProgramada {

    private final Visita visita;
    private final Persona persona;
    private final EstadoVisita estado;
    private final HorarioIngreso horario;

    public DetalleVisitaProgramada(Visita visita, Persona persona, EstadoVisita estado, HorarioIngreso horario) {
        this.visita = Objects.requireNonNull(visita);
        this.persona = Objects.requireNonNull(persona);
        this.estado = Objects.requireNonNull(estado);
        this.horario = Objects.requireNonNull(horario);
    }

    public Visita getVisita() { return visita; }
    public Persona getPersona() { return persona; }
    public EstadoVisita getEstado() { return estado; }
    public HorarioIngreso getHorario() { return horario; }
}
