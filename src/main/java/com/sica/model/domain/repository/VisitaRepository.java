package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Visita;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VisitaRepository {

    Visita guardar(Visita visita);

    Optional<Visita> buscarPorId(Integer id);

    // Permite comprobar y registrar el ingreso sin que otra operación cambie la visita a la vez.
    Optional<Visita> buscarPorIdParaActualizar(Integer id);

    List<Visita> listarPorPersona(Integer personaId);

    List<SolicitudEnPorteria> listarSolicitudesEnviadas(Integer guardaId);

    List<Visita> listarPorFuncionarioDestinatario(Integer funcionarioId);

    List<Visita> listarPorEstado(Integer estadoVisitaId);

    // Incluye entradas desde esa fecha, sin incluir la fecha hasta.
    List<Visita> listarPorFechaEntrada(LocalDateTime desde, LocalDateTime hasta);

    // Devuelve cada visita una sola vez si su entrada, salida real o cierre ocurrió en el período.
    // Incluye desde y excluye hasta, aunque la entrada haya ocurrido antes del período consultado.
    List<Visita> listarPorMovimientosEnPeriodo(LocalDateTime desde, LocalDateTime hasta);

    // Un ingreso abierto tiene entrada, pero no salida ni cierre del sistema.
    Optional<Visita> buscarIngresoAbiertoPorPersona(Integer personaId);

    List<VisitaPrevista> listarVisitasPrevistas(LocalDateTime desde, LocalDateTime hasta);

    List<MovimientoPorteria> listarMovimientosPorteria(Integer guardaId, LocalDateTime desde,
            LocalDateTime hasta, MovimientoPorteria.Tipo tipo);

    // Los movimientos son del período. Las solicitudes siguen contando mientras esperen respuesta.
    ResumenPorteria resumirPorteria(Integer guardaId, LocalDateTime desde, LocalDateTime hasta);

    // Lee el ingreso abierto más reciente mientras la persona está protegida para actualizar.
    Optional<Visita> buscarIngresoAbiertoPorPersonaParaActualizar(Integer personaId);

    List<Visita> listarIngresosAbiertos();

    // Incluye entradas de la fecha límite o anteriores que siguen abiertas.
    List<Visita> listarIngresosAbiertosHasta(LocalDateTime fechaLimite);

    boolean actualizar(Visita visita);
}
