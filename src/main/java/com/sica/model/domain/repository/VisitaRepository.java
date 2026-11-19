package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Visita;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VisitaRepository {

    Visita guardar(Visita visita);

    Optional<Visita> buscarPorId(Integer id);

    List<Visita> listarPorPersona(Integer personaId);

    List<Visita> listarPorFuncionarioDestinatario(Integer funcionarioId);

    List<Visita> listarPorEstado(Integer estadoVisitaId);

    // Incluye entradas desde esa fecha, sin incluir la fecha hasta.
    List<Visita> listarPorFechaEntrada(LocalDateTime desde, LocalDateTime hasta);

    // Un ingreso abierto tiene entrada, pero no salida ni cierre del sistema.
    Optional<Visita> buscarIngresoAbiertoPorPersona(Integer personaId);

    List<Visita> listarIngresosAbiertos();

    // Incluye entradas de la fecha límite o anteriores que siguen abiertas.
    List<Visita> listarIngresosAbiertosHasta(LocalDateTime fechaLimite);

    boolean actualizar(Visita visita);
}
