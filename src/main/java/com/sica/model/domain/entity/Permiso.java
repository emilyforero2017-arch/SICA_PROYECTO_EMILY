package com.sica.model.domain.entity;

public class Permiso {

    private Integer id;
    private String nombrePermiso;
    private String descripcion;

    public Permiso(String nombrePermiso, String descripcion) {
        this(null, nombrePermiso, descripcion);
    }

    public Permiso(
            Integer id,
            String nombrePermiso,
            String descripcion) {

        this.id = id;
        this.nombrePermiso = validarNombrePermiso(nombrePermiso);
        this.descripcion = descripcion;
    }

    public Integer getId() {
        return id;
    }

    public String getNombrePermiso() {
        return nombrePermiso;
    }

    public void setNombrePermiso(String nombrePermiso) {
        this.nombrePermiso = validarNombrePermiso(nombrePermiso);
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    // El nombre del permiso es obligatorio y admite hasta 100 caracteres.
    private String validarNombrePermiso(String nombrePermiso) {
        if (nombrePermiso == null || nombrePermiso.isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre del permiso es obligatorio.");
        }

        if (nombrePermiso.length() > 100) {
            throw new IllegalArgumentException(
                    "El nombre del permiso admite hasta 100 caracteres.");
        }

        return nombrePermiso;
    }
}
