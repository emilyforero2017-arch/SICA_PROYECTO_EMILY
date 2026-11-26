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
        byte[] logo = empresa.getLogo();
        String sql = """
                INSERT INTO empresas (nombre, contacto_principal, esta_activa%s)
                VALUES (?, ?, ?%s)
                """.formatted(logo == null ? "" : ", logo", logo == null ? "" : ", ?");

        try (PreparedStatement consulta = conexion.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {

            // Los signos ? se completan con los datos, separados del SQL.
            consulta.setString(1, empresa.getNombre());
            consulta.setString(2, empresa.getContactoPrincipal());
            consulta.setBoolean(3, empresa.isEstaActiva());
            if (logo != null) consulta.setBytes(4, logo);

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
                        empresa.isEstaActiva(), logo);
            }
        } catch (SQLException error) {
            verificarColumnaLogo(logo, error);
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
        try {
            return consultarLista(false, true);
        } catch (SQLException error) {
            // Una instalación anterior sigue mostrando sus empresas mientras se habilita la columna.
            if (error.getErrorCode() == 1054) return listarSinLogos(false);
            throw new IllegalStateException("No se pudieron listar las empresas.", error);
        }
    }

    @Override
    public List<Empresa> listarActivas() {
        return listarSinLogos(true);
    }

    private List<Empresa> listarSinLogos(boolean soloActivas) {
        try {
            return consultarLista(soloActivas, false);
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron listar las empresas.", error);
        }
    }

    private List<Empresa> consultarLista(boolean soloActivas, boolean incluirLogo) throws SQLException {
        String sql = "SELECT " + COLUMNAS + (incluirLogo ? ", logo" : "") + " FROM empresas";
        if (soloActivas) {
            sql += " WHERE esta_activa = TRUE";
        }
        sql += " ORDER BY nombre, id";

        List<Empresa> empresas = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql);
             ResultSet resultado = consulta.executeQuery()) {

            while (resultado.next()) {
                empresas.add(leerEmpresa(resultado, incluirLogo ? resultado.getBytes("logo") : null));
            }
            return empresas;
        }
    }

    @Override
    public boolean actualizar(Empresa empresa) {
        byte[] logo = empresa.getLogo();
        // Actualizar los datos conserva el estado que la empresa tiene en MySQL.
        String sql = """
                UPDATE empresas
                SET nombre = ?, contacto_principal = ?%s
                WHERE id = ?
                """.formatted(logo == null ? "" : ", logo = ?");

        Objects.requireNonNull(
                empresa.getId(), "El identificador de la empresa es obligatorio.");

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setString(1, empresa.getNombre());
            consulta.setString(2, empresa.getContactoPrincipal());
            if (logo != null) consulta.setBytes(3, logo);
            consulta.setInt(logo == null ? 3 : 4, empresa.getId());

            return consulta.executeUpdate() == 1;
        } catch (SQLException error) {
            verificarColumnaLogo(logo, error);
            throw new IllegalStateException("No se pudo actualizar la empresa.", error);
        }
    }

    private static void verificarColumnaLogo(byte[] logo, SQLException error) {
        if (logo != null && error.getErrorCode() == 1054) {
            throw new IllegalStateException("La base de datos aún no tiene habilitada la carga de logos.", error);
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
        return leerEmpresa(resultado, null);
    }

    private Empresa leerEmpresa(ResultSet resultado, byte[] logo) throws SQLException {
        return new Empresa(
                resultado.getInt("id"),
                resultado.getString("nombre"),
                resultado.getString("contacto_principal"),
                resultado.getBoolean("esta_activa"), logo);
    }
}
