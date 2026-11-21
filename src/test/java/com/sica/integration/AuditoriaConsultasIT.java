package com.sica.integration;

import com.sica.infrastructure.database.GestorTransaccionesJdbc;
import com.sica.infrastructure.security.ServicioContrasenasPbkdf2;
import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.application.usecase.*;
import com.sica.model.domain.entity.*;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sica.pruebas.jdbc", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Recorridos: auditoría general y actividad propia del funcionario")
class AuditoriaConsultasIT {

    private static final ZoneId ZONA = ZoneId.of("America/Bogota");
    private static final LocalDateTime DIA = LocalDateTime.of(2026, 1, 12, 10, 0);
    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private RelojAuditoria reloj;
    private final List<ContextoSesion> sesiones = new ArrayList<>();
    private Aplicacion supervisor;

    private final class Aplicacion implements AutoCloseable {
        final ContextoSesion contexto = new ContextoSesion();
        final EjecutorOperaciones ejecutor = new EjecutorOperaciones(gestor, contexto);
        final AuditoriaCase auditoria = new AuditoriaCase(ejecutor);
        final SesionUsuario sesion;

        Aplicacion(String correo) {
            sesiones.add(contexto);
            char[] clave = "SicaDemo2026!".toCharArray();
            try {
                sesion = new IniciarSesionCase(gestor, new ServicioContrasenasPbkdf2()).iniciar(contexto, correo, clave);
            } finally { Arrays.fill(clave, '\0'); }
        }

        Integer id() { return sesion.getUsuarioId(); }
        @Override public void close() { new CerrarSesionCase(contexto).cerrar(); }
    }

    private static final class RelojAuditoria extends Clock {
        private volatile Instant actual;
        void marcar(LocalDateTime hora) { actual = hora.atZone(ZONA).toInstant(); }
        @Override public ZoneId getZone() { return ZONA; }
        @Override public Clock withZone(ZoneId zona) { return Clock.fixed(actual, zona); }
        @Override public Instant instant() { return actual; }
    }

    @BeforeEach
    void preparar() throws Exception {
        base = BaseDatosTemporal.crear();
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        reloj = new RelojAuditoria();
        reloj.marcar(DIA);
        supervisor = ingresar("supervisor@sica.local");
    }

    @AfterEach
    void limpiar(TestInfo prueba) throws SQLException {
        try {
            for (ContextoSesion contexto : sesiones) new CerrarSesionCase(contexto).cerrar();
        } finally {
            if (base != null) base.close();
            System.out.println("Recorrido revisado: " + prueba.getDisplayName());
        }
    }

    @Test
    @DisplayName("Ana consulta el registro, la entrada y la salida de su visita sin duplicar su preregistro")
    void reuneLaActividadRealDeUnaVisita() {
        Aplicacion ana = ingresar("ana@sol.sica.local");
        Aplicacion guarda = ingresar("guarda@sica.local");
        Aplicacion admin = ingresar("admin@sica.local");
        Visita visita = agendar(ana);
        reloj.marcar(DIA.plusMinutes(30));
        new ControlAccesoProgramadoCase(guarda.ejecutor, reloj).registrarEntrada(
                guarda.id(), documento(visita), visita.getId());
        reloj.marcar(DIA.plusHours(2));
        new ControlSalidaCase(guarda.ejecutor, reloj).registrarSalida(guarda.id(), documento(visita), visita.getId());
        List<DetalleAuditoria> propia = ana.auditoria.consultarPropia(ana.id(), DIA, DIA.plusHours(3));
        assertEquals(List.of("REGISTRO_SALIDA", "REGISTRO_ENTRADA", "PREREGISTRO_VISITA", "REGISTRO_INVITADO"),
                propia.stream().map(d -> d.getRegistro().getAccionRealizada()).toList());
        assertEquals(List.of(guarda.id(), guarda.id(), ana.id(), ana.id()),
                propia.stream().map(d -> d.getRegistro().getUsuarioId()).toList());
        assertEquals(4, ids(propia).size());
        assertEquals(guarda.sesion.getNombre(), propia.get(0).getNombreResponsable());
        assertEquals(ana.sesion.getNombre(), propia.get(2).getNombreResponsable());
        assertEquals(ids(propia), ids(general(DIA, DIA.plusHours(3))));
        assertEquals(ids(propia), ids(admin.auditoria.consultarGeneral(admin.id(), DIA, DIA.plusHours(3))));
    }

