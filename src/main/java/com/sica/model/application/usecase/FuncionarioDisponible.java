package com.sica.model.application.usecase;

import java.util.Objects;

// Datos necesarios para elegir al destinatario de una solicitud desde portería.
public final class FuncionarioDisponible {

    private final Integer id;
    private final String nombre;
    private final Integer empresaId;

    public FuncionarioDisponible(Integer id, String nombre, Integer empresaId) {
        this.id = Objects.requireNonNull(id);
        this.nombre = Objects.requireNonNull(nombre);
        this.empresaId = Objects.requireNonNull(empresaId);
    }

    public Integer getId() { return id; }
    public String getNombre() { return nombre; }
    public Integer getEmpresaId() { return empresaId; }
}
