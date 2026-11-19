package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Rol;
import java.util.List;
import java.util.Optional;

public interface RolRepository {

    Optional<Rol> buscarPorId(Integer id);

    Optional<Rol> buscarPorNombre(String nombreRol);

    List<Rol> listar();
}
