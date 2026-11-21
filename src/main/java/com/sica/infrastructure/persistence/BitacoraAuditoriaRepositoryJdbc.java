package com.sica.infrastructure.persistence;

import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.repository.BitacoraAuditoriaRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class BitacoraAuditoriaRepositoryJdbc implements BitacoraAuditoriaRepository {

    private static final String CONSULTA_AUDITORIA = """
            SELECT id, usuario_id, fecha_hora, accion_realizada,
                   tabla_afectada, registro_id_afectado, detalles
            FROM bitacora_auditoria
            """;

    private static final String ORDEN_RECIENTE = " ORDER BY fecha_hora DESC, id DESC";

    private final Connection conexion;

    public BitacoraAuditoriaRepositoryJdbc(Connection conexion) {
        // Usa la misma conexión que el cambio del que se dejará constancia.
        this.conexion = Objects.requireNonNull(
                conexion, "La conexión de la base de datos es obligatoria.");
    }

    @Override
    public BitacoraAuditoria guardar(BitacoraAuditoria registro) {
        Objects.requireNonNull(registro, "El registro de auditoría es obligatorio.");

        String sql = """
                INSERT INTO bitacora_auditoria
                    (usuario_id, fecha_hora, accion_realizada,
                     tabla_afectada, registro_id_afectado, detalles)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        long id;
        try (PreparedStatement consulta = conexion.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            consulta.setObject(1, registro.getUsuarioId(), Types.INTEGER);
            consulta.setObject(2, registro.getFechaHora(), Types.TIMESTAMP);
            consulta.setString(3, registro.getAccionRealizada());
            consulta.setString(4, registro.getTablaAfectada());
            consulta.setObject(5, registro.getRegistroIdAfectado(), Types.INTEGER);
            consulta.setString(6, registro.getDetalles());

            if (consulta.executeUpdate() != 1) {
                throw new SQLException("No se guardó el registro de auditoría esperado.");
            }

            try (ResultSet identificadores = consulta.getGeneratedKeys()) {
                if (!identificadores.next()) {
                    throw new SQLException(
                            "MySQL no devolvió el identificador de la auditoría.");
                }
                id = identificadores.getLong(1);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo guardar la auditoría.", error);
        }

        // Devuelve también la fecha con la precisión que realmente conserva MySQL.
        return buscarPorId(id).orElseThrow(() -> new IllegalStateException(
                "No se pudo recuperar el registro de auditoría recién guardado."));
    }

    @Override
    public Optional<BitacoraAuditoria> buscarPorId(Long id) {
        Objects.requireNonNull(id, "El identificador de auditoría es obligatorio.");

        String sql = CONSULTA_AUDITORIA + " WHERE id = ?";
        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setLong(1, id);

            try (ResultSet resultado = consulta.executeQuery()) {
                return resultado.next()
                        ? Optional.of(leerRegistro(resultado))
                        : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar la auditoría.", error);
        }
    }

    @Override
    public List<BitacoraAuditoria> listarPorUsuario(Integer usuarioId) {
        // También permite consultar registros sin usuario asociado cuando se recibe null.
        String sql = CONSULTA_AUDITORIA + " WHERE usuario_id <=> ?" + ORDEN_RECIENTE;
        return consultarLista(sql, usuarioId);
    }

    @Override
    public List<BitacoraAuditoria> listarPorRegistro(String tablaAfectada, Integer registroIdAfectado) {
        // La tabla y el identificador deben coincidir; admite referencias opcionales.
        String sql = CONSULTA_AUDITORIA
                + " WHERE tabla_afectada <=> ? AND registro_id_afectado <=> ?"
                + ORDEN_RECIENTE;
        return consultarLista(sql, tablaAfectada, registroIdAfectado);
    }

    @Override
    public List<BitacoraAuditoria> listarPorPeriodo(LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null || !hasta.isAfter(desde)) {
            throw new IllegalArgumentException(
                    "Debes indicar un período con fecha final posterior a la inicial.");
        }

        // Incluye el inicio y excluye el final para no repetir eventos entre períodos seguidos.
        String sql = CONSULTA_AUDITORIA + " WHERE fecha_hora >= ? AND fecha_hora < ?"
                + ORDEN_RECIENTE;
        return consultarLista(sql, elevarAlSegundo(desde), elevarAlSegundo(hasta));
    }

    private List<BitacoraAuditoria> consultarLista(String sql, Object... valores) {
        List<BitacoraAuditoria> registros = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            for (int i = 0; i < valores.length; i++) {
                consulta.setObject(i + 1, valores[i]);
            }

            try (ResultSet resultado = consulta.executeQuery()) {
                while (resultado.next()) {
                    registros.add(leerRegistro(resultado));
                }
            }
            return registros;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo listar la auditoría.", error);
        }
    }

    private LocalDateTime elevarAlSegundo(LocalDateTime fecha) {
        // El esquema guarda segundos completos: un límite intermedio pasa al segundo siguiente.
        return fecha.getNano() == 0 ? fecha : fecha.withNano(0).plusSeconds(1);
    }

    // Recupera los campos opcionales como null y la hora de la sesión de la conexión.
    private BitacoraAuditoria leerRegistro(ResultSet resultado) throws SQLException {
        return new BitacoraAuditoria(
                resultado.getLong("id"),
                resultado.getObject("usuario_id", Integer.class),
                resultado.getObject("fecha_hora", LocalDateTime.class),
                resultado.getString("accion_realizada"),
                resultado.getString("tabla_afectada"),
                resultado.getObject("registro_id_afectado", Integer.class),
                resultado.getString("detalles"));
    }
}
