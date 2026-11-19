package com.sica.model.domain.entity;

public class Usuario {

    private Integer id;
    private String nombre;
    private String email;
    private String passwordHash;
    private Integer rolId;
    private Integer empresaId;
    private boolean estaActivo;

    public Usuario(
            String nombre,
            String email,
            String passwordHash,
            Integer rolId,
            Integer empresaId) {

        this(null, nombre, email, passwordHash, rolId, empresaId, true);
    }

    public Usuario(
            Integer id,
            String nombre,
            String email,
            String passwordHash,
            Integer rolId,
            Integer empresaId,
            boolean estaActivo) {

        this.id = id;
        this.nombre = validarNombre(nombre);
        this.email = validarEmail(email);
        this.passwordHash = validarPasswordHash(passwordHash);
        this.rolId = validarRolId(rolId);
        this.empresaId = empresaId;
        this.estaActivo = estaActivo;
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = validarEmail(email);
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = validarPasswordHash(passwordHash);
    }

    public Integer getRolId() {
        return rolId;
    }

    public void setRolId(Integer rolId) {
        this.rolId = validarRolId(rolId);
    }

    public Integer getEmpresaId() {
        return empresaId;
    }

    public void setEmpresaId(Integer empresaId) {
        this.empresaId = empresaId;
    }

    public boolean isEstaActivo() {
        return estaActivo;
    }

    public void setEstaActivo(boolean estaActivo) {
        this.estaActivo = estaActivo;
    }

    // El nombre es obligatorio y admite hasta 100 caracteres.
    private String validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre del usuario es obligatorio.");
        }

        if (nombre.length() > 100) {
            throw new IllegalArgumentException(
                    "El nombre del usuario admite hasta 100 caracteres.");
        }

        return nombre;
    }

    // El correo es obligatorio y admite hasta 100 caracteres.
    private String validarEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException(
                    "El correo del usuario es obligatorio.");
        }

        if (email.length() > 100) {
            throw new IllegalArgumentException(
                    "El correo del usuario admite hasta 100 caracteres.");
        }

        return email;
    }

    // Se recibe el hash ya calculado; su generación se implementará aparte.
    private String validarPasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) { // blank utiliza para saber si el texto esta vacio o contiene espacios en blanco. 
            throw new IllegalArgumentException(
                    "El hash de la contraseña es obligatorio.");
        }

        if (passwordHash.length() > 255) {
            throw new IllegalArgumentException(
                    "El hash de la contraseña admite hasta 255 caracteres.");
        }

        return passwordHash;
    }

    // La existencia del rol se comprobará mediante los repositorios y MySQL.
    private Integer validarRolId(Integer rolId) {
        if (rolId == null) {
            throw new IllegalArgumentException(
                    "El rol del usuario es obligatorio.");
        }

        return rolId;
    }
}
