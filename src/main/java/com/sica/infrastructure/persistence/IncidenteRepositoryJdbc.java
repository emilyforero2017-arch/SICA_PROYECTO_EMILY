package com.sica.infrastructure.persistence;

import com.sica.model.domain.entity.Incidente;
import com.sica.model.domain.repository.IncidenteRepository;
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

public final class IncidenteRepositoryJdbc implements IncidenteRepository {

    private static final String CONSULTA_INCIDENTES = """
            SELECT id, visita_id, registrado_por_id, fecha_incidente, descripcion
            FROM incidentes
            """;

    private static final String ORDEN_RECIENTE = " ORDER BY fecha_incidente DESC, id DESC";

    private final Connection conexion;

    public IncidenteRepositoryJdbc(Connection conexion) {
        // Comparte la conexión de la operación con la visita y la auditoría.
        this.conexion = Objects.requireNonNull(
                conexion, "La conexión de la base de datos es obligatoria.");
    }

    @Override
    public Incidente guardar(Incidente incidente) {
        Objects.requireNonNull(incidente, "Los datos del incidente son obligatorios.");

        String sql = """
                INSERT INTO incidentes (visita_id, registrado_por_id, fecha_incidente, descripcion)
                VALUES (?, ?, ?, ?)
                """;

        int id;
        try (PreparedStatement consulta = conexion.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            consulta.setInt(1, incidente.getVisitaId());
            consulta.setInt(2, incidente.getRegistradoPorId());
            // Conserva la fecha del hecho con los segundos que admite el esquema.
            consulta.setObject(3, incidente.getFechaIncidente().withNano(0), Types.TIMESTAMP);
            consulta.setString(4, incidente.getDescripcion());

            if (consulta.executeUpdate() != 1) {
                throw new SQLException("No se guardó el incidente esperado.");
            }
            try (ResultSet identificadores = consulta.getGeneratedKeys()) {
                if (!identificadores.next()) {
                    throw new SQLException("MySQL no devolvió el identificador del incidente.");
                }
                id = identificadores.getInt(1);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo guardar el incidente.", error);
        }

        return buscarPorId(id).orElseThrow(() -> new IllegalStateException(
                "No se pudo recuperar el incidente recién guardado."));
    }

    @Override
    public Optional<Incidente> buscarPorId(Integer id) {
        Objects.requireNonNull(id, "El identificador del incidente es obligatorio.");

        String sql = CONSULTA_INCIDENTES + " WHERE id = ?";
        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setInt(1, id);

            try (ResultSet resultado = consulta.executeQuery()) {
                return resultado.next()
                        ? Optional.of(leerIncidente(resultado))
                        : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar el incidente.", error);
        }
    }

    @Override
    public List<Incidente> listarPorVisita(Integer visitaId) {
        Objects.requireNonNull(visitaId, "La visita a consultar es obligatoria.");
        return consultarLista(CONSULTA_INCIDENTES + " WHERE visita_id = ?" + ORDEN_RECIENTE, visitaId);
    }

    @Override
    public List<Incidente> listarPorRegistradoPor(Integer usuarioId) {
        Objects.requireNonNull(usuarioId, "El usuario que registró los incidentes es obligatorio.");
        // También conserva el historial de una cuenta que posteriormente fue desactivada.
        return consultarLista(CONSULTA_INCIDENTES + " WHERE registrado_por_id = ?"
                + ORDEN_RECIENTE, usuarioId);
    }

    @Override
    public List<Incidente> listarPorPeriodo(LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null || !hasta.isAfter(desde)) {
            throw new IllegalArgumentException(
                    "Debes indicar un período con fecha final posterior a la inicial.");
        }

        // Filtra por la fecha del hecho: incluye el inicio y excluye el final.
        String sql = CONSULTA_INCIDENTES + " WHERE fecha_incidente >= ? AND fecha_incidente < ?"
                + ORDEN_RECIENTE;
        return consultarLista(sql, elevarAlSegundo(desde), elevarAlSegundo(hasta));
    }

    private List<Incidente> consultarLista(String sql, Object... valores) {
        List<Incidente> incidentes = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            for (int i = 0; i < valores.length; i++) {
                consulta.setObject(i + 1, valores[i]);
            }

            try (ResultSet resultado = consulta.executeQuery()) {
                while (resultado.next()) {
                    incidentes.add(leerIncidente(resultado));
                }
            }
            return incidentes;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron listar los incidentes.", error);
        }
    }

    private Incidente leerIncidente(ResultSet resultado) throws SQLException {
        return new Incidente(
                resultado.getInt("id"),
                resultado.getInt("visita_id"),
                resultado.getInt("registrado_por_id"),
                resultado.getObject("fecha_incidente", LocalDateTime.class),
                resultado.getString("descripcion"));
    }

    private LocalDateTime elevarAlSegundo(LocalDateTime fecha) {
        // Respeta el intervalo cuando se compara con fechas guardadas sin fracciones.
        return fecha.getNano() == 0 ? fecha : fecha.withNano(0).plusSeconds(1);
    }
}
