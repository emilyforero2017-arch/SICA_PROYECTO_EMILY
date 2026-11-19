package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Empresa;
import java.util.List;
import java.util.Optional;

public interface EmpresaRepository {

    // Registra una empresa nueva y devuelve sus datos con el identificador asignado.
    Empresa guardar(Empresa empresa);

    Optional<Empresa> buscarPorId(Integer id);

    List<Empresa> listar();

    // Devuelve true si la empresa existía y se pudo actualizar.
    boolean actualizar(Empresa empresa);
}
