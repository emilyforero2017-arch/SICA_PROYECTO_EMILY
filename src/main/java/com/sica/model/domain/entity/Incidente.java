package com.sica.model.domain.entity;

import java.time.LocalDateTime;

public class Incidente {

    private Integer id;
    private Integer visitaId;
    private Integer registradoPorId;
    private LocalDateTime fechaIncidente;
    private String descripcion;

    public Incidente(
            Integer visitaId,
            Integer registradoPorId,
            LocalDateTime fechaIncidente,
            String descripcion) {

        this(null, visitaId, registradoPorId, fechaIncidente, descripcion);
    }

    public Incidente(
            Integer id,
            Integer visitaId,
            Integer registradoPorId,
            LocalDateTime fechaIncidente,
            String descripcion) {

        this.id = id;
        this.visitaId = validarVisitaId(visitaId);
        this.registradoPorId = validarRegistradoPorId(registradoPorId);
        this.fechaIncidente = validarFechaIncidente(fechaIncidente);
        this.descripcion = validarDescripcion(descripcion);
    }

    public Integer getId() {
        return id;
    }

    public Integer getVisitaId() {
        return visitaId;
    }

    public void setVisitaId(Integer visitaId) {
        this.visitaId = validarVisitaId(visitaId);
    }

    public Integer getRegistradoPorId() {
        return registradoPorId;
    }

    public void setRegistradoPorId(Integer registradoPorId) {
        this.registradoPorId = validarRegistradoPorId(registradoPorId);
    }

    public LocalDateTime getFechaIncidente() {
        return fechaIncidente;
    }

    public void setFechaIncidente(LocalDateTime fechaIncidente) {
        this.fechaIncidente = validarFechaIncidente(fechaIncidente);
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = validarDescripcion(descripcion);
    }

    private Integer validarVisitaId(Integer visitaId) {
        if (visitaId == null) {
            throw new IllegalArgumentException(
                    "La visita del incidente es obligatoria.");
        }

        return visitaId;
    }

    private Integer validarRegistradoPorId(Integer registradoPorId) {
        if (registradoPorId == null) {
            throw new IllegalArgumentException(
                    "El usuario que registra el incidente es obligatorio.");
        }

        return registradoPorId;
    }

    private LocalDateTime validarFechaIncidente(LocalDateTime fechaIncidente) {
        if (fechaIncidente == null) {
            throw new IllegalArgumentException(
                    "La fecha y hora del incidente son obligatorias.");
        }

        return fechaIncidente;
    }

    private String validarDescripcion(String descripcion) {
        if (descripcion == null || descripcion.isBlank()) {
            throw new IllegalArgumentException(
                    "La descripción del incidente es obligatoria.");
        }

        return descripcion;
    }
}
