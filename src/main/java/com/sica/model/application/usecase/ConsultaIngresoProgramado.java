package com.sica.model.application.usecase;

import com.sica.model.domain.entity.Persona;
import java.util.List;
import java.util.Objects;

// Una consulta devuelve identidad, foto y citas sin entrada, indicando si siguen vigentes o expiraron.
public final class ConsultaIngresoProgramado {

    private final Persona persona;
    private final String estadoAcceso;
    private final List<DetalleIngresoProgramado> visitas;

    public ConsultaIngresoProgramado(
            Persona persona, String estadoAcceso, List<DetalleIngresoProgramado> visitas) {
        this.persona = Objects.requireNonNull(persona);
        this.estadoAcceso = Objects.requireNonNull(estadoAcceso);
        this.visitas = List.copyOf(visitas);
    }

    public Persona getPersona() { return persona; }
    public String getEstadoAcceso() { return estadoAcceso; }
    public List<DetalleIngresoProgramado> getVisitas() { return visitas; }
}
