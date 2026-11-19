package com.sica.model.domain.repository;

import com.sica.model.domain.entity.EstadoAccesoPersona;
import java.util.List;
import java.util.Optional;

public interface EstadoAccesoPersonaRepository {

    Optional<EstadoAccesoPersona> buscarPorId(Integer id);

    Optional<EstadoAccesoPersona> buscarPorNombre(String nombreEstado);

    List<EstadoAccesoPersona> listar();
}
