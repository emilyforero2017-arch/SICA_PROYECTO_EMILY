package com.sica.infrastructure.persistence;

import com.sica.model.domain.entity.Rol;
import com.sica.model.domain.repository.RolRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RolRepositoryJdbc implements RolRepository {

    private static final String CONSULTA_ROLES = "SELECT id, nombre_rol FROM roles";

    private final Connection conexion;

    public RolRepositoryJdbc(Connection conexion) {
        // Utiliza la conexión que comparte la operación completa.
        this.conexion = Objects.requireNonNull(
                conexion, "La conexión de la base de datos es obligatoria.");
    }

    @Override
    public Optional<Rol> buscarPorId(Integer id) {
        Objects.requireNonNull(id, "El identificador del rol es obligatorio.");
        return buscar(CONSULTA_ROLES + " WHERE id = ?", id);
    }

    @Override
    public Optional<Rol> buscarPorNombre(String nombreRol) {
        Objects.requireNonNull(nombreRol, "El nombre del rol es obligatorio.");
        return buscar(CONSULTA_ROLES + " WHERE nombre_rol = ?", nombreRol);
    }

    private Optional<Rol> buscar(String sql, Object valor) {
        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setObject(1, valor);

            try (ResultSet resultado = consulta.executeQuery()) {
                return resultado.next()
                        ? Optional.of(leerRol(resultado))
                        : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar el rol.", error);
        }
    }

    @Override
    public List<Rol> listar() {
        String sql = CONSULTA_ROLES + " ORDER BY nombre_rol, id";
        List<Rol> roles = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql);
             ResultSet resultado = consulta.executeQuery()) {

            while (resultado.next()) {
                roles.add(leerRol(resultado));
            }
            return roles;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron listar los roles.", error);
        }
    }

    // Convierte cada fila de MySQL en el objeto Rol que necesitan los casos de uso.
    private Rol leerRol(ResultSet resultado) throws SQLException {
        return new Rol(
                resultado.getInt("id"),
                resultado.getString("nombre_rol"));
    }
}
