package com.sica.model.domain.entity;

public class Empresa {
    private Integer id;
    private String nombre;
    private String contactoPrincipal;

    public Empresa(String nombre, String contactoPrincipal) {
        this(null, nombre, contactoPrincipal);
    }

    public Empresa(
            Integer id,
            String nombre,
            String contactoPrincipal) {

        this.id = id;
        this.nombre = validarNombre(nombre);
        this.contactoPrincipal = validarContactoPrincipal(contactoPrincipal);
    }

    public Integer getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = validarNombre(nombre);
    }

    public String getContactoPrincipal() {
        return contactoPrincipal;
    }

    public void setContactoPrincipal(String contactoPrincipal) {
        this.contactoPrincipal = validarContactoPrincipal(contactoPrincipal);
    }

    // nombre no se encuentre vacio y no supere los 100 caracteres 
    private String validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre de la empresa es obligatorio.");
        }

        if (nombre.length() > 100) {
            throw new IllegalArgumentException(
                    "El nombre de la empresa admite hasta 100 caracteres.");
        }

        return nombre;
    }

    // el nombre del contacto no supere los 100 caracteres, si no hay contacto permite dejarlo vacio. 
    private String validarContactoPrincipal(String contactoPrincipal) {
        if (contactoPrincipal != null
                && contactoPrincipal.length() > 100) {

            throw new IllegalArgumentException(
                    "El contacto principal admite hasta 100 caracteres.");
        }

        return contactoPrincipal;
    }
}
