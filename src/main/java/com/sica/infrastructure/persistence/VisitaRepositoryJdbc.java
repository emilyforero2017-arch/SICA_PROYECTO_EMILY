package com.sica.infrastructure.persistence;

import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.repository.MovimientoPorteria;
import com.sica.model.domain.repository.ResumenPorteria;
import com.sica.model.domain.repository.SolicitudEnPorteria;
import com.sica.model.domain.repository.VisitaRepository;
import com.sica.model.domain.repository.VisitaPrevista;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class VisitaRepositoryJdbc implements VisitaRepository {

    private static final String CONSULTA_VISITAS = """
            SELECT id, persona_id, empresa_destino_id, funcionario_destinatario_id,
                   tipo_ingreso, fecha_programada, fecha_aprobacion, fecha_entrada,
                   fecha_salida, fecha_cierre_sistema, estado_visita_id,
                   vehiculo_placa, aprobada_por_id
            FROM visitas
            """;

    private static final String INGRESO_ABIERTO =
            "fecha_entrada IS NOT NULL AND fecha_salida IS NULL AND fecha_cierre_sistema IS NULL";

    private final Connection conexion;

    public VisitaRepositoryJdbc(Connection conexion) {
        // Comparte la conexión de la operación con las personas y su auditoría.
        this.conexion = Objects.requireNonNull(
                conexion, "La conexión de la base de datos es obligatoria.");
    }

    @Override
    public List<VisitaPrevista> listarVisitasPrevistas(LocalDateTime desde, LocalDateTime hasta) {
        String sql = """
                SELECT v.id, p.nombre AS invitado, p.documento_identidad,
                       e.nombre AS empresa, u.nombre AS funcionario, v.fecha_programada
                  FROM visitas v
                  JOIN visita_estados estado ON estado.id = v.estado_visita_id
                  JOIN personas p ON p.id = v.persona_id
                  JOIN persona_estados_acceso acceso ON acceso.id = p.estado_acceso_id
                  JOIN empresas e ON e.id = v.empresa_destino_id
                  JOIN usuarios u ON u.id = v.funcionario_destinatario_id
                  JOIN roles rol ON rol.id = u.rol_id
                 WHERE v.tipo_ingreso = 'Invitado preregistrado'
                   AND estado.nombre_estado = 'Aprobado' AND acceso.nombre_estado = 'Activo'
                   AND e.esta_activa AND u.esta_activo AND u.empresa_id = e.id
                   AND rol.nombre_rol = 'Funcionario de Empresa'
                   AND v.aprobada_por_id = u.id AND v.fecha_aprobacion IS NOT NULL
                   AND v.fecha_entrada IS NULL AND v.fecha_salida IS NULL AND v.fecha_cierre_sistema IS NULL
                   AND v.fecha_programada >= ? AND v.fecha_programada < ?
                 ORDER BY v.fecha_programada, v.id
                """;
        List<VisitaPrevista> visitas = new ArrayList<>();
        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setObject(1, desde);
            consulta.setObject(2, hasta);
            try (ResultSet resultado = consulta.executeQuery()) {
                while (resultado.next()) {
                    visitas.add(new VisitaPrevista(resultado.getInt("id"), resultado.getString("invitado"),
                            resultado.getString("documento_identidad"), resultado.getString("empresa"),
                            resultado.getString("funcionario"), resultado.getObject("fecha_programada", LocalDateTime.class)));
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron consultar las próximas llegadas.", error);
        }
        return visitas;
    }

    @Override
    public List<SolicitudEnPorteria> listarSolicitudesEnviadas(Integer guardaId) {
        // La auditoría identifica al guarda que envió la solicitud.
        String sql = """
                SELECT v.*, p.nombre AS persona, p.documento_identidad,
                       e.nombre AS empresa, u.nombre AS funcionario, estado.nombre_estado
                  FROM visitas v
                  JOIN personas p ON p.id = v.persona_id
                  JOIN empresas e ON e.id = v.empresa_destino_id
                  JOIN usuarios u ON u.id = v.funcionario_destinatario_id
                  JOIN visita_estados estado ON estado.id = v.estado_visita_id
                 WHERE v.tipo_ingreso IN ('Invitado no anunciado', 'Trabajador con carnet olvidado')
                   AND EXISTS (
                       SELECT 1 FROM bitacora_auditoria b
                        WHERE b.usuario_id = ? AND b.tabla_afectada = 'visitas'
                          AND b.registro_id_afectado = v.id
                          AND b.accion_realizada IN ('SOLICITUD_VISITA_NO_ANUNCIADA', 'SOLICITUD_CARNET_OLVIDADO')
                   )
                 ORDER BY v.id DESC
                """;
        List<SolicitudEnPorteria> solicitudes = new ArrayList<>();
        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setInt(1, guardaId);
            try (ResultSet resultado = consulta.executeQuery()) {
                while (resultado.next()) {
                    solicitudes.add(new SolicitudEnPorteria(leerVisita(resultado), resultado.getString("persona"),
                            resultado.getString("documento_identidad"), resultado.getString("empresa"),
                            resultado.getString("funcionario"), resultado.getString("nombre_estado")));
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron consultar las solicitudes de portería.", error);
        }
        return solicitudes;
    }

    @Override
    public List<MovimientoPorteria> listarMovimientosPorteria(Integer guardaId, LocalDateTime desde,
            LocalDateTime hasta, MovimientoPorteria.Tipo tipo) {
        String filtroIngreso = switch (tipo) {
            case ENTRADA_INVITADO -> " AND v.tipo_ingreso IN ('Invitado preregistrado', 'Invitado no anunciado')";
            case INGRESO_LABORAL -> " AND v.tipo_ingreso IN ('Trabajador con carnet', 'Trabajador con carnet olvidado')";
            case SALIDA -> "";
        };
        // La bitácora conserva la cuenta que registró el movimiento, incluso si la visita se cerró después.
        String sql = """
                SELECT b.fecha_hora, p.nombre AS persona, e.nombre AS empresa
                  FROM bitacora_auditoria b
                  JOIN visitas v ON v.id = b.registro_id_afectado
                  JOIN personas p ON p.id = v.persona_id
                  JOIN empresas e ON e.id = v.empresa_destino_id
                 WHERE b.usuario_id = ? AND b.tabla_afectada = 'visitas'
                   AND b.accion_realizada = ?
                   AND b.fecha_hora >= ? AND b.fecha_hora < ?
                """ + filtroIngreso + " ORDER BY b.fecha_hora DESC, b.id DESC LIMIT 5";
        List<MovimientoPorteria> movimientos = new ArrayList<>();
        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setInt(1, guardaId);
            consulta.setString(2, tipo == MovimientoPorteria.Tipo.SALIDA ? "REGISTRO_SALIDA" : "REGISTRO_ENTRADA");
            consulta.setObject(3, desde);
            consulta.setObject(4, hasta);
            try (ResultSet resultado = consulta.executeQuery()) {
                while (resultado.next()) {
                    movimientos.add(new MovimientoPorteria(resultado.getObject("fecha_hora", LocalDateTime.class),
                            resultado.getString("persona"), resultado.getString("empresa")));
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron consultar los movimientos de portería.", error);
        }
        return movimientos;
    }

    @Override
    public ResumenPorteria resumirPorteria(Integer guardaId, LocalDateTime desde, LocalDateTime hasta) {
        // La bitácora identifica quién confirmó cada movimiento y quién envió cada solicitud.
        String sql = """
                SELECT COUNT(CASE WHEN movimiento.accion_realizada = 'REGISTRO_ENTRADA' THEN 1 END) AS entradas,
                       COUNT(CASE WHEN movimiento.accion_realizada = 'REGISTRO_SALIDA' THEN 1 END) AS salidas,
                       (SELECT COUNT(DISTINCT visita.id)
                          FROM bitacora_auditoria solicitud
                          JOIN visitas visita ON visita.id = solicitud.registro_id_afectado
                          JOIN visita_estados estado ON estado.id = visita.estado_visita_id
                         WHERE solicitud.usuario_id = ?
                           AND solicitud.tabla_afectada = 'visitas'
                           AND solicitud.accion_realizada IN ('SOLICITUD_VISITA_NO_ANUNCIADA', 'SOLICITUD_CARNET_OLVIDADO')
                           AND estado.nombre_estado IN ('Pendiente de Aprobación', 'Pendiente de Aprobación por Olvido')
                       ) AS pendientes
                  FROM bitacora_auditoria movimiento
                 WHERE movimiento.usuario_id = ?
                   AND movimiento.tabla_afectada = 'visitas'
                   AND movimiento.accion_realizada IN ('REGISTRO_ENTRADA', 'REGISTRO_SALIDA')
                   AND movimiento.fecha_hora >= ? AND movimiento.fecha_hora < ?
                """;
        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setInt(1, guardaId);
            consulta.setInt(2, guardaId);
            consulta.setObject(3, desde);
            consulta.setObject(4, hasta);
            try (ResultSet resultado = consulta.executeQuery()) {
                if (!resultado.next()) throw new SQLException("MySQL no devolvió los totales de portería.");
                return new ResumenPorteria(resultado.getLong("entradas"), resultado.getLong("salidas"),
                        resultado.getLong("pendientes"));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar el resumen de portería.", error);
        }
    }

    @Override
    public Visita guardar(Visita visita) {
        Objects.requireNonNull(visita, "Los datos de la visita son obligatorios.");

        String sql = """
                INSERT INTO visitas
                    (persona_id, empresa_destino_id, funcionario_destinatario_id,
                     tipo_ingreso, fecha_programada, fecha_aprobacion, fecha_entrada,
                     fecha_salida, fecha_cierre_sistema, estado_visita_id,
                     vehiculo_placa, aprobada_por_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        int id;
        try (PreparedStatement consulta = conexion.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            completarDatos(consulta, visita);

            if (consulta.executeUpdate() != 1) {
                throw new SQLException("No se guardó la visita esperada.");
            }
            try (ResultSet identificadores = consulta.getGeneratedKeys()) {
                if (!identificadores.next()) {
                    throw new SQLException("MySQL no devolvió el identificador de la visita.");
                }
                id = identificadores.getInt(1);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo guardar la visita.", error);
        }

        return buscarPorId(id).orElseThrow(() -> new IllegalStateException(
                "No se pudo recuperar la visita recién guardada."));
    }

    @Override
    public Optional<Visita> buscarPorId(Integer id) {
        Objects.requireNonNull(id, "El identificador de la visita es obligatorio.");
        return buscar(CONSULTA_VISITAS + " WHERE id = ?", false, id);
    }

    @Override
    public Optional<Visita> buscarPorIdParaActualizar(Integer id) {
        Objects.requireNonNull(id, "El identificador de la visita es obligatorio.");
        return buscar(CONSULTA_VISITAS + " WHERE id = ? FOR UPDATE", true, id);
    }

    private Optional<Visita> buscar(String sql, boolean proteger, Object... valores) {
        try {
            if (proteger && conexion.getAutoCommit()) {
                throw new IllegalStateException(
                        "La consulta protegida requiere una transacción abierta.");
            }

            try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
                completarParametros(consulta, valores);

                try (ResultSet resultado = consulta.executeQuery()) {
                    return resultado.next()
                            ? Optional.of(leerVisita(resultado))
                            : Optional.empty();
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo consultar la visita.", error);
        }
    }

    @Override
    public List<Visita> listarPorPersona(Integer personaId) {
        Objects.requireNonNull(personaId, "El identificador de la persona es obligatorio.");
        return consultarLista(CONSULTA_VISITAS + " WHERE persona_id = ? ORDER BY id DESC", personaId);
    }

    @Override
    public List<Visita> listarPorFuncionarioDestinatario(Integer funcionarioId) {
        Objects.requireNonNull(funcionarioId, "El funcionario destinatario es obligatorio.");
        return consultarLista(CONSULTA_VISITAS
                + " WHERE funcionario_destinatario_id = ? ORDER BY id DESC", funcionarioId);
    }

    @Override
    public List<Visita> listarPorEstado(Integer estadoVisitaId) {
        Objects.requireNonNull(estadoVisitaId, "El estado de la visita es obligatorio.");
        return consultarLista(CONSULTA_VISITAS
                + " WHERE estado_visita_id = ? ORDER BY id DESC", estadoVisitaId);
    }

    @Override
    public List<Visita> listarPorFechaEntrada(LocalDateTime desde, LocalDateTime hasta) {
        validarPeriodo(desde, hasta);
        String sql = CONSULTA_VISITAS
                + " WHERE fecha_entrada >= ? AND fecha_entrada < ?"
                + " ORDER BY fecha_entrada DESC, id DESC";
        return consultarLista(sql, elevarAlSegundo(desde), elevarAlSegundo(hasta));
    }

    @Override
    public List<Visita> listarPorMovimientosEnPeriodo(LocalDateTime desde, LocalDateTime hasta) {
        validarPeriodo(desde, hasta);
        LocalDateTime inicio = elevarAlSegundo(desde);
        LocalDateTime fin = elevarAlSegundo(hasta);

        // Una visita aparece una sola vez aunque tenga varios movimientos dentro del período.
        String sql = CONSULTA_VISITAS + """
                 WHERE (fecha_entrada >= ? AND fecha_entrada < ?)
                    OR (fecha_salida >= ? AND fecha_salida < ?)
                    OR (fecha_cierre_sistema >= ? AND fecha_cierre_sistema < ?)
                 ORDER BY id DESC
                """;
        return consultarLista(sql, inicio, fin, inicio, fin, inicio, fin);
    }

    @Override
    public Optional<Visita> buscarIngresoAbiertoPorPersona(Integer personaId) {
        return buscarIngresoAbierto(personaId, false);
    }

    @Override
    public Optional<Visita> buscarIngresoAbiertoPorPersonaParaActualizar(Integer personaId) {
        // El caso de uso protege primero a la persona para coordinar sus ingresos.
        return buscarIngresoAbierto(personaId, true);
    }

    private Optional<Visita> buscarIngresoAbierto(Integer personaId, boolean proteger) {
        Objects.requireNonNull(personaId, "El identificador de la persona es obligatorio.");
        String sql = CONSULTA_VISITAS + " WHERE persona_id = ? AND " + INGRESO_ABIERTO
                + " ORDER BY fecha_entrada DESC, id DESC LIMIT 1";
        if (proteger) {
            sql += " FOR UPDATE";
        }
        return buscar(sql, proteger, personaId);
    }

    @Override
    public List<Visita> listarIngresosAbiertos() {
        return consultarLista(CONSULTA_VISITAS + " WHERE " + INGRESO_ABIERTO
                + " ORDER BY fecha_entrada, id");
    }

    @Override
    public List<Visita> listarIngresosAbiertosHasta(LocalDateTime fechaLimite) {
        Objects.requireNonNull(fechaLimite, "La fecha límite es obligatoria.");
        String sql = CONSULTA_VISITAS + " WHERE " + INGRESO_ABIERTO
                + " AND fecha_entrada <= ? ORDER BY fecha_entrada, id";
        return consultarLista(sql, sinFracciones(fechaLimite));
    }

    private List<Visita> consultarLista(String sql, Object... valores) {
        List<Visita> visitas = new ArrayList<>();

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            completarParametros(consulta, valores);

            try (ResultSet resultado = consulta.executeQuery()) {
                while (resultado.next()) {
                    visitas.add(leerVisita(resultado));
                }
            }
            return visitas;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudieron listar las visitas.", error);
        }
    }

    @Override
    public boolean actualizar(Visita visita) {
        Objects.requireNonNull(visita, "Los datos de la visita son obligatorios.");
        Objects.requireNonNull(visita.getId(), "El identificador de la visita es obligatorio.");

        // El caso de uso determina el estado y las fechas que corresponden al cambio.
        String sql = """
                UPDATE visitas
                SET persona_id = ?, empresa_destino_id = ?, funcionario_destinatario_id = ?,
                    tipo_ingreso = ?, fecha_programada = ?, fecha_aprobacion = ?, fecha_entrada = ?,
                    fecha_salida = ?, fecha_cierre_sistema = ?, estado_visita_id = ?,
                    vehiculo_placa = ?, aprobada_por_id = ?
                WHERE id = ?
                """;

        try (PreparedStatement consulta = conexion.prepareStatement(sql)) {
            completarDatos(consulta, visita);
            consulta.setInt(13, visita.getId());
            return consulta.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo actualizar la visita.", error);
        }
    }

    private void completarDatos(PreparedStatement consulta, Visita visita) throws SQLException {
        consulta.setInt(1, visita.getPersonaId());
        consulta.setInt(2, visita.getEmpresaDestinoId());
        consulta.setObject(3, visita.getFuncionarioDestinatarioId(), Types.INTEGER);
        consulta.setString(4, switch (visita.getTipoIngreso()) {
            case INVITADO_PREREGISTRADO -> "Invitado preregistrado";
            case INVITADO_NO_ANUNCIADO -> "Invitado no anunciado";
            case TRABAJADOR_CON_CARNET -> "Trabajador con carnet";
            case TRABAJADOR_CON_CARNET_OLVIDADO -> "Trabajador con carnet olvidado";
        });
        consulta.setObject(5, sinFracciones(visita.getFechaProgramada()), Types.TIMESTAMP);
        consulta.setObject(6, sinFracciones(visita.getFechaAprobacion()), Types.TIMESTAMP);
        consulta.setObject(7, sinFracciones(visita.getFechaEntrada()), Types.TIMESTAMP);
        consulta.setObject(8, sinFracciones(visita.getFechaSalida()), Types.TIMESTAMP);
        consulta.setObject(9, sinFracciones(visita.getFechaCierreSistema()), Types.TIMESTAMP);
        consulta.setInt(10, visita.getEstadoVisitaId());
        consulta.setString(11, visita.getVehiculoPlaca());
        consulta.setObject(12, visita.getAprobadaPorId(), Types.INTEGER);
    }

    private void completarParametros(PreparedStatement consulta, Object[] valores) throws SQLException {
        for (int i = 0; i < valores.length; i++) {
            consulta.setObject(i + 1, valores[i]);
        }
    }

    private Visita leerVisita(ResultSet resultado) throws SQLException {
        TipoIngreso tipo = switch (resultado.getString("tipo_ingreso")) {
            case "Invitado preregistrado" -> TipoIngreso.INVITADO_PREREGISTRADO;
            case "Invitado no anunciado" -> TipoIngreso.INVITADO_NO_ANUNCIADO;
            case "Trabajador con carnet" -> TipoIngreso.TRABAJADOR_CON_CARNET;
            case "Trabajador con carnet olvidado" -> TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO;
            default -> throw new SQLException("El tipo de ingreso guardado no es reconocido.");
        };

        return new Visita(
                resultado.getInt("id"), resultado.getInt("persona_id"),
                resultado.getInt("empresa_destino_id"),
                resultado.getObject("funcionario_destinatario_id", Integer.class), tipo,
                resultado.getObject("fecha_programada", LocalDateTime.class),
                resultado.getObject("fecha_aprobacion", LocalDateTime.class),
                resultado.getObject("fecha_entrada", LocalDateTime.class),
                resultado.getObject("fecha_salida", LocalDateTime.class),
                resultado.getObject("fecha_cierre_sistema", LocalDateTime.class),
                resultado.getInt("estado_visita_id"), resultado.getString("vehiculo_placa"),
                resultado.getObject("aprobada_por_id", Integer.class));
    }

    private void validarPeriodo(LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null || !hasta.isAfter(desde)) {
            throw new IllegalArgumentException(
                    "Debes indicar un período con fecha final posterior a la inicial.");
        }
    }

    private LocalDateTime sinFracciones(LocalDateTime fecha) {
        // El esquema guarda segundos: se descartan fracciones para evitar redondear al minuto siguiente.
        return fecha == null ? null : fecha.truncatedTo(ChronoUnit.SECONDS);
    }

    private LocalDateTime elevarAlSegundo(LocalDateTime fecha) {
        // Conserva los límites del período al comparar con fechas guardadas sin fracciones.
        return fecha.getNano() == 0 ? fecha : sinFracciones(fecha).plusSeconds(1);
    }
}
