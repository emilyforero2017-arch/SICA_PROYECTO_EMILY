package com.sica.infrastructure.persistence;

import com.sica.model.domain.entity.FotoPersona;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.TipoPersona;
import com.sica.model.domain.repository.PersonaRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PersonaRepositoryJdbc implements PersonaRepository {

    private static final String CONSULTA_PERSONAS = """
            SELECT id, nombre, documento_identidad, empresa_id, tipo_persona,
                   estado_acceso_id, url_foto, vinculacion_laboral_activa
            FROM personas
            """;

    private final Connection conexion;

    public PersonaRepositoryJdbc(Connection conexion) {
        // Utiliza la conexión que comparten los repositorios de la operación.
        this.conexion = Objects.requireNonNull(
                conexion, "La conexión de la base de datos es obligatoria.");
    }

    @Override
    public Persona guardar(Persona persona) {
        Objects.requireNonNull(persona, "Los datos de la persona son obligatorios.");

        String foto = persona.getUrlFoto();
        boolean archivo = FotoPersona.esCarga(foto);
        String sql = """
                INSERT INTO personas
                    (nombre, documento_identidad, empresa_id, tipo_persona,
                     estado_acceso_id, url_foto, vinculacion_laboral_activa%s)
                VALUES (?, ?, ?, ?, ?, ?, ?%s)
                """.formatted(archivo ? ", foto_archivo" : "", archivo ? ", ?" : "");

        try (PreparedStatement consulta = conexion.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            completarDatos(consulta, persona);
            if (archivo) consulta.setBytes(8, FotoPersona.decodificar(foto));

            if (consulta.executeUpdate() != 1) {
                throw new SQLException("No se guardó la persona esperada.");
            }

            try (ResultSet identificadores = consulta.getGeneratedKeys()) {
                if (!identificadores.next()) {
                    throw new SQLException(
                            "MySQL no devolvió el identificador de la persona.");
                }

                return new Persona(
                        identificadores.getInt(1), persona.getNombre(),
                        persona.getDocumentoIdentidad(), persona.getEmpresaId(),
                        persona.getTipoPersona(), persona.getEstadoAccesoId(),
                        archivo ? FotoPersona.ARCHIVO_GUARDADO : foto, persona.getVinculacionLaboralActiva());
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo guardar la persona.", error);
        }
    }

    @Override
    public Optional<Persona> buscarPorId(Integer id) {
        Objects.requireNonNull(id, "El identificador de la persona es obligatorio.");
        return buscar(CONSULTA_PERSONAS + " WHERE id = ?", id, false);
    }

    @Override
    public Optional<Persona> buscarPorIdParaActualizar(Integer id) {
        Objects.requireNonNull(id, "El identificador de la persona es obligatorio.");
        return buscar(CONSULTA_PERSONAS + " WHERE id = ? FOR UPDATE", id, true);
    }

    @Override
    public Optional<Persona> buscarPorDocumento(String documentoIdentidad) {
        Objects.requireNonNull(documentoIdentidad, "El documento de identidad es obligatorio.");
        return buscar(CONSULTA_PERSONAS + " WHERE documento_identidad = ?", documentoIdentidad, false);
    }

    @Override
    public Optional<byte[]> buscarFotoArchivo(Integer id) {
        String sql = "SELECT foto_archivo FROM personas WHERE id = ? AND url_foto = ?";
        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setInt(1, id);
            consulta.setString(2, FotoPersona.ARCHIVO_GUARDADO);
            try (ResultSet resultado = consulta.executeQuery()) {
                return resultado.next() ? Optional.ofNullable(resultado.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar la foto de la persona.", error);
        }
    }

    @Override
    public Optional<Persona> buscarPorDocumentoParaActualizar(String documentoIdentidad) {
        Objects.requireNonNull(documentoIdentidad, "El documento de identidad es obligatorio.");
        return buscar(CONSULTA_PERSONAS + " WHERE documento_identidad = ? FOR UPDATE", documentoIdentidad, true);
    }

    private Optional<Persona> buscar(String sql, Object valor, boolean proteger) {
        try {
            // La protección se conserva hasta que el gestor confirme o deshaga los cambios.
            if (proteger && conexion.getAutoCommit()) {
                throw new IllegalStateException(
                        "La consulta protegida requiere una transacción abierta.");
            }

            try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
                consulta.setObject(1, valor);

                try (ResultSet resultado = consulta.executeQuery()) {
                    return resultado.next()
                            ? Optional.of(leerPersona(resultado))
                            : Optional.empty();
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar la persona.", error);
        }
    }

    @Override
    public List<Persona> listar() {
        return consultarLista("", null);
    }

    @Override
    public List<Persona> listarTrabajadoresPorEmpresa(Integer empresaId) {
        Objects.requireNonNull(empresaId, "El identificador de la empresa es obligatorio.");
        // Incluye vínculos finalizados para conservar el historial de la empresa.
        String filtro = " WHERE empresa_id = ? AND tipo_persona = 'Trabajador'";
        return consultarLista(filtro, empresaId);
    }

    @Override
    public List<Persona> listarPorEstadoAcceso(Integer estadoAccesoId) {
        Objects.requireNonNull(estadoAccesoId, "El estado de acceso es obligatorio.");
        return consultarLista(" WHERE estado_acceso_id = ?", estadoAccesoId);
    }

    private List<Persona> consultarLista(String filtro, Integer valor) {
        String sql = CONSULTA_PERSONAS + filtro + " ORDER BY nombre, id";
        List<Persona> personas = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            if (valor != null) {
                consulta.setInt(1, valor);
            }

            try (ResultSet resultado = consulta.executeQuery()) {
                while (resultado.next()) {
                    personas.add(leerPersona(resultado));
                }
            }
            return personas;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron listar las personas.", error);
        }
    }

    @Override
    public boolean actualizar(Persona persona) {
        Objects.requireNonNull(persona, "Los datos de la persona son obligatorios.");
        Objects.requireNonNull(persona.getId(), "El identificador de la persona es obligatorio.");

        // El caso de uso prepara los cambios permitidos y conserva los datos que deben mantenerse.
        String foto = persona.getUrlFoto();
        boolean archivo = FotoPersona.esCarga(foto);
        String sql = """
                UPDATE personas
                SET nombre = ?, documento_identidad = ?, empresa_id = ?, tipo_persona = ?,
                    estado_acceso_id = ?, url_foto = ?, vinculacion_laboral_activa = ?%s
                WHERE id = ?
                """.formatted(archivo ? ", foto_archivo = ?" : "");

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            completarDatos(consulta, persona);
            if (archivo) consulta.setBytes(8, FotoPersona.decodificar(foto));
            consulta.setInt(archivo ? 9 : 8, persona.getId());
            return consulta.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo actualizar la persona.", error);
        }
    }

    private void completarDatos(PreparedStatement consulta, Persona persona) throws SQLException {
        consulta.setString(1, persona.getNombre());
        consulta.setString(2, persona.getDocumentoIdentidad());
        consulta.setObject(3, persona.getEmpresaId(), Types.INTEGER);
        consulta.setString(4, switch (persona.getTipoPersona()) {
            case TRABAJADOR -> "Trabajador";
            case INVITADO -> "Invitado";
        });
        consulta.setInt(5, persona.getEstadoAccesoId());
        String foto = persona.getUrlFoto();
        consulta.setString(6, FotoPersona.esCarga(foto) ? FotoPersona.ARCHIVO_GUARDADO : foto);
        consulta.setObject(7, persona.getVinculacionLaboralActiva(), Types.BOOLEAN);
    }

    private Persona leerPersona(ResultSet resultado) throws SQLException {
        // Traduce los nombres guardados en MySQL al tipo que utilizamos en Java.
        TipoPersona tipo = switch (resultado.getString("tipo_persona")) {
            case "Trabajador" -> TipoPersona.TRABAJADOR;
            case "Invitado" -> TipoPersona.INVITADO;
            default -> throw new SQLException("El tipo de persona guardado no es reconocido.");
        };

        return new Persona(
                resultado.getInt("id"),
                resultado.getString("nombre"),
                resultado.getString("documento_identidad"),
                resultado.getObject("empresa_id", Integer.class),
                tipo,
                resultado.getInt("estado_acceso_id"),
                resultado.getString("url_foto"),
                resultado.getObject("vinculacion_laboral_activa", Boolean.class));
    }
}
