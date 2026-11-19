package com.sica.model.domain.repository;

import com.sica.model.domain.entity.EstadoVisita;
import java.util.List;
import java.util.Optional;

public interface EstadoVisitaRepository {

    Optional<EstadoVisita> buscarPorId(Integer id);

    Optional<EstadoVisita> buscarPorNombre(String nombreEstado);

    List<EstadoVisita> listar();
}
