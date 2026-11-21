package com.sica.infrastructure.persistence;

import com.sica.model.domain.entity.Permiso;
import com.sica.model.domain.repository.PermisoRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PermisoRepositoryJdbc implements PermisoRepository {

    private static final String CONSULTA_PERMISOS = """
            SELECT p.id, p.nombre_permiso, p.descripcion
            FROM permisos p
            """;

    private final Connection conexion;

    public PermisoRepositoryJdbc(Connection conexion) {
        // Comparte la conexión de la operación con los demás repositorios.
        this.conexion = Objects.requireNonNull(
                conexion, "La conexión de la base de datos es obligatoria.");
    }

    @Override
    public Optional<Permiso> buscarPorId(Integer id) {
        Objects.requireNonNull(id, "El identificador del permiso es obligatorio.");
        return buscar(CONSULTA_PERMISOS + " WHERE p.id = ?", id);
    }

    @Override
    public Optional<Permiso> buscarPorNombre(String nombrePermiso) {
        Objects.requireNonNull(nombrePermiso, "El nombre del permiso es obligatorio.");
        return buscar(CONSULTA_PERMISOS + " WHERE p.nombre_permiso = ?", nombrePermiso);
    }

    private Optional<Permiso> buscar(String sql, Object valor) {
        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setObject(1, valor);

            try (ResultSet resultado = consulta.executeQuery()) {
                return resultado.next()
                        ? Optional.of(leerPermiso(resultado))
                        : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar el permiso.", error);
        }
    }

    @Override
    public List<Permiso> listar() {
        return consultarLista(CONSULTA_PERMISOS + " ORDER BY p.nombre_permiso, p.id", null);
    }

    @Override
    public List<Permiso> listarPorRol(Integer rolId) {
        Objects.requireNonNull(rolId, "El identificador del rol es obligatorio.");

        // La tabla intermedia indica cuáles permisos están asignados al rol elegido.
        String sql = CONSULTA_PERMISOS + """
                 JOIN rol_permisos rp ON rp.permiso_id = p.id
                 WHERE rp.rol_id = ?
                 ORDER BY p.nombre_permiso, p.id
                """;
        return consultarLista(sql, rolId);
    }

    private List<Permiso> consultarLista(String sql, Integer rolId) {
        List<Permiso> permisos = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            if (rolId != null) {
                consulta.setInt(1, rolId);
            }

            try (ResultSet resultado = consulta.executeQuery()) {
                while (resultado.next()) {
                    permisos.add(leerPermiso(resultado));
                }
            }
            return permisos;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron listar los permisos.", error);
        }
    }

    @Override
    public boolean tienePermiso(Integer rolId, String nombrePermiso) {
        Objects.requireNonNull(rolId, "El identificador del rol es obligatorio.");
        Objects.requireNonNull(nombrePermiso, "El nombre del permiso es obligatorio.");

        String sql = """
                SELECT 1
                FROM rol_permisos rp
                JOIN permisos p ON p.id = rp.permiso_id
                WHERE rp.rol_id = ? AND p.nombre_permiso = ?
                LIMIT 1
                """;

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setInt(1, rolId);
            consulta.setString(2, nombrePermiso);

            try (ResultSet resultado = consulta.executeQuery()) {
                return resultado.next();
            }
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "No se pudo comprobar el permiso del rol.", error);
        }
    }

    @Override
    public boolean asignarARol(Integer rolId, Integer permisoId) {
        validarIdentificadores(rolId, permisoId);

        String sql = "INSERT INTO rol_permisos (rol_id, permiso_id) VALUES (?, ?)";

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setInt(1, rolId);
            consulta.setInt(2, permisoId);

            if (consulta.executeUpdate() != 1) {
                throw new SQLException("No se guardó la asignación esperada.");
            }
            return true;
        } catch (SQLException error) {
            // La clave de la tabla impide duplicados, incluso con solicitudes simultáneas.
            if (error.getErrorCode() == 1062 && "23000".equals(error.getSQLState())) {
                return false;
            }
            throw new IllegalStateException("No se pudo asignar el permiso al rol.", error);
        }
    }

    @Override
    public boolean retirarDeRol(Integer rolId, Integer permisoId) {
        validarIdentificadores(rolId, permisoId);

        // Retira la asignación; el permiso sigue existiendo en el catálogo.
        String sql = "DELETE FROM rol_permisos WHERE rol_id = ? AND permiso_id = ?";

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setInt(1, rolId);
            consulta.setInt(2, permisoId);
            return consulta.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo retirar el permiso del rol.", error);
        }
    }

    private void validarIdentificadores(Integer rolId, Integer permisoId) {
        Objects.requireNonNull(rolId, "El identificador del rol es obligatorio.");
        Objects.requireNonNull(permisoId, "El identificador del permiso es obligatorio.");
    }

    private Permiso leerPermiso(ResultSet resultado) throws SQLException {
        return new Permiso(
                resultado.getInt("id"),
                resultado.getString("nombre_permiso"),
                resultado.getString("descripcion"));
    }
}
