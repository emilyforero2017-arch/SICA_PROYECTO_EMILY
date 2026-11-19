package com.sica.model.domain.repository;

import com.sica.model.domain.entity.BitacoraAuditoria;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BitacoraAuditoriaRepository {

    BitacoraAuditoria guardar(BitacoraAuditoria registro);

    Optional<BitacoraAuditoria> buscarPorId(Long id);

    List<BitacoraAuditoria> listarPorUsuario(Integer usuarioId);

    List<BitacoraAuditoria> listarPorRegistro(String tablaAfectada, Integer registroIdAfectado);

    // Incluye desde, sin incluir hasta.
    List<BitacoraAuditoria> listarPorPeriodo(LocalDateTime desde, LocalDateTime hasta);
}
