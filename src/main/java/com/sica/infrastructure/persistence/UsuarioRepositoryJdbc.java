package com.sica.infrastructure.persistence;

import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.repository.UsuarioRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;

public final class UsuarioRepositoryJdbc implements UsuarioRepository {

    private static final String CONSULTA_USUARIOS = """
            SELECT u.id, u.nombre, u.email, u.password_hash,
                   u.rol_id, u.empresa_id, u.esta_activo
            FROM usuarios u
            """;

    private final Connection conexion;

    public UsuarioRepositoryJdbc(Connection conexion) {
        // Comparte la conexión de la operación con los demás repositorios.
        this.conexion = Objects.requireNonNull(
                conexion, "La conexión de la base de datos es obligatoria.");
    }

    @Override
    public Map<Integer, String> buscarNombresPorIds(Set<Integer> ids) {
        List<Integer> pendientes = List.copyOf(ids);
        Map<Integer, String> nombres = new HashMap<>();
        // Limitamos el número de parámetros por consulta para historiales extensos.
        int tamanoLote = 500;
        for (int inicio = 0; inicio < pendientes.size(); inicio += tamanoLote) {
            List<Integer> lote = pendientes.subList(inicio, Math.min(inicio + tamanoLote, pendientes.size()));
            String parametros = String.join(", ", Collections.nCopies(lote.size(), "?"));
            String sql = "SELECT id, nombre FROM usuarios WHERE id IN (" + parametros + ")";
            try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
                for (int i = 0; i < lote.size(); i++) consulta.setInt(i + 1, lote.get(i));
                try (ResultSet filas = consulta.executeQuery()) {
                    while (filas.next()) nombres.put(filas.getInt("id"), filas.getString("nombre"));
                }
            } catch (SQLException error) {
                throw new IllegalStateException("No fue posible consultar los responsables de la auditoría.", error);
            }
        }
        return nombres;
    }

    @Override
    public Usuario guardar(Usuario usuario) {
        Objects.requireNonNull(usuario, "Los datos del usuario son obligatorios.");

        byte[] foto = usuario.getFotoPerfil();
        String sql = """
                INSERT INTO usuarios
                    (nombre, email, password_hash, rol_id, empresa_id, esta_activo%s)
                VALUES (?, ?, ?, ?, ?, ?%s)
                """.formatted(foto == null ? "" : ", foto_perfil", foto == null ? "" : ", ?");

        try (PreparedStatement consulta = conexion.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            consulta.setString(1, usuario.getNombre());
            consulta.setString(2, usuario.getEmail());
            // El caso de uso entrega un hash ya calculado por el servicio de contraseñas.
            consulta.setString(3, usuario.getPasswordHash());
            consulta.setInt(4, usuario.getRolId());
            consulta.setObject(5, usuario.getEmpresaId(), Types.INTEGER);
            consulta.setBoolean(6, usuario.isEstaActivo());
            if (foto != null) consulta.setBytes(7, foto);

            if (consulta.executeUpdate() != 1) {
                throw new SQLException("No se guardó el usuario esperado.");
            }

            try (ResultSet identificadores = consulta.getGeneratedKeys()) {
                if (!identificadores.next()) {
                    throw new SQLException(
                            "MySQL no devolvió el identificador del usuario.");
                }

                return new Usuario(
                        identificadores.getInt(1),
                        usuario.getNombre(), usuario.getEmail(), usuario.getPasswordHash(),
                        usuario.getRolId(), usuario.getEmpresaId(), usuario.isEstaActivo(), foto);
            }
        } catch (SQLException error) {
            verificarColumnaFoto(foto, error);
            throw new IllegalStateException("No se pudo guardar el usuario.", error);
        }
    }

    @Override
    public Optional<Usuario> buscarPorId(Integer id) {
        Objects.requireNonNull(id, "El identificador del usuario es obligatorio.");
        return buscar(CONSULTA_USUARIOS + " WHERE u.id = ?", id, false);
    }

    @Override
    public Optional<Usuario> buscarPorIdParaActualizar(Integer id) {
        Objects.requireNonNull(id, "El identificador del usuario es obligatorio.");
        return buscar(CONSULTA_USUARIOS + " WHERE u.id = ? FOR UPDATE", id, true);
    }

    @Override
    public Optional<Usuario> buscarPorEmail(String email) {
        Objects.requireNonNull(email, "El correo del usuario es obligatorio.");
        return buscar(CONSULTA_USUARIOS + " WHERE u.email = ?", email, false);
    }

    @Override
    public Optional<byte[]> buscarFotoPerfil(Integer id) {
        try (PreparedStatement consulta = conexion.prepareStatement("SELECT foto_perfil FROM usuarios WHERE id = ?")) {
            consulta.setInt(1, id);
            try (ResultSet filas = consulta.executeQuery()) {
                return filas.next() ? Optional.ofNullable(filas.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException error) {
            if (error.getErrorCode() == 1054) return Optional.empty();
            throw new IllegalStateException("No se pudo consultar la foto de perfil.", error);
        }
    }

    private Optional<Usuario> buscar(String sql, Object valor, boolean proteger) {
        try {
            // La protección del registro se mantiene hasta confirmar o deshacer la transacción.
            if (proteger && conexion.getAutoCommit()) {
                throw new IllegalStateException(
                        "La consulta protegida requiere una transacción abierta.");
            }

            try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
                consulta.setObject(1, valor);

                try (ResultSet resultado = consulta.executeQuery()) {
                    return resultado.next()
                            ? Optional.of(leerUsuario(resultado))
                            : Optional.empty();
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar el usuario.", error);
        }
    }

    @Override
    public List<Usuario> listar() {
        return consultarLista("", null);
    }

    @Override
    public List<Usuario> listarActivos() {
        return consultarLista(" WHERE u.esta_activo = TRUE", null);
    }

    @Override
    public List<Usuario> listarPorEmpresa(Integer empresaId) {
        Objects.requireNonNull(empresaId, "El identificador de la empresa es obligatorio.");
        // Incluye cuentas retiradas para conservar las consultas de historial.
        return consultarLista(" WHERE u.empresa_id = ?", empresaId);
    }

    @Override
    public List<Usuario> listarFuncionariosActivosPorEmpresa(Integer empresaId) {
        Objects.requireNonNull(empresaId, "El identificador de la empresa es obligatorio.");
        // Consulta el rol por su nombre, sin depender de un número fijo en el código.
        String filtro = """
                 JOIN roles r ON r.id = u.rol_id
                 WHERE u.empresa_id = ? AND u.esta_activo = TRUE
                   AND r.nombre_rol = 'Funcionario de Empresa'
                """;
        return consultarLista(filtro, empresaId);
    }

    private List<Usuario> consultarLista(String filtro, Integer empresaId) {
        String sql = CONSULTA_USUARIOS + filtro + " ORDER BY u.nombre, u.id";
        List<Usuario> usuarios = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            if (empresaId != null) {
                consulta.setInt(1, empresaId);
            }

            try (ResultSet resultado = consulta.executeQuery()) {
                while (resultado.next()) {
                    usuarios.add(leerUsuario(resultado));
                }
            }
            return usuarios;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron listar los usuarios.", error);
        }
    }

    @Override
    public boolean actualizar(Usuario usuario) {
        Objects.requireNonNull(usuario, "Los datos del usuario son obligatorios.");
        Objects.requireNonNull(usuario.getId(), "El identificador del usuario es obligatorio.");

        byte[] foto = usuario.getFotoPerfil();
        String sql = """
                UPDATE usuarios SET nombre = ?, email = ?%s WHERE id = ?
                """.formatted(foto == null ? "" : ", foto_perfil = ?");

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setString(1, usuario.getNombre());
            consulta.setString(2, usuario.getEmail());
            if (foto != null) consulta.setBytes(3, foto);
            consulta.setInt(foto == null ? 3 : 4, usuario.getId());
            return consulta.executeUpdate() == 1;
        } catch (SQLException error) {
            verificarColumnaFoto(foto, error);
            throw new IllegalStateException("No se pudo actualizar el usuario.", error);
        }
    }

    private static void verificarColumnaFoto(byte[] foto, SQLException error) {
        if (foto != null && error.getErrorCode() == 1054) {
            throw new IllegalStateException("La base de datos aún no tiene habilitadas las fotos de perfil.", error);
        }
    }

    @Override
    public boolean desactivar(Integer id) {
        Objects.requireNonNull(id, "El identificador del usuario es obligatorio.");

        // Conserva la cuenta y sus relaciones; cambia únicamente su estado de actividad.
        String sql = """
                UPDATE usuarios SET esta_activo = FALSE
                WHERE id = ? AND esta_activo = TRUE
                """;

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setInt(1, id);
            return consulta.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo desactivar el usuario.", error);
        }
    }

    // Reconstruye la cuenta conservando también la ausencia de empresa en los roles globales.
    private Usuario leerUsuario(ResultSet resultado) throws SQLException {
        return new Usuario(
                resultado.getInt("id"),
                resultado.getString("nombre"),
                resultado.getString("email"),
                resultado.getString("password_hash"),
                resultado.getInt("rol_id"),
                resultado.getObject("empresa_id", Integer.class),
                resultado.getBoolean("esta_activo"));
    }
}
