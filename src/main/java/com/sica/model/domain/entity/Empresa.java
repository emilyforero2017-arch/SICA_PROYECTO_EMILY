package com.sica.model.domain.entity;

public class Empresa {
    private Integer id;
    private String nombre;
    private String contactoPrincipal;
    private boolean estaActiva;
    private final byte[] logo;

    public Empresa(String nombre, String contactoPrincipal) {
        this(null, nombre, contactoPrincipal);
    }

    public Empresa(
            Integer id,
            String nombre,
            String contactoPrincipal) {

        this(id, nombre, contactoPrincipal, true);
    }

    // Permite recuperar de la base de datos tanto empresas activas como desactivadas.
    public Empresa(
            Integer id,
            String nombre,
            String contactoPrincipal,
            boolean estaActiva) {
        this(id, nombre, contactoPrincipal, estaActiva, null);
    }

    public Empresa(Integer id, String nombre, String contactoPrincipal, boolean estaActiva, byte[] logo) {
        this.id = id;
        this.nombre = validarNombre(nombre);
        this.contactoPrincipal = validarContactoPrincipal(contactoPrincipal);
        this.estaActiva = estaActiva;
        if (logo != null && (logo.length == 0 || logo.length > 128 * 1024)) {
            throw new IllegalArgumentException("El logo no tiene un tamaño válido.");
        }
        this.logo = logo == null ? null : logo.clone();
    }

    public byte[] getLogo() { return logo == null ? null : logo.clone(); }

    public boolean tieneLogo() { return logo != null; }

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

    public boolean isEstaActiva() {
        return estaActiva;
    }

    // Retira la empresa del funcionamiento sin borrar su identificador ni sus datos.
    public void desactivar() {
        estaActiva = false;
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
