package com.sica.model.application.usecase;

import com.sica.model.domain.entity.Incidente;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Visita;
import java.util.Objects;

// Reúne el incidente y los datos necesarios para comprenderlo al consultar el historial.
public final class DetalleIncidente {

    private final Incidente incidente;
    private final Visita visita;
    private final Persona persona;
    private final String empresa;
    private final String supervisor;

    public DetalleIncidente(Incidente incidente, Visita visita, Persona persona, String empresa, String supervisor) {
        this.incidente = Objects.requireNonNull(incidente);
        this.visita = Objects.requireNonNull(visita);
        this.persona = Objects.requireNonNull(persona);
        this.empresa = Objects.requireNonNull(empresa);
        this.supervisor = Objects.requireNonNull(supervisor);
    }

    public Incidente getIncidente() { return incidente; }
    public Visita getVisita() { return visita; }
    public Persona getPersona() { return persona; }
    public String getEmpresa() { return empresa; }
    public String getSupervisor() { return supervisor; }
}
