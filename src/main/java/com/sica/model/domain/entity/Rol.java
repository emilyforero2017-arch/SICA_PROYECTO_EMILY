package com.sica.model.domain.entity;

public class Rol {

    private Integer id;
    private String nombreRol;

    public Rol(String nombreRol) {
        this(null, nombreRol);
    }

    public Rol(Integer id, String nombreRol) {
        this.id = id;
        this.nombreRol = validarNombreRol(nombreRol);
    }

    public Integer getId() {
        return id;
    }

    public String getNombreRol() {
        return nombreRol;
    }

    public void setNombreRol(String nombreRol) {
        this.nombreRol = validarNombreRol(nombreRol);
    }

    // El nombre del rol es obligatorio y admite hasta 50 caracteres.
    private String validarNombreRol(String nombreRol) {
        if (nombreRol == null || nombreRol.isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre del rol es obligatorio.");
        }

        if (nombreRol.length() > 50) {
            throw new IllegalArgumentException(
                    "El nombre del rol admite hasta 50 caracteres.");
        }

        return nombreRol;
    }
}
