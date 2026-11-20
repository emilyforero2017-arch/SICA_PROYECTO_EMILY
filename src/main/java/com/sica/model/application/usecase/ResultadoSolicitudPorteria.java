package com.sica.model.application.usecase;

import com.sica.model.domain.entity.EstadoVisita;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.policy.HorarioIngreso;
import java.util.Objects;
import java.util.Optional;

// Respuesta actual de una solicitud: identidad, decisión y condiciones que permiten o impiden entrar.
public final class ResultadoSolicitudPorteria {

    private final Visita solicitud;
    private final Persona persona;
    private final EstadoVisita estado;
    private final String empresa;
    private final String funcionario;
    private final HorarioIngreso horario;
    private final boolean permiteIngreso;
    private final String mensaje;

    public ResultadoSolicitudPorteria(Visita solicitud, Persona persona, EstadoVisita estado,
            String empresa, String funcionario, HorarioIngreso horario, boolean permiteIngreso, String mensaje) {
        this.solicitud = Objects.requireNonNull(solicitud);
        this.persona = Objects.requireNonNull(persona);
        this.estado = Objects.requireNonNull(estado);
        this.empresa = Objects.requireNonNull(empresa);
        this.funcionario = Objects.requireNonNull(funcionario);
        this.horario = horario;
        this.permiteIngreso = permiteIngreso;
        this.mensaje = Objects.requireNonNull(mensaje);
    }

    public Visita getSolicitud() { return solicitud; }
    public Persona getPersona() { return persona; }
    public EstadoVisita getEstado() { return estado; }
    public String getEmpresa() { return empresa; }
    public String getFuncionario() { return funcionario; }
    // Una solicitud pendiente o rechazada no tiene un horario autorizado.
    public Optional<HorarioIngreso> getHorario() { return Optional.ofNullable(horario); }
    public boolean isPermiteIngreso() { return permiteIngreso; }
    public String getMensaje() { return mensaje; }
}
