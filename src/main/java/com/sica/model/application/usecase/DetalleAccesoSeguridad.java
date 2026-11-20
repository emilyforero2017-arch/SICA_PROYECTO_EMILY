package com.sica.model.application.usecase;

import com.sica.model.domain.entity.EstadoVisita;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Visita;
import java.util.Objects;
import java.util.Optional;

// Reúne la identidad y el destino del acceso para las consultas de seguridad.
public final class DetalleAccesoSeguridad {

    private final Visita visita;
    private final Persona persona;
    private final EstadoVisita estado;
    private final String empresa;
    private final String funcionario;

    public DetalleAccesoSeguridad(Visita visita, Persona persona, EstadoVisita estado,
            String empresa, String funcionario) {
        this.visita = Objects.requireNonNull(visita);
        this.persona = Objects.requireNonNull(persona);
        this.estado = Objects.requireNonNull(estado);
        this.empresa = Objects.requireNonNull(empresa);
        this.funcionario = funcionario;
    }

    public Visita getVisita() { return visita; }
    public Persona getPersona() { return persona; }
    public EstadoVisita getEstado() { return estado; }
    public String getEmpresa() { return empresa; }

    // El ingreso habitual con carnet no tiene funcionario destinatario.
    public Optional<String> getFuncionario() { return Optional.ofNullable(funcionario); }
}
