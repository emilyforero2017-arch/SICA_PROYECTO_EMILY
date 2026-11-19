package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Incidente;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IncidenteRepository {

    Incidente guardar(Incidente incidente);

    Optional<Incidente> buscarPorId(Integer id);

    List<Incidente> listarPorVisita(Integer visitaId);

    List<Incidente> listarPorRegistradoPor(Integer usuarioId);

    // Incluye desde, sin incluir hasta.
    List<Incidente> listarPorPeriodo(LocalDateTime desde, LocalDateTime hasta);
}
