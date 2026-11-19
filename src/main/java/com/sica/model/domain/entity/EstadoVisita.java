package com.sica.model.domain.entity;

public class EstadoVisita {

    private Integer id;
    private String nombreEstado;

    public EstadoVisita(String nombreEstado) {
        this(null, nombreEstado);
    }

    public EstadoVisita(Integer id, String nombreEstado) {
        this.id = id;
        this.nombreEstado = validarNombreEstado(nombreEstado);
    }

    public Integer getId() {
        return id;
    }

    public String getNombreEstado() {
        return nombreEstado;
    }

    public void setNombreEstado(String nombreEstado) {
        this.nombreEstado = validarNombreEstado(nombreEstado);
    }

    // El nombre del estado es obligatorio y admite hasta 50 caracteres.
    private String validarNombreEstado(String nombreEstado) {
        if (nombreEstado == null || nombreEstado.isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre del estado de la visita es obligatorio.");
        }

        if (nombreEstado.length() > 50) {
            throw new IllegalArgumentException(
                    "El nombre del estado de la visita admite hasta 50 caracteres.");
        }

        return nombreEstado;
    }
}
