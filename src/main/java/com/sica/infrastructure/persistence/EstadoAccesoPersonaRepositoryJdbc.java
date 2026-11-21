package com.sica.infrastructure.persistence;

import com.sica.model.domain.entity.EstadoAccesoPersona;
import com.sica.model.domain.repository.EstadoAccesoPersonaRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class EstadoAccesoPersonaRepositoryJdbc implements EstadoAccesoPersonaRepository {

    private static final String CONSULTA_ESTADOS =
            "SELECT id, nombre_estado FROM persona_estados_acceso";

    private final Connection conexion;

    public EstadoAccesoPersonaRepositoryJdbc(Connection conexion) {
        // Utiliza la conexión que comparten los repositorios de la operación.
        this.conexion = Objects.requireNonNull(
                conexion, "La conexión de la base de datos es obligatoria.");
    }

    @Override
    public Optional<EstadoAccesoPersona> buscarPorId(Integer id) {
        Objects.requireNonNull(id, "El identificador del estado de acceso es obligatorio.");
        return buscar(CONSULTA_ESTADOS + " WHERE id = ?", id);
    }

    @Override
    public Optional<EstadoAccesoPersona> buscarPorNombre(String nombreEstado) {
        Objects.requireNonNull(nombreEstado, "El nombre del estado de acceso es obligatorio.");
        return buscar(CONSULTA_ESTADOS + " WHERE nombre_estado = ?", nombreEstado);
    }

    private Optional<EstadoAccesoPersona> buscar(String sql, Object valor) {
        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setObject(1, valor);

            try (ResultSet resultado = consulta.executeQuery()) {
                return resultado.next()
                        ? Optional.of(leerEstado(resultado))
                        : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar el estado de acceso.", error);
        }
    }

    @Override
    public List<EstadoAccesoPersona> listar() {
        String sql = CONSULTA_ESTADOS + " ORDER BY nombre_estado, id";
        List<EstadoAccesoPersona> estados = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql);
             ResultSet resultado = consulta.executeQuery()) {

            while (resultado.next()) {
                estados.add(leerEstado(resultado));
            }
            return estados;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron listar los estados de acceso.", error);
        }
    }

    // Recupera el número y el nombre guardados, sin depender de identificadores fijos en Java.
    private EstadoAccesoPersona leerEstado(ResultSet resultado) throws SQLException {
        return new EstadoAccesoPersona(
                resultado.getInt("id"),
                resultado.getString("nombre_estado"));
    }
}
