package com.sica.model.application.usecase;

import com.sica.model.domain.entity.Rol;
import com.sica.model.domain.entity.Usuario;
import java.time.LocalDateTime;
import java.util.Objects;

// Identifica a quien inició sesión en esta aplicación, sin conservar su contraseña ni su hash.
public final class SesionUsuario {

    private final Integer usuarioId;
    private final String nombre;
    private final String email;
    private final Integer rolId;
    private final String nombreRol;
    private final Integer empresaId;
    private final LocalDateTime fechaInicio;

    SesionUsuario(Usuario usuario, Rol rol, LocalDateTime fechaInicio) {
        Objects.requireNonNull(usuario);
        Objects.requireNonNull(rol);
        this.usuarioId = Objects.requireNonNull(usuario.getId());
        this.nombre = usuario.getNombre();
        this.email = usuario.getEmail();
        this.rolId = rol.getId();
        this.nombreRol = rol.getNombreRol();
        this.empresaId = usuario.getEmpresaId();
        this.fechaInicio = Objects.requireNonNull(fechaInicio);
    }

    public Integer getUsuarioId() { return usuarioId; }
    public String getNombre() { return nombre; }
    public String getEmail() { return email; }
    public Integer getRolId() { return rolId; }
    public String getNombreRol() { return nombreRol; }
    public Integer getEmpresaId() { return empresaId; }
    public LocalDateTime getFechaInicio() { return fechaInicio; }
}
