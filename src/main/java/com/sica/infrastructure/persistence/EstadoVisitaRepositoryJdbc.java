package com.sica.infrastructure.persistence;

import com.sica.model.domain.entity.EstadoVisita;
import com.sica.model.domain.repository.EstadoVisitaRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class EstadoVisitaRepositoryJdbc implements EstadoVisitaRepository {

    private static final String CONSULTA_ESTADOS =
            "SELECT id, nombre_estado FROM visita_estados";

    private final Connection conexion;

    public EstadoVisitaRepositoryJdbc(Connection conexion) {
        // Utiliza la conexión que comparten los repositorios de la operación.
        this.conexion = Objects.requireNonNull(
                conexion, "La conexión de la base de datos es obligatoria.");
    }

    @Override
    public Optional<EstadoVisita> buscarPorId(Integer id) {
        Objects.requireNonNull(id, "El identificador del estado de visita es obligatorio.");
        return buscar(CONSULTA_ESTADOS + " WHERE id = ?", id);
    }

    @Override
    public Optional<EstadoVisita> buscarPorNombre(String nombreEstado) {
        Objects.requireNonNull(nombreEstado, "El nombre del estado de visita es obligatorio.");
        return buscar(CONSULTA_ESTADOS + " WHERE nombre_estado = ?", nombreEstado);
    }

    private Optional<EstadoVisita> buscar(String sql, Object valor) {
        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setObject(1, valor);

            try (ResultSet resultado = consulta.executeQuery()) {
                return resultado.next()
                        ? Optional.of(leerEstado(resultado))
                        : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar el estado de visita.", error);
        }
    }

    @Override
    public List<EstadoVisita> listar() {
        String sql = CONSULTA_ESTADOS + " ORDER BY nombre_estado, id";
        List<EstadoVisita> estados = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql);
             ResultSet resultado = consulta.executeQuery()) {

            while (resultado.next()) {
                estados.add(leerEstado(resultado));
            }
            return estados;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron listar los estados de visita.", error);
        }
    }

    // Recupera cada estado con el identificador que realmente tiene en MySQL.
    private EstadoVisita leerEstado(ResultSet resultado) throws SQLException {
        return new EstadoVisita(
                resultado.getInt("id"),
                resultado.getString("nombre_estado"));
    }
}
