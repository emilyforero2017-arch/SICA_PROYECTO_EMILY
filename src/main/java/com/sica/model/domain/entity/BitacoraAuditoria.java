package com.sica.model.domain.entity;

import java.time.LocalDateTime;

public final class BitacoraAuditoria {

    private final Long id;
    private final Integer usuarioId;
    private final LocalDateTime fechaHora;
    private final String accionRealizada;
    private final String tablaAfectada;
    private final Integer registroIdAfectado;
    private final String detalles;

    public BitacoraAuditoria(
            Integer usuarioId,
            LocalDateTime fechaHora,
            String accionRealizada,
            String tablaAfectada,
            Integer registroIdAfectado,
            String detalles) {

        this(null, usuarioId, fechaHora, accionRealizada,
                tablaAfectada, registroIdAfectado, detalles);
    }

    public BitacoraAuditoria(
            Long id,
            Integer usuarioId,
            LocalDateTime fechaHora,
            String accionRealizada,
            String tablaAfectada,
            Integer registroIdAfectado,
            String detalles) {

        this.id = id;
        this.usuarioId = usuarioId;
        this.fechaHora = validarFechaHora(fechaHora);
        this.accionRealizada = validarAccionRealizada(accionRealizada);
        this.tablaAfectada = validarTablaAfectada(tablaAfectada);
        this.registroIdAfectado = registroIdAfectado;
        this.detalles = validarDetalles(detalles);
    }

    public Long getId() {
        return id;
    }

    public Integer getUsuarioId() {
        return usuarioId;
    }

    public LocalDateTime getFechaHora() {
        return fechaHora;
    }

    public String getAccionRealizada() {
        return accionRealizada;
    }

    public String getTablaAfectada() {
        return tablaAfectada;
    }

    public Integer getRegistroIdAfectado() {
        return registroIdAfectado;
    }

    public String getDetalles() {
        return detalles;
    }

    private static LocalDateTime validarFechaHora(LocalDateTime fechaHora) {
        if (fechaHora == null) {
            throw new IllegalArgumentException(
                    "La fecha y hora del registro de auditoría son obligatorias.");
        }

        return fechaHora;
    }

    private static String validarAccionRealizada(String accionRealizada) {
        if (accionRealizada == null || accionRealizada.isBlank()) {
            throw new IllegalArgumentException(
                    "La acción realizada es obligatoria.");
        }

        if (accionRealizada.length() > 255) {
            throw new IllegalArgumentException(
                    "La acción realizada admite hasta 255 caracteres.");
        }

        return accionRealizada;
    }

    private static String validarTablaAfectada(String tablaAfectada) {
        if (tablaAfectada != null && tablaAfectada.length() > 100) {
            throw new IllegalArgumentException(
                    "El nombre de la tabla afectada admite hasta 100 caracteres.");
        }

        return tablaAfectada;
    }

    private static String validarDetalles(String detalles) {
        if (detalles == null || detalles.isBlank()) {
            throw new IllegalArgumentException(
                    "Los detalles del registro de auditoría son obligatorios.");
        }

        return detalles;
    }
}
