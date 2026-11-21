package com.sica.infrastructure.persistence;

import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.repository.VisitaRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class VisitaRepositoryJdbc implements VisitaRepository {

    private static final String CONSULTA_VISITAS = """
            SELECT id, persona_id, empresa_destino_id, funcionario_destinatario_id,
                   tipo_ingreso, fecha_programada, fecha_aprobacion, fecha_entrada,
                   fecha_salida, fecha_cierre_sistema, estado_visita_id,
                   vehiculo_placa, aprobada_por_id
            FROM visitas
            """;

    private static final String INGRESO_ABIERTO =
            "fecha_entrada IS NOT NULL AND fecha_salida IS NULL AND fecha_cierre_sistema IS NULL";

    private final Connection conexion;

    public VisitaRepositoryJdbc(Connection conexion) {
        // Comparte la conexión de la operación con las personas y su auditoría.
        this.conexion = Objects.requireNonNull(
                conexion, "La conexión de la base de datos es obligatoria.");
    }

    @Override
    public Visita guardar(Visita visita) {
        Objects.requireNonNull(visita, "Los datos de la visita son obligatorios.");

        String sql = """
                INSERT INTO visitas
                    (persona_id, empresa_destino_id, funcionario_destinatario_id,
                     tipo_ingreso, fecha_programada, fecha_aprobacion, fecha_entrada,
                     fecha_salida, fecha_cierre_sistema, estado_visita_id,
                     vehiculo_placa, aprobada_por_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        int id;
        try (PreparedStatement consulta = conexion.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            completarDatos(consulta, visita);

            if (consulta.executeUpdate() != 1) {
                throw new SQLException("No se guardó la visita esperada.");
            }
            try (ResultSet identificadores = consulta.getGeneratedKeys()) {
                if (!identificadores.next()) {
                    throw new SQLException("MySQL no devolvió el identificador de la visita.");
                }
                id = identificadores.getInt(1);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo guardar la visita.", error);
        }

        return buscarPorId(id).orElseThrow(() -> new IllegalStateException(
                "No se pudo recuperar la visita recién guardada."));
    }

    @Override
    public Optional<Visita> buscarPorId(Integer id) {
        Objects.requireNonNull(id, "El identificador de la visita es obligatorio.");
        return buscar(CONSULTA_VISITAS + " WHERE id = ?", false, id);
    }

    @Override
    public Optional<Visita> buscarPorIdParaActualizar(Integer id) {
        Objects.requireNonNull(id, "El identificador de la visita es obligatorio.");
        return buscar(CONSULTA_VISITAS + " WHERE id = ? FOR UPDATE", true, id);
    }

    private Optional<Visita> buscar(String sql, boolean proteger, Object... valores) {
        try {
            if (proteger && conexion.getAutoCommit()) {
                throw new IllegalStateException(
                        "La consulta protegida requiere una transacción abierta.");
            }

            try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
                completarParametros(consulta, valores);

                try (ResultSet resultado = consulta.executeQuery()) {
                    return resultado.next()
                            ? Optional.of(leerVisita(resultado))
                            : Optional.empty();
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar la visita.", error);
        }
    }

    @Override
    public List<Visita> listarPorPersona(Integer personaId) {
        Objects.requireNonNull(personaId, "El identificador de la persona es obligatorio.");
        return consultarLista(CONSULTA_VISITAS + " WHERE persona_id = ? ORDER BY id DESC", personaId);
    }

    @Override
    public List<Visita> listarPorFuncionarioDestinatario(Integer funcionarioId) {
        Objects.requireNonNull(funcionarioId, "El funcionario destinatario es obligatorio.");
        return consultarLista(CONSULTA_VISITAS
                + " WHERE funcionario_destinatario_id = ? ORDER BY id DESC", funcionarioId);
    }

    @Override
    public List<Visita> listarPorEstado(Integer estadoVisitaId) {
        Objects.requireNonNull(estadoVisitaId, "El estado de la visita es obligatorio.");
        return consultarLista(CONSULTA_VISITAS
                + " WHERE estado_visita_id = ? ORDER BY id DESC", estadoVisitaId);
    }

    @Override
    public List<Visita> listarPorFechaEntrada(LocalDateTime desde, LocalDateTime hasta) {
        validarPeriodo(desde, hasta);
        String sql = CONSULTA_VISITAS
                + " WHERE fecha_entrada >= ? AND fecha_entrada < ?"
                + " ORDER BY fecha_entrada DESC, id DESC";
        return consultarLista(sql, elevarAlSegundo(desde), elevarAlSegundo(hasta));
    }

    @Override
    public List<Visita> listarPorMovimientosEnPeriodo(LocalDateTime desde, LocalDateTime hasta) {
        validarPeriodo(desde, hasta);
        LocalDateTime inicio = elevarAlSegundo(desde);
        LocalDateTime fin = elevarAlSegundo(hasta);

        // Una visita aparece una sola vez aunque tenga varios movimientos dentro del período.
        String sql = CONSULTA_VISITAS + """
                 WHERE (fecha_entrada >= ? AND fecha_entrada < ?)
                    OR (fecha_salida >= ? AND fecha_salida < ?)
                    OR (fecha_cierre_sistema >= ? AND fecha_cierre_sistema < ?)
                 ORDER BY id DESC
                """;
        return consultarLista(sql, inicio, fin, inicio, fin, inicio, fin);
    }

    @Override
    public Optional<Visita> buscarIngresoAbiertoPorPersona(Integer personaId) {
        return buscarIngresoAbierto(personaId, false);
    }

    @Override
    public Optional<Visita> buscarIngresoAbiertoPorPersonaParaActualizar(Integer personaId) {
        // El caso de uso protege primero a la persona para coordinar sus ingresos.
        return buscarIngresoAbierto(personaId, true);
    }

    private Optional<Visita> buscarIngresoAbierto(Integer personaId, boolean proteger) {
        Objects.requireNonNull(personaId, "El identificador de la persona es obligatorio.");
        String sql = CONSULTA_VISITAS + " WHERE persona_id = ? AND " + INGRESO_ABIERTO
                + " ORDER BY fecha_entrada DESC, id DESC LIMIT 1";
        if (proteger) {
            sql += " FOR UPDATE";
        }
        return buscar(sql, proteger, personaId);
    }

    @Override
    public List<Visita> listarIngresosAbiertos() {
        return consultarLista(CONSULTA_VISITAS + " WHERE " + INGRESO_ABIERTO
                + " ORDER BY fecha_entrada, id");
    }

    @Override
    public List<Visita> listarIngresosAbiertosHasta(LocalDateTime fechaLimite) {
        Objects.requireNonNull(fechaLimite, "La fecha límite es obligatoria.");
        String sql = CONSULTA_VISITAS + " WHERE " + INGRESO_ABIERTO
                + " AND fecha_entrada <= ? ORDER BY fecha_entrada, id";
        return consultarLista(sql, sinFracciones(fechaLimite));
    }

    private List<Visita> consultarLista(String sql, Object... valores) {
        List<Visita> visitas = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            completarParametros(consulta, valores);

            try (ResultSet resultado = consulta.executeQuery()) {
                while (resultado.next()) {
                    visitas.add(leerVisita(resultado));
                }
            }
            return visitas;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron listar las visitas.", error);
        }
    }

    @Override
    public boolean actualizar(Visita visita) {
        Objects.requireNonNull(visita, "Los datos de la visita son obligatorios.");
        Objects.requireNonNull(visita.getId(), "El identificador de la visita es obligatorio.");

        // El caso de uso determina el estado y las fechas que corresponden al cambio.
        String sql = """
                UPDATE visitas
                SET persona_id = ?, empresa_destino_id = ?, funcionario_destinatario_id = ?,
                    tipo_ingreso = ?, fecha_programada = ?, fecha_aprobacion = ?, fecha_entrada = ?,
                    fecha_salida = ?, fecha_cierre_sistema = ?, estado_visita_id = ?,
                    vehiculo_placa = ?, aprobada_por_id = ?
                WHERE id = ?
                """;

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            completarDatos(consulta, visita);
            consulta.setInt(13, visita.getId());
            return consulta.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo actualizar la visita.", error);
        }
    }

    private void completarDatos(PreparedStatement consulta, Visita visita) throws SQLException {
        consulta.setInt(1, visita.getPersonaId());
        consulta.setInt(2, visita.getEmpresaDestinoId());
        consulta.setObject(3, visita.getFuncionarioDestinatarioId(), Types.INTEGER);
        consulta.setString(4, switch (visita.getTipoIngreso()) {
            case INVITADO_PREREGISTRADO -> "Invitado preregistrado";
            case INVITADO_NO_ANUNCIADO -> "Invitado no anunciado";
            case TRABAJADOR_CON_CARNET -> "Trabajador con carnet";
            case TRABAJADOR_CON_CARNET_OLVIDADO -> "Trabajador con carnet olvidado";
        });
        consulta.setObject(5, sinFracciones(visita.getFechaProgramada()), Types.TIMESTAMP);
        consulta.setObject(6, sinFracciones(visita.getFechaAprobacion()), Types.TIMESTAMP);
        consulta.setObject(7, sinFracciones(visita.getFechaEntrada()), Types.TIMESTAMP);
        consulta.setObject(8, sinFracciones(visita.getFechaSalida()), Types.TIMESTAMP);
        consulta.setObject(9, sinFracciones(visita.getFechaCierreSistema()), Types.TIMESTAMP);
        consulta.setInt(10, visita.getEstadoVisitaId());
        consulta.setString(11, visita.getVehiculoPlaca());
        consulta.setObject(12, visita.getAprobadaPorId(), Types.INTEGER);
    }

    private void completarParametros(PreparedStatement consulta, Object[] valores) throws SQLException {
        for (int i = 0; i < valores.length; i++) {
            consulta.setObject(i + 1, valores[i]);
        }
    }

    private Visita leerVisita(ResultSet resultado) throws SQLException {
        TipoIngreso tipo = switch (resultado.getString("tipo_ingreso")) {
            case "Invitado preregistrado" -> TipoIngreso.INVITADO_PREREGISTRADO;
            case "Invitado no anunciado" -> TipoIngreso.INVITADO_NO_ANUNCIADO;
            case "Trabajador con carnet" -> TipoIngreso.TRABAJADOR_CON_CARNET;
            case "Trabajador con carnet olvidado" -> TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO;
            default -> throw new SQLException("El tipo de ingreso guardado no es reconocido.");
        };

        return new Visita(
                resultado.getInt("id"), resultado.getInt("persona_id"),
                resultado.getInt("empresa_destino_id"),
                resultado.getObject("funcionario_destinatario_id", Integer.class), tipo,
                resultado.getObject("fecha_programada", LocalDateTime.class),
                resultado.getObject("fecha_aprobacion", LocalDateTime.class),
                resultado.getObject("fecha_entrada", LocalDateTime.class),
                resultado.getObject("fecha_salida", LocalDateTime.class),
                resultado.getObject("fecha_cierre_sistema", LocalDateTime.class),
                resultado.getInt("estado_visita_id"), resultado.getString("vehiculo_placa"),
                resultado.getObject("aprobada_por_id", Integer.class));
    }

    private void validarPeriodo(LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null || !hasta.isAfter(desde)) {
            throw new IllegalArgumentException(
                    "Debes indicar un período con fecha final posterior a la inicial.");
        }
    }

    private LocalDateTime sinFracciones(LocalDateTime fecha) {
        // El esquema guarda segundos: se descartan fracciones para evitar redondear al minuto siguiente.
        return fecha == null ? null : fecha.truncatedTo(ChronoUnit.SECONDS);
    }

    private LocalDateTime elevarAlSegundo(LocalDateTime fecha) {
        // Conserva los límites del período al comparar con fechas guardadas sin fracciones.
        return fecha.getNano() == 0 ? fecha : sinFracciones(fecha).plusSeconds(1);
    }
}
