package com.sica.model.application.usecase;

import com.sica.model.domain.entity.EstadoVisita;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Visita;
import java.util.Objects;

// Reúne la solicitud y la identidad de la persona para que su destinatario pueda revisarla.
public final class DetalleSolicitudIngreso {

    private final Visita solicitud;
    private final Persona persona;
    private final EstadoVisita estado;

    public DetalleSolicitudIngreso(Visita solicitud, Persona persona, EstadoVisita estado) {
        this.solicitud = Objects.requireNonNull(solicitud);
        this.persona = Objects.requireNonNull(persona);
        this.estado = Objects.requireNonNull(estado);
    }

    public Visita getSolicitud() { return solicitud; }
    public Persona getPersona() { return persona; }
    public EstadoVisita getEstado() { return estado; }
}