    @Test
    @DisplayName("La general incluye éxitos, fallos y acciones sin usuario, ordenados por fecha e identificador")
    void consultaLaGeneralConResponsablesYRegistrosDelSistema() {
        Usuario ana = usuario("ana@sol.sica.local");
        Usuario guarda = usuario("guarda@sica.local");
        List<BitacoraAuditoria> registros = List.of(
                guardar(ana.getId(), DIA, "ACCION_CONFIRMADA", "usuarios", ana.getId(), "Acción de Ana."),
                guardar(guarda.getId(), DIA.plusMinutes(1), "ACTIVIDAD_PORTERIA", "usuarios", guarda.getId(), "Acción del guarda."),
                guardar(supervisor.id(), DIA.plusMinutes(1), "REVISION_SEGURIDAD", "usuarios", supervisor.id(), "Acción del supervisor."),
                guardar(null, DIA.plusMinutes(2), "CIERRE_SALIDA_NO_REGISTRADA", null, null, "Acción automática del sistema."),
                guardar(null, DIA.plusMinutes(2), "LOGIN_FALLIDO", "usuarios", null, "Intento sin una cuenta identificada."),
                guardar(ana.getId(), DIA.plusMinutes(3), "PREREGISTRO_VISITA_FALLIDO", "visitas", null, "Intento fallido, sin registro confirmado."));
        List<Long> orden = new ArrayList<>(registros.stream().map(BitacoraAuditoria::getId).toList());
        Collections.reverse(orden);
        List<DetalleAuditoria> consulta = general(DIA, DIA.plusHours(1));
        assertEquals(orden, consulta.stream().map(d -> d.getRegistro().getId()).toList());
        assertEquals("Sin usuario asociado", consulta.get(1).getNombreResponsable());
        assertEquals("Sin usuario asociado", consulta.get(2).getNombreResponsable());
        assertNull(consulta.get(0).getRegistro().getRegistroIdAfectado());
        assertEquals("PREREGISTRO_VISITA_FALLIDO", consulta.get(0).getRegistro().getAccionRealizada());
        assertEquals(supervisor.sesion.getNombre(), consulta.get(3).getNombreResponsable());
    }

