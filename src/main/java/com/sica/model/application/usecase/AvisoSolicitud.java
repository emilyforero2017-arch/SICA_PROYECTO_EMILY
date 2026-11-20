package com.sica.model.application.usecase;

import java.time.LocalDateTime;
import java.util.Objects;

// Informa de una decisión confirmada; no sustituye la consulta de la autorización vigente.
public final class AvisoSolicitud {

    private final Long id;
    private final Integer guardaId;
    private final Integer solicitudId;
    private final LocalDateTime fecha;
    private final String resultado;
    private final String funcionario;
    private final String mensaje;

    public AvisoSolicitud(Long id, Integer guardaId, Integer solicitudId, LocalDateTime fecha, String resultado, String funcionario, String mensaje) {
        this.id = Objects.requireNonNull(id);
        this.guardaId = Objects.requireNonNull(guardaId);
        this.solicitudId = Objects.requireNonNull(solicitudId);
        this.fecha = Objects.requireNonNull(fecha);
        this.resultado = Objects.requireNonNull(resultado);
        this.funcionario = Objects.requireNonNull(funcionario);
        this.mensaje = Objects.requireNonNull(mensaje);
    }

    public Long getId() { return id; }
    public Integer getGuardaId() { return guardaId; }
    public Integer getSolicitudId() { return solicitudId; }
    public LocalDateTime getFecha() { return fecha; }
    public String getResultado() { return resultado; }
    public String getFuncionario() { return funcionario; }
    public String getMensaje() { return mensaje; }
}
