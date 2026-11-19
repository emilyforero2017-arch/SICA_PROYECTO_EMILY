package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Permiso;
import java.util.List;
import java.util.Optional;

public interface PermisoRepository {

    Optional<Permiso> buscarPorId(Integer id);

    Optional<Permiso> buscarPorNombre(String nombrePermiso);

    List<Permiso> listar();

    List<Permiso> listarPorRol(Integer rolId);

    boolean tienePermiso(Integer rolId, String nombrePermiso);

    // Devuelve true si se agregó una asignación que no existía.
    boolean asignarARol(Integer rolId, Integer permisoId);

    // Devuelve true si se quitó una asignación que existía.
    boolean retirarDeRol(Integer rolId, Integer permisoId);
}
