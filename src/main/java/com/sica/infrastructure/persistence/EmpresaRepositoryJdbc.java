package com.sica.infrastructure.persistence;

import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.repository.EmpresaRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class EmpresaRepositoryJdbc implements EmpresaRepository {

    private static final String COLUMNAS =
            "id, nombre, contacto_principal, esta_activa";

    private final Connection conexion;

    // Recibe la conexión que compartirán los repositorios de una operación.
    public EmpresaRepositoryJdbc(Connection conexion) {
        this.conexion = Objects.requireNonNull(
                conexion, "La conexión de la base de datos es obligatoria.");
    }

    @Override
    public Empresa guardar(Empresa empresa) {
        String sql = """
                INSERT INTO empresas (nombre, contacto_principal, esta_activa)
                VALUES (?, ?, ?)
                """;

        try (PreparedStatement consulta = conexion.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {

            // Los signos ? se completan con los datos, separados del SQL.
            consulta.setString(1, empresa.getNombre());
            consulta.setString(2, empresa.getContactoPrincipal());
            consulta.setBoolean(3, empresa.isEstaActiva());

            if (consulta.executeUpdate() != 1) {
                throw new SQLException("No se guardó la empresa esperada.");
            }

            // MySQL asigna el identificador; lo devolvemos en una nueva Empresa.
            try (ResultSet identificadores = consulta.getGeneratedKeys()) {
                if (!identificadores.next()) {
                    throw new SQLException(
                            "MySQL no devolvió el identificador de la empresa.");
                }

                return new Empresa(
                        identificadores.getInt(1),
                        empresa.getNombre(),
                        empresa.getContactoPrincipal(),
                        empresa.isEstaActiva());
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo guardar la empresa.", error);
        }
    }

    @Override
    public Optional<Empresa> buscarPorId(Integer id) {
        return buscar(id, false);
    }

    @Override
    public Optional<Empresa> buscarPorIdParaActualizar(Integer id) {
        return buscar(id, true);
    }

    private Optional<Empresa> buscar(Integer id, boolean proteger) {
        Objects.requireNonNull(id, "El identificador de la empresa es obligatorio.");

        String sql = "SELECT " + COLUMNAS + " FROM empresas WHERE id = ?";
        if (proteger) {
            // La protección dura hasta que el gestor confirme o deshaga la transacción.
            sql += " FOR UPDATE";
        }

        try {
            if (proteger && conexion.getAutoCommit()) {
                throw new IllegalStateException(
                        "La consulta protegida requiere una transacción abierta.");
            }

            try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
                consulta.setInt(1, id);

                try (ResultSet resultado = consulta.executeQuery()) {
                    if (resultado.next()) {
                        return Optional.of(leerEmpresa(resultado));
                    }
                    return Optional.empty();
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar la empresa.", error);
        }
    }

    @Override
    public List<Empresa> listar() {
        return consultarLista(false);
    }

    @Override
    public List<Empresa> listarActivas() {
        return consultarLista(true);
    }

    private List<Empresa> consultarLista(boolean soloActivas) {
        String sql = "SELECT " + COLUMNAS + " FROM empresas";
        if (soloActivas) {
            sql += " WHERE esta_activa = TRUE";
        }
        sql += " ORDER BY nombre, id";

        List<Empresa> empresas = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql);
             ResultSet resultado = consulta.executeQuery()) {

            while (resultado.next()) {
                empresas.add(leerEmpresa(resultado));
            }
            return empresas;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron listar las empresas.", error);
        }
    }

    @Override
    public boolean actualizar(Empresa empresa) {
        // Actualizar los datos conserva el estado que la empresa tiene en MySQL.
        String sql = """
                UPDATE empresas
                SET nombre = ?, contacto_principal = ?
                WHERE id = ?
                """;

        Objects.requireNonNull(
                empresa.getId(), "El identificador de la empresa es obligatorio.");

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setString(1, empresa.getNombre());
            consulta.setString(2, empresa.getContactoPrincipal());
            consulta.setInt(3, empresa.getId());

            return consulta.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo actualizar la empresa.", error);
        }
    }

    @Override
    public boolean desactivar(Integer id) {
        Objects.requireNonNull(id, "El identificador de la empresa es obligatorio.");

        // Cambia el estado y mantiene el registro para conservar su historial.
        String sql = """
                UPDATE empresas SET esta_activa = FALSE
                WHERE id = ? AND esta_activa = TRUE
                """;

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setInt(1, id);
            return consulta.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo desactivar la empresa.", error);
        }
    }

    // Convierte una fila de MySQL en el objeto Empresa que usan los casos de uso.
    private Empresa leerEmpresa(ResultSet resultado) throws SQLException {
        return new Empresa(
                resultado.getInt("id"),
                resultado.getString("nombre"),
                resultado.getString("contacto_principal"),
                resultado.getBoolean("esta_activa"));
    }
}
