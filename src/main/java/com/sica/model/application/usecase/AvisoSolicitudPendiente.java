package com.sica.model.application.usecase;

import com.sica.model.domain.entity.TipoIngreso;
import java.util.Objects;

// Informa al funcionario de una solicitud pendiente dirigida a él.
public final class AvisoSolicitudPendiente {

    private final Integer solicitudId;
    private final Integer funcionarioId;
    private final Integer personaId;
    private final String nombrePersona;
    private final TipoIngreso tipoIngreso;
    private final String mensaje;

    public AvisoSolicitudPendiente(Integer solicitudId, Integer funcionarioId, Integer personaId,
            String nombrePersona, TipoIngreso tipoIngreso, String mensaje) {
        this.solicitudId = Objects.requireNonNull(solicitudId);
        this.funcionarioId = Objects.requireNonNull(funcionarioId);
        this.personaId = Objects.requireNonNull(personaId);
        this.nombrePersona = Objects.requireNonNull(nombrePersona);
        this.tipoIngreso = Objects.requireNonNull(tipoIngreso);
        this.mensaje = Objects.requireNonNull(mensaje);
    }

    public Integer getSolicitudId() { return solicitudId; }
    public Integer getFuncionarioId() { return funcionarioId; }
    public Integer getPersonaId() { return personaId; }
    public String getNombrePersona() { return nombrePersona; }
    public TipoIngreso getTipoIngreso() { return tipoIngreso; }
    public String getMensaje() { return mensaje; }
}