    @Test
    @DisplayName("La propia excluye otros funcionarios, otras empresas y tablas con identificadores coincidentes")
    void limitaLaAuditoriaPropiaALaActividadQueCorresponde() {
        Aplicacion ana = ingresar("ana@sol.sica.local");
        Usuario anfitriona = usuario("ana@sol.sica.local");
        Usuario marta = usuario("marta@luna.sica.local");
        Usuario guarda = usuario("guarda@sica.local");
        Usuario admin = usuario("admin@sica.local");
        Usuario beatriz = clonarCuenta(anfitriona, "Beatriz", anfitriona.getEmpresaId());
        Visita propia = prepararVisita(anfitriona, null, DIA);
        Visita deBeatriz = prepararVisita(beatriz, null, DIA);
        Persona invitada = persona(propia);
        Visita deMarta = prepararVisita(marta, invitada, DIA.plusDays(2));
        BitacoraAuditoria identidad = guardar(ana.id(), DIA, "REGISTRO_INVITADO", "personas", invitada.getId(), "Identidad registrada por Ana.");
        BitacoraAuditoria aprobacion = guardar(ana.id(), DIA, "PREREGISTRO_VISITA", "visitas", propia.getId(), "Visita registrada por Ana.");
        BitacoraAuditoria entrada = guardar(guarda.getId(), DIA.plusMinutes(1), "REGISTRO_ENTRADA", "visitas", propia.getId(), "Entrada de la visita de Ana.");
        BitacoraAuditoria cierre = guardar(null, DIA.plusDays(1).plusMinutes(1), "CIERRE_SALIDA_NO_REGISTRADA", "visitas", propia.getId(), "Cierre de la visita de Ana.");
        List<BitacoraAuditoria> ajenas = new ArrayList<>();
        ajenas.add(guardar(beatriz.getId(), DIA, "PREREGISTRO_VISITA", "visitas", deBeatriz.getId(), "Otro funcionario de la misma empresa."));
        ajenas.add(guardar(guarda.getId(), DIA.plusMinutes(1), "REGISTRO_ENTRADA", "visitas", deBeatriz.getId(), "Entrada de la visita de Beatriz."));
        ajenas.add(guardar(marta.getId(), DIA.plusDays(2), "PREREGISTRO_VISITA", "visitas", deMarta.getId(), "La misma invitada visita otra empresa."));
        ajenas.add(guardar(guarda.getId(), DIA.plusDays(2).plusMinutes(1), "REGISTRO_ENTRADA", "visitas", deMarta.getId(), "Entrada correspondiente a Marta."));
        // Los datos iniciales contienen una visita de Ana y una cuenta con el mismo número.
        assertTrue(gestor.ejecutar(r -> r.crearVisitaRepository().listarPorFuncionarioDestinatario(ana.id()))
                .stream().anyMatch(v -> v.getId().equals(admin.getId())));
        ajenas.add(guardar(admin.getId(), DIA, "ADMINISTRACION_USUARIO", "usuarios", admin.getId(), "Es una cuenta, no la visita con igual número."));
        Incidente incidente = new IncidenteCase(supervisor.ejecutor, reloj(DIA.plusDays(1).plusMinutes(2))).registrar(
                supervisor.id(), propia.getId(), DIA.plusMinutes(30), "Situación revisada únicamente por seguridad.");
        BitacoraAuditoria auditoriaIncidente = gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository()
                .listarPorRegistro("incidentes", incidente.getId())).get(0);
        List<DetalleAuditoria> visible = ana.auditoria.consultarPropia(ana.id(), DIA, DIA.plusDays(5));
        assertEquals(Set.of(identidad.getId(), aprobacion.getId(), entrada.getId(), cierre.getId()), ids(visible));
        assertEquals(4, visible.size());
        Set<Long> general = ids(general(DIA, DIA.plusDays(5)));
        for (BitacoraAuditoria ajena : ajenas) {
            assertFalse(ids(visible).contains(ajena.getId()));
            assertTrue(general.contains(ajena.getId()));
        }
        assertFalse(ids(visible).contains(auditoriaIncidente.getId()));
        assertTrue(general.contains(auditoriaIncidente.getId()));
    }

    @Test
    @DisplayName("Las fechas incluyen el inicio y excluyen el final, incluso al consultar fracciones de segundo")
    void respetaLosLimitesDelPeriodo() {
        Aplicacion ana = ingresar("ana@sol.sica.local");
        guardar(ana.id(), DIA.minusSeconds(1), "ANTES", null, null, "Antes del período.");
        BitacoraAuditoria inicio = guardar(ana.id(), DIA, "INICIO", null, null, "Inicio incluido.");
        BitacoraAuditoria siguiente = guardar(ana.id(), DIA.plusSeconds(1), "DENTRO", null, null, "Segundo incluido.");
        guardar(ana.id(), DIA.plusHours(1), "FINAL", null, null, "Final excluido.");
        assertEquals(Set.of(inicio.getId(), siguiente.getId()), ids(general(DIA, DIA.plusHours(1))));
        assertEquals(Set.of(inicio.getId(), siguiente.getId()), ids(ana.auditoria.consultarPropia(ana.id(), DIA, DIA.plusHours(1))));
        assertEquals(Set.of(siguiente.getId()), ids(general(DIA.plusNanos(1), DIA.plusSeconds(1).plusNanos(1))));
        assertEquals(Set.of(siguiente.getId()), ids(ana.auditoria.consultarPropia(ana.id(), DIA.plusNanos(1), DIA.plusSeconds(1).plusNanos(1))));
        assertEquals(Set.of(inicio.getId()), ids(general(DIA, DIA.plusSeconds(1))));
    }

    @Test
    @DisplayName("Devuelve listas vacías cuando corresponde y rechaza períodos incompletos o invertidos")
    void validaPeriodosSinCrearRegistros() {
        Aplicacion ana = ingresar("ana@sol.sica.local");
        assertTrue(general(DIA, DIA.plusDays(1)).isEmpty());
        assertTrue(ana.auditoria.consultarPropia(ana.id(), DIA, DIA.plusDays(1)).isEmpty());
        for (LocalDateTime[] periodo : List.of(new LocalDateTime[]{null, DIA}, new LocalDateTime[]{DIA, null},
                new LocalDateTime[]{DIA, DIA}, new LocalDateTime[]{DIA, DIA.minusDays(1)})) {
            assertThrows(IllegalArgumentException.class, () -> supervisor.auditoria.consultarGeneral(supervisor.id(), periodo[0], periodo[1]));
            assertThrows(IllegalArgumentException.class, () -> ana.auditoria.consultarPropia(ana.id(), periodo[0], periodo[1]));
        }
        assertTrue(general(DIA, DIA.plusDays(1)).isEmpty());
    }

    @Test
    @DisplayName("La general y la propia exigen sus permisos y una sesión vigente")
    void compruebaPermisosYSesion() {
        Aplicacion ana = ingresar("ana@sol.sica.local");
        Aplicacion guarda = ingresar("guarda@sica.local");
        Aplicacion admin = ingresar("admin@sica.local");
        for (Aplicacion sinGeneral : List.of(ana, guarda)) {
            assertThrows(SecurityException.class, () -> sinGeneral.auditoria.consultarGeneral(sinGeneral.id(), DIA, DIA.plusDays(1)));
        }
        for (Aplicacion sinPropia : List.of(supervisor, guarda, admin)) {
            assertThrows(SecurityException.class, () -> sinPropia.auditoria.consultarPropia(sinPropia.id(), DIA, DIA.plusDays(1)));
        }
        AuditoriaCase sinSesion = new AuditoriaCase(new EjecutorOperaciones(gestor, new ContextoSesion()));
        assertThrows(SecurityException.class, () -> sinSesion.consultarGeneral(supervisor.id(), DIA, DIA.plusDays(1)));
        ana.close();
        assertThrows(SecurityException.class, () -> ana.auditoria.consultarPropia(ana.id(), DIA, DIA.plusDays(1)));
    }

    @Test
    @DisplayName("El historial conserva los nombres de cuentas inactivas y de personas de empresas retiradas")
    void conservaLaAutoriaAunqueSeDesactivenLasCuentas() {
        Aplicacion ana = ingresar("ana@sol.sica.local");
        Usuario autor = clonarCuenta(usuario("supervisor@sica.local"), "Supervisor anterior", null);
        Aplicacion sesionAutor = ingresar(autor.getEmail());
        BitacoraAuditoria deAna = guardar(ana.id(), DIA, "ACCION_ANTERIOR", "usuarios", ana.id(), "Actividad anterior de Ana.");
        BitacoraAuditoria delSupervisor = guardar(autor.getId(), DIA.plusMinutes(1), "REVISION_ANTERIOR", "usuarios", autor.getId(), "Actividad del supervisor anterior.");
        Aplicacion admin = ingresar("admin@sica.local");
        new UsuarioCase(admin.ejecutor, new ServicioContrasenasPbkdf2()).desactivar(admin.id(), autor.getId(), "Terminó su función");
        new EmpresaUseCase(admin.ejecutor).desactivar(admin.id(), ana.sesion.getEmpresaId(), "Empresa retirada");
        assertThrows(IllegalStateException.class, () -> sesionAutor.auditoria.consultarGeneral(sesionAutor.id(), DIA, DIA.plusDays(1)));
        assertThrows(IllegalStateException.class, () -> ana.auditoria.consultarPropia(ana.id(), DIA, DIA.plusDays(1)));
        List<DetalleAuditoria> consulta = general(DIA, DIA.plusDays(1));
        assertEquals(List.of(delSupervisor.getId(), deAna.getId()), consulta.stream().map(d -> d.getRegistro().getId()).toList());
        assertEquals(List.of("Supervisor anterior", "Ana Gómez"), consulta.stream().map(DetalleAuditoria::getNombreResponsable).toList());
    }

    @Test
    @DisplayName("Consultar la auditoría no añade auditorías nuevas ni altera las anteriores")
    void consultarNoSeAuditaASiMismo() {
        Aplicacion ana = ingresar("ana@sol.sica.local");
        BitacoraAuditoria anterior = guardar(ana.id(), DIA, "ACCION_CONFIRMADA", null, null, "Texto que debe conservarse.");
        acceso.fallarProximasAuditorias(1);
        List<DetalleAuditoria> general = general(DIA, DIA.plusDays(1));
        List<DetalleAuditoria> propia = ana.auditoria.consultarPropia(ana.id(), DIA, DIA.plusDays(1));
        assertEquals(Set.of(anterior.getId()), ids(general));
        assertEquals(ids(general), ids(propia));
        assertEquals(anterior.getDetalles(), propia.get(0).getRegistro().getDetalles());
        assertTrue(acceso.conexionesDeAuditoria().isEmpty());
    }

    @Test
    @DisplayName("MySQL impide modificar o borrar una fila de auditoría ya confirmada")
    void protegeLaAuditoriaContraCambiosYBorrados() throws SQLException {
        BitacoraAuditoria original = guardar(supervisor.id(), DIA, "REGISTRO_PROTEGIDO", null, null, "Historial que no debe alterarse.");
        // Estas instrucciones se intentan únicamente en la base temporal para comprobar las protecciones del esquema.
        try (var conexion = acceso.abrirConexion()) {
            conexion.setAutoCommit(false);
            try (var actualizar = conexion.prepareStatement("UPDATE bitacora_auditoria SET detalles = ? WHERE id = ?")) {
                actualizar.setString(1, "Alteración de prueba.");
                actualizar.setLong(2, original.getId());
                SQLException error = assertThrows(SQLException.class, actualizar::executeUpdate);
                assertEquals("45000", error.getSQLState());
            }
            conexion.rollback();
            try (var borrar = conexion.prepareStatement("DELETE FROM bitacora_auditoria WHERE id = ?")) {
                borrar.setLong(1, original.getId());
                SQLException error = assertThrows(SQLException.class, borrar::executeUpdate);
                assertEquals("45000", error.getSQLState());
            }
            conexion.rollback();
        }
        List<DetalleAuditoria> consulta = general(DIA, DIA.plusDays(1));
        assertEquals(Set.of(original.getId()), ids(consulta));
        assertEquals(original.getDetalles(), consulta.get(0).getRegistro().getDetalles());
    }

    @Test
    @DisplayName("Crear una cuenta e intentar iniciar sesión no expone la contraseña escrita ni el hash guardado")
    void noExponeCredencialesEnLosResultados() {
        LocalDateTime desde = LocalDateTime.now(ZONA).minusSeconds(2);
        Aplicacion admin = ingresar("admin@sica.local");
        Usuario modelo = usuario("ana@sol.sica.local");
        String claveTexto = "ClaveSoloPrueba2026!";
        String equivocadaTexto = "OtraClaveSoloPrueba!";
        char[] clave = claveTexto.toCharArray();
        Usuario nuevo;
        try {
            nuevo = new UsuarioCase(admin.ejecutor, new ServicioContrasenasPbkdf2()).registrar(
                    admin.id(), "Cuenta de prueba", UUID.randomUUID() + "@prueba.invalid", clave,
                    modelo.getRolId(), modelo.getEmpresaId());
            ContextoSesion correcta = new ContextoSesion();
            sesiones.add(correcta);
            new IniciarSesionCase(gestor, new ServicioContrasenasPbkdf2()).iniciar(correcta, nuevo.getEmail(), clave);
        } finally { Arrays.fill(clave, '\0'); }
        char[] equivocada = equivocadaTexto.toCharArray();
        try {
            ContextoSesion fallida = new ContextoSesion();
            sesiones.add(fallida);
            assertThrows(SecurityException.class, () -> new IniciarSesionCase(gestor, new ServicioContrasenasPbkdf2())
                    .iniciar(fallida, nuevo.getEmail(), equivocada));
            assertTrue(fallida.consultarActual().isEmpty());
        } finally { Arrays.fill(equivocada, '\0'); }
        List<DetalleAuditoria> consulta = general(desde, LocalDateTime.now(ZONA).plusMinutes(1));
        Set<String> acciones = consulta.stream().map(DetalleAuditoria::getRegistro)
                .filter(r -> "usuarios".equals(r.getTablaAfectada()) && nuevo.getId().equals(r.getRegistroIdAfectado()))
                .map(BitacoraAuditoria::getAccionRealizada).collect(Collectors.toSet());
        assertTrue(acciones.containsAll(Set.of("CREACION_USUARIO", "LOGIN_EXITOSO", "LOGIN_FALLIDO")));
        assertNotEquals(claveTexto, nuevo.getPasswordHash());
        for (DetalleAuditoria detalle : consulta) {
            String texto = detalle.getNombreResponsable() + " " + detalle.getRegistro().getDetalles();
            assertFalse(texto.contains(claveTexto));
            assertFalse(texto.contains(equivocadaTexto));
            assertFalse(texto.contains(nuevo.getPasswordHash()));
        }
    }

    @Test
    @DisplayName("Ana ve un intento fallido sobre su visita, sin que aparezca una entrada exitosa inexistente")
    void muestraElFalloRealSinConfundirloConUnIngreso() {
        Aplicacion ana = ingresar("ana@sol.sica.local");
        Aplicacion guarda = ingresar("guarda@sica.local");
        Visita visita = agendar(ana);
        reloj.marcar(DIA.plusMinutes(30));
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> new ControlAccesoProgramadoCase(guarda.ejecutor, reloj)
                .registrarEntrada(guarda.id(), documento(visita), visita.getId()));
        BitacoraAuditoria fallo = gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorRegistro("visitas", visita.getId()))
                .stream().filter(a -> "REGISTRO_ENTRADA_FALLIDO".equals(a.getAccionRealizada())).findFirst().orElseThrow();
        LocalDateTime desde = fallo.getFechaHora().minusSeconds(1);
        LocalDateTime hasta = fallo.getFechaHora().plusSeconds(1);
        assertTrue(ids(general(desde, hasta)).contains(fallo.getId()));
        List<DetalleAuditoria> propia = ana.auditoria.consultarPropia(ana.id(), desde, hasta);
        assertTrue(ids(propia).contains(fallo.getId()));
        assertTrue(propia.stream().noneMatch(d -> visita.getId().equals(d.getRegistro().getRegistroIdAfectado())
                && "visitas".equals(d.getRegistro().getTablaAfectada()) && "REGISTRO_ENTRADA".equals(d.getRegistro().getAccionRealizada())));
        gestor.ejecutar(r -> {
            assertNull(r.crearVisitaRepository().buscarPorId(visita.getId()).orElseThrow().getFechaEntrada());
            return null;
        });
    }

    private Aplicacion ingresar(String correo) { return new Aplicacion(correo); }

    private Usuario usuario(String correo) {
        return gestor.ejecutar(r -> r.crearUsuarioRepository().buscarPorEmail(correo).orElseThrow());
    }

    private Usuario clonarCuenta(Usuario modelo, String nombre, Integer empresa) {
        return gestor.ejecutar(r -> r.crearUsuarioRepository().guardar(new Usuario(nombre, UUID.randomUUID() + "@prueba.invalid",
                modelo.getPasswordHash(), modelo.getRolId(), empresa)));
    }

    private Visita agendar(Aplicacion ana) {
        return new VisitaCase(ana.ejecutor, reloj).preregistrar(ana.id(), documentoNuevo(), "Laura prueba", "foto-prueba.png", DIA.plusHours(1), null);
    }

    private Visita prepararVisita(Usuario anfitrion, Persona reutilizada, LocalDateTime inicio) {
        return gestor.ejecutar(r -> {
            Persona persona = reutilizada == null ? r.crearPersonaRepository().guardar(new Persona("Laura prueba", documentoNuevo(), null,
                    TipoPersona.INVITADO, r.crearEstadoAccesoPersonaRepository().buscarPorNombre("Activo").orElseThrow().getId(),
                    "foto-prueba.png", null)) : reutilizada;
            return r.crearVisitaRepository().guardar(new Visita(persona.getId(), anfitrion.getEmpresaId(), anfitrion.getId(),
                    TipoIngreso.INVITADO_PREREGISTRADO, inicio.plusHours(1), inicio, inicio.plusMinutes(1), null,
                    inicio.plusDays(1).plusMinutes(1),
                    r.crearEstadoVisitaRepository().buscarPorNombre("Cerrada por Sistema - Salida no registrada").orElseThrow().getId(),
                    null, anfitrion.getId()));
        });
    }

    private BitacoraAuditoria guardar(Integer actor, LocalDateTime fecha, String accion, String tabla, Integer id, String detalles) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(actor, fecha, accion, tabla, id, detalles)));
    }

    private List<DetalleAuditoria> general(LocalDateTime desde, LocalDateTime hasta) {
        return supervisor.auditoria.consultarGeneral(supervisor.id(), desde, hasta);
    }

    private Set<Long> ids(List<DetalleAuditoria> registros) {
        return registros.stream().map(d -> d.getRegistro().getId()).collect(Collectors.toSet());
    }

    private Persona persona(Visita visita) {
        return gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorId(visita.getPersonaId()).orElseThrow());
    }

    private String documento(Visita visita) { return persona(visita).getDocumentoIdentidad(); }
    private String documentoNuevo() { return "AU" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    private Clock reloj(LocalDateTime hora) { return Clock.fixed(hora.atZone(ZONA).toInstant(), ZONA); }
}
