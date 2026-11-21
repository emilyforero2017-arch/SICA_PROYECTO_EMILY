package com.sica.integration;

import com.sica.infrastructure.database.GestorTransaccionesJdbc;
import com.sica.infrastructure.security.ServicioContrasenasPbkdf2;
import com.sica.model.application.usecase.*;
import com.sica.model.domain.entity.*;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sica.pruebas.jdbc", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Recorridos: autenticación, sesión vigente, cierre y detención de avisos")
class SesionesIT {

    private static final String CLAVE = "SicaDemo2026!";
    private static final LocalDateTime FECHA = LocalDateTime.of(2026, 1, 15, 10, 20, 30);
    private static final Clock RELOJ = Clock.fixed(FECHA.atZone(ZoneId.of("America/Bogota")).toInstant(), ZoneId.of("America/Bogota"));
    private final ServicioContrasenasPbkdf2 contrasenas = new ServicioContrasenasPbkdf2();
    private final List<ContextoSesion> contextos = new ArrayList<>();
    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private IniciarSesionCase login;

    private final class Aplicacion {
        final ContextoSesion contexto = nuevoContexto();
        final EjecutorOperaciones ejecutor = new EjecutorOperaciones(gestor, contexto);
        final SesionUsuario sesion;
        Aplicacion(String correo) { sesion = iniciar(contexto, correo, CLAVE); }
        Integer id() { return sesion.getUsuarioId(); }
        void cerrar() { new CerrarSesionCase(contexto).cerrar(); }
    }

    @BeforeEach
    void preparar() throws Exception {
        base = BaseDatosTemporal.crear();
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        login = new IniciarSesionCase(gestor, contrasenas, RELOJ);
    }

    @AfterEach
    void limpiar(TestInfo prueba) throws SQLException {
        try {
            for (ContextoSesion contexto : contextos) new CerrarSesionCase(contexto).cerrar();
        } finally {
            if (base != null) base.close();
            System.out.println("Recorrido revisado: " + prueba.getDisplayName());
        }
    }

    @Test
    @DisplayName("Un login correcto conserva solo la identidad y confirma su auditoría antes de publicar la sesión")
    void iniciaConIdentidadSinSecretos() {
        Usuario original = cuenta("ana@sol.sica.local");
        ContextoSesion contexto = nuevoContexto();
        char[] clave = CLAVE.toCharArray();
        char[] copia = clave.clone();
        try {
            SesionUsuario sesion = login.iniciar(contexto, original.getEmail(), clave);
            assertSame(sesion, contexto.consultarActual().orElseThrow());
            assertEquals(original.getId(), contexto.requerirUsuarioId());
            assertEquals(original.getNombre(), sesion.getNombre());
            assertEquals(original.getEmail(), sesion.getEmail());
            assertEquals(original.getRolId(), sesion.getRolId());
            assertEquals(original.getEmpresaId(), sesion.getEmpresaId());
            assertEquals("Funcionario de Empresa", sesion.getNombreRol());
            assertEquals(FECHA, sesion.getFechaInicio());
            assertArrayEquals(copia, clave);
            Set<String> campos = Arrays.stream(SesionUsuario.class.getDeclaredFields()).map(java.lang.reflect.Field::getName)
                    .collect(Collectors.toSet());
            assertEquals(Set.of("usuarioId", "nombre", "email", "rolId", "nombreRol", "empresaId", "fechaInicio"), campos);
            BitacoraAuditoria auditoria = auditoriasLogin().get(0);
            assertEquals("LOGIN_EXITOSO", auditoria.getAccionRealizada());
            assertEquals(original.getId(), auditoria.getUsuarioId());
            assertEquals(original.getId(), auditoria.getRegistroIdAfectado());
            assertEquals(FECHA, auditoria.getFechaHora());
            assertSinSecretos(auditoria, original);
        } finally { Arrays.fill(clave, '\0'); Arrays.fill(copia, '\0'); }
    }

    @Test
    @DisplayName("Una contraseña incorrecta deja la sesión vacía y guarda el intento sin guardar esa contraseña")
    void rechazaContrasenaIncorrecta() {
        ContextoSesion contexto = nuevoContexto();
        Usuario original = cuenta("ana@sol.sica.local");
        char[] clave = "ClaveIncorrectaSoloPrueba!".toCharArray();
        char[] copia = clave.clone();
        try {
            assertThrows(SecurityException.class, () -> login.iniciar(contexto, original.getEmail(), clave));
            assertArrayEquals(copia, clave);
            assertSinSesion(contexto);
            BitacoraAuditoria registro = unicoFallo(original.getEmail());
            assertEquals(original.getId(), registro.getUsuarioId());
            assertEquals(original.getId(), registro.getRegistroIdAfectado());
            assertFalse(registro.getDetalles().contains(new String(clave)));
            assertSinSecretos(registro, original);
        } finally { Arrays.fill(clave, '\0'); Arrays.fill(copia, '\0'); }
    }

    @Test
    @DisplayName("Un correo desconocido no crea una cuenta y su intento queda sin atribuirlo a otra persona")
    void rechazaCorreoDesconocido() {
        String correo = UUID.randomUUID() + "@prueba.invalid";
        ContextoSesion contexto = nuevoContexto();
        int cantidad = gestor.ejecutar(r -> r.crearUsuarioRepository().listar()).size();
        assertThrows(SecurityException.class, () -> iniciar(contexto, correo, CLAVE));
        assertSinSesion(contexto);
        BitacoraAuditoria registro = unicoFallo(correo);
        assertNull(registro.getUsuarioId());
        assertNull(registro.getRegistroIdAfectado());
        assertFalse(registro.getDetalles().contains(CLAVE));
        assertEquals(cantidad, gestor.ejecutar(r -> r.crearUsuarioRepository().listar()).size());
    }

    @Test
    @DisplayName("Una cuenta desactivada no inicia sesión aunque presente la contraseña correcta")
    void rechazaCuentaDesactivada() {
        Usuario guarda = cuenta("guarda@sica.local");
        gestor.ejecutar(r -> r.crearUsuarioRepository().desactivar(guarda.getId()));
        ContextoSesion contexto = nuevoContexto();
        assertThrows(SecurityException.class, () -> iniciar(contexto, guarda.getEmail(), CLAVE));
        assertSinSesion(contexto);
        assertTrue(unicoFallo(guarda.getEmail()).getDetalles().contains("desactivada"));
        assertFalse(gestor.ejecutar(r -> r.crearUsuarioRepository().buscarPorId(guarda.getId()).orElseThrow()).isEstaActivo());
    }

    @Test
    @DisplayName("Una empresa desactivada impide el login y las operaciones de una sesión anterior del funcionario")
    void rechazaEmpresaDesactivada() throws SQLException {
        Aplicacion ana = new Aplicacion("ana@sol.sica.local");
        // Se aísla la comprobación de empresa manteniendo activa la cuenta en esta base desechable.
        ejecutarSql("UPDATE empresas SET esta_activa = 0 WHERE id = ?", ana.sesion.getEmpresaId());
        assertTrue(cuenta(ana.sesion.getEmail()).isEstaActivo());
        ContextoSesion otro = nuevoContexto();
        assertThrows(SecurityException.class, () -> iniciar(otro, ana.sesion.getEmail(), CLAVE));
        assertSinSesion(otro);
        assertTrue(unicoFallo(ana.sesion.getEmail()).getDetalles().contains("empresa"));
        assertThrows(IllegalStateException.class, () -> ana.ejecutor.ejecutar(ana.id(), "consultar_trabajadores_empresa", (r, u) -> u.getId()));
        assertDoesNotThrow(ana::cerrar);
        assertSinSesion(ana.contexto);
    }

    @Test
    @DisplayName("Una relación incompatible entre rol y empresa tampoco permite iniciar sesión")
    void rechazaRolYEmpresaIncompatibles() throws SQLException {
        Usuario ana = cuenta("ana@sol.sica.local");
        Usuario guarda = cuenta("guarda@sica.local");
        ejecutarSql("UPDATE usuarios SET empresa_id = NULL WHERE id = ?", ana.getId());
        ejecutarSql("UPDATE usuarios SET empresa_id = ? WHERE id = ?", ana.getEmpresaId(), guarda.getId());
        for (Usuario usuario : List.of(ana, guarda)) {
            ContextoSesion contexto = nuevoContexto();
            assertThrows(SecurityException.class, () -> iniciar(contexto, usuario.getEmail(), CLAVE));
            assertSinSesion(contexto);
            assertEquals(usuario.getId(), unicoFallo(usuario.getEmail()).getUsuarioId());
        }
        assertEquals(2, auditoriasLogin().size());
    }

    @Test
    @DisplayName("Los campos obligatorios se validan antes del intento y no crean sesiones ni auditorías falsas")
    void validaDatosObligatorios() {
        ContextoSesion contexto = nuevoContexto();
        for (String correo : Arrays.asList(null, "", "   ", "a".repeat(101))) {
            assertThrows(IllegalArgumentException.class, () -> iniciar(contexto, correo, CLAVE));
        }
        assertThrows(IllegalArgumentException.class, () -> login.iniciar(contexto, "ana@sol.sica.local", null));
        assertThrows(IllegalArgumentException.class, () -> login.iniciar(contexto, "ana@sol.sica.local", new char[0]));
        assertSinSesion(contexto);
        assertTrue(auditoriasLogin().isEmpty());
    }

    @Test
    @DisplayName("Una auditoría de login fallida impide publicar la sesión y registra el error en otra transacción")
    void noPublicaSesionSiFallaAuditoria() {
        ContextoSesion contexto = nuevoContexto();
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> iniciar(contexto, "ana@sol.sica.local", CLAVE));
        assertSinSesion(contexto);
        assertTrue(auditoriasLogin().stream().noneMatch(a -> "LOGIN_EXITOSO".equals(a.getAccionRealizada())));
        BitacoraAuditoria fallo = unicoFallo("ana@sol.sica.local");
        assertNull(fallo.getUsuarioId());
        assertEquals(2, acceso.conexionesDeAuditoria().stream().distinct().count());
        assertEquals(cuenta("ana@sol.sica.local").getId(), iniciar(contexto, "ana@sol.sica.local", CLAVE).getUsuarioId());
        assertEquals(1, auditoriasLogin().stream().filter(a -> "LOGIN_EXITOSO".equals(a.getAccionRealizada())).count());
    }

    @Test
    @DisplayName("Si también falla el registro del error, se conserva el error original y la sesión continúa vacía")
    void conservaAmbosFallosSinCrearSesion() {
        ContextoSesion contexto = nuevoContexto();
        acceso.fallarProximasAuditorias(2);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> iniciar(contexto, "ana@sol.sica.local", "ClaveIncorrectaSoloPrueba!"));
        assertEquals(1, error.getSuppressed().length);
        assertSinSesion(contexto);
        assertTrue(auditoriasLogin().isEmpty());
        assertEquals(2, acceso.conexionesDeAuditoria().stream().distinct().count());
        assertDoesNotThrow(() -> iniciar(contexto, "ana@sol.sica.local", CLAVE));
    }

    @Test
    @DisplayName("Una sesión abierta no se sustituye por otro login; tras cerrarla puede entrar otra cuenta")
    void exigeCerrarAntesDeCambiarCuenta() {
        Aplicacion ana = new Aplicacion("ana@sol.sica.local");
        int auditorias = auditoriasLogin().size();
        assertThrows(IllegalStateException.class, () -> iniciar(ana.contexto, "guarda@sica.local", CLAVE));
        assertSame(ana.sesion, ana.contexto.consultarActual().orElseThrow());
        assertEquals(auditorias, auditoriasLogin().size());
        ana.cerrar();
        SesionUsuario guarda = iniciar(ana.contexto, "guarda@sica.local", CLAVE);
        assertEquals(cuenta("guarda@sica.local").getId(), guarda.getUsuarioId());
        assertThrows(SecurityException.class, () -> ana.ejecutor.ejecutar(ana.id(), "consultar_trabajadores_empresa", (r, u) -> u.getId()));
    }

    @Test
    @DisplayName("Cerrar sesión retira la identidad y bloquea operaciones, sin desactivar la cuenta ni modificar su historial")
    void cierraSinDesactivarCuenta() {
        Aplicacion guarda = new Aplicacion("guarda@sica.local");
        Usuario antes = cuenta(guarda.sesion.getEmail());
        int auditorias = auditoriasLogin().size();
        guarda.cerrar();
        guarda.cerrar();
        assertSinSesion(guarda.contexto);
        assertThrows(SecurityException.class, () -> guarda.ejecutor.ejecutar(guarda.id(), "consultar_visita_porteria", (r, u) -> u.getId()));
        Usuario despues = cuenta(antes.getEmail());
        assertTrue(despues.isEstaActivo());
        assertEquals(antes.getRolId(), despues.getRolId());
        assertTrue(antes.getPasswordHash().equals(despues.getPasswordHash()), "Cerrar no cambia la contraseña.");
        assertEquals(auditorias, auditoriasLogin().size());
        assertEquals(guarda.id(), iniciar(guarda.contexto, antes.getEmail(), CLAVE).getUsuarioId());
    }

    @Test
    @DisplayName("El cierre detiene todos los seguimientos incluso si alguno falla y no los repite al cerrar otra vez")
    void cierraTodosLosSeguimientosAunqueFallen() throws Exception {
        Aplicacion guarda = new Aplicacion("guarda@sica.local");
        AtomicInteger cerrados = new AtomicInteger();
        AutoCloseable primero = guarda.contexto.registrarSeguimiento(guarda.id(), () -> {
            cerrados.incrementAndGet(); throw new IOException("Fallo de cierre provocado");
        });
        guarda.contexto.registrarSeguimiento(guarda.id(), () -> { cerrados.incrementAndGet(); });
        guarda.contexto.registrarSeguimiento(guarda.id(), () -> {
            cerrados.incrementAndGet(); throw new IllegalStateException("Otro fallo de cierre provocado");
        });
        IllegalStateException error = assertThrows(IllegalStateException.class, guarda::cerrar);
        assertEquals(2, error.getSuppressed().length);
        assertEquals(3, cerrados.get());
        assertSinSesion(guarda.contexto);
        assertDoesNotThrow(guarda::cerrar);
        primero.close();
        assertEquals(3, cerrados.get());
        AtomicInteger rechazados = new AtomicInteger();
        assertThrows(SecurityException.class, () -> guarda.contexto.registrarSeguimiento(guarda.id(), rechazados::incrementAndGet));
        assertEquals(1, rechazados.get());
        SesionUsuario nueva = iniciar(guarda.contexto, "ana@sol.sica.local", CLAVE);
        assertThrows(SecurityException.class, () -> guarda.contexto.registrarSeguimiento(guarda.id(), rechazados::incrementAndGet));
        assertEquals(2, rechazados.get());
        assertEquals(cuenta("ana@sol.sica.local").getId(), nueva.getUsuarioId());
    }

    @Test
    @DisplayName("Los avisos reales de funcionario y guarda dejan de entregarse al cerrar sus sesiones")
    void detieneAvisosDeAmbosRoles() throws Exception {
        Aplicacion marta = new Aplicacion("marta@luna.sica.local");
        Aplicacion guarda = new Aplicacion("guarda@sica.local");
        var pendientes = new LinkedBlockingQueue<AvisoSolicitudPendiente>();
        var decisiones = new LinkedBlockingQueue<AvisoSolicitud>();
        var errores = new CopyOnWriteArrayList<RuntimeException>();
        AvisosSolicitudCase avisosMarta = new AvisosSolicitudCase(marta.ejecutor);
        AvisosSolicitudCase avisosGuarda = new AvisosSolicitudCase(guarda.ejecutor);
        try (AutoCloseable a = avisosMarta.observarFuncionario(marta.id(), pendientes::add, errores::add);
             AutoCloseable b = avisosGuarda.observar(guarda.id(), decisiones::add, errores::add)) {
            Visita primera = solicitar(guarda, marta);
            assertNotNull(esperar(pendientes, aviso -> primera.getId().equals(aviso.getSolicitudId())));
            new SolicitudIngresoCase(marta.ejecutor, RELOJ).aprobar(marta.id(), primera.getId());
            assertNotNull(esperar(decisiones, aviso -> primera.getId().equals(aviso.getSolicitudId())));
            Visita segunda = solicitar(guarda, marta);
            marta.cerrar();
            guarda.cerrar();
            pendientes.clear();
            decisiones.clear();
            Aplicacion otraMarta = new Aplicacion("marta@luna.sica.local");
            Aplicacion otroGuarda = new Aplicacion("guarda@sica.local");
            new SolicitudIngresoCase(otraMarta.ejecutor, RELOJ).aprobar(otraMarta.id(), segunda.getId());
            Visita tercera = solicitar(otroGuarda, otraMarta);
            // Hay nuevas solicitudes y decisiones en MySQL, pero los receptores anteriores ya no deben recibirlas.
            assertTrue(new AvisosSolicitudCase(otroGuarda.ejecutor).consultarAvisos(otroGuarda.id(), Set.of())
                    .stream().anyMatch(aviso -> segunda.getId().equals(aviso.getSolicitudId())));
            assertTrue(new AvisosSolicitudCase(otraMarta.ejecutor).consultarAvisosFuncionario(otraMarta.id(), Set.of())
                    .stream().anyMatch(aviso -> tercera.getId().equals(aviso.getSolicitudId())));
            assertNull(pendientes.poll(2200, TimeUnit.MILLISECONDS));
            assertNull(decisiones.poll(2200, TimeUnit.MILLISECONDS));
            assertTrue(errores.isEmpty(), "Los avisos se deben detener sin comunicar errores a una sesión cerrada.");
        }
    }

    @Test
    @DisplayName("Cerrar una aplicación no cierra otra, y una sesión no puede ejecutar acciones con la identidad de otra cuenta")
    void separaSesionesYEvitaSuplantarIdentidades() {
        Aplicacion primera = new Aplicacion("guarda@sica.local");
        Aplicacion segunda = new Aplicacion("guarda@sica.local");
        Integer admin = cuenta("admin@sica.local").getId();
        assertThrows(SecurityException.class, () -> primera.ejecutor.ejecutar(admin, "consultar_usuarios", (r, u) -> u.getId()));
        primera.cerrar();
        assertSinSesion(primera.contexto);
        assertEquals(segunda.id(), segunda.ejecutor.ejecutar(segunda.id(), "consultar_visita_porteria", (r, u) -> u.getId()));
        assertTrue(segunda.contexto.consultarActual().isPresent());
    }

    @Test
    @DisplayName("Dos logins simultáneos sobre un mismo contexto publican una sola sesión sin reemplazarla")
    void coordinaDosLoginsEnUnaAplicacion() throws Exception {
        ContextoSesion contexto = nuevoContexto();
        CountDownLatch inicio = new CountDownLatch(1);
        var hilos = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Boolean>> resultados = new ArrayList<>();
            for (String correo : List.of("guarda@sica.local", "ana@sol.sica.local")) resultados.add(hilos.submit(() -> {
                if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("No comenzó la prueba.");
                try { iniciar(contexto, correo, CLAVE); return true; }
                catch (IllegalStateException error) {
                    if (!"Debes cerrar la sesión actual antes de iniciar otra.".equals(error.getMessage())) throw error;
                    return false;
                }
            }));
            inicio.countDown();
            int sesiones = 0;
            for (var resultado : resultados) if (resultado.get(30, TimeUnit.SECONDS)) sesiones++;
            assertEquals(1, sesiones);
        } finally {
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(25, TimeUnit.SECONDS));
        }
        assertEquals(1, auditoriasLogin().size());
        assertEquals(contexto.requerirUsuarioId(), auditoriasLogin().get(0).getUsuarioId());
        assertEquals("LOGIN_EXITOSO", auditoriasLogin().get(0).getAccionRealizada());
    }

    @Test
    @DisplayName("Una operación ya iniciada termina antes del cierre y las siguientes quedan bloqueadas")
    void coordinaUnaOperacionConElCierre() throws Exception {
        Aplicacion guarda = new Aplicacion("guarda@sica.local");
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch continuar = new CountDownLatch(1);
        CountDownLatch cierreSolicitado = new CountDownLatch(1);
        List<String> orden = new CopyOnWriteArrayList<>();
        guarda.contexto.registrarSeguimiento(guarda.id(), () -> { orden.add("cierre"); });
        var hilos = Executors.newFixedThreadPool(2);
        try {
            var operacion = hilos.submit(() -> guarda.ejecutor.ejecutar(guarda.id(), "consultar_visita_porteria", (r, u) -> {
                dentro.countDown();
                try {
                    if (!continuar.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("No continuó la operación.");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                Integer id = r.crearUsuarioRepository().buscarPorId(u.getId()).orElseThrow().getId();
                orden.add("operacion");
                return id;
            }));
            assertTrue(dentro.await(20, TimeUnit.SECONDS));
            var cierre = hilos.submit(() -> { cierreSolicitado.countDown(); guarda.cerrar(); });
            assertTrue(cierreSolicitado.await(5, TimeUnit.SECONDS));
            continuar.countDown();
            assertEquals(guarda.id(), operacion.get(30, TimeUnit.SECONDS));
            cierre.get(30, TimeUnit.SECONDS);
        } finally {
            continuar.countDown();
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(25, TimeUnit.SECONDS));
        }
        assertEquals(List.of("operacion", "cierre"), orden);
        assertSinSesion(guarda.contexto);
        assertThrows(SecurityException.class, () -> guarda.ejecutor.ejecutar(guarda.id(), "consultar_visita_porteria", (r, u) -> u.getId()));
    }

    private ContextoSesion nuevoContexto() {
        ContextoSesion contexto = new ContextoSesion();
        contextos.add(contexto);
        return contexto;
    }

    private SesionUsuario iniciar(ContextoSesion contexto, String correo, String texto) {
        char[] clave = texto.toCharArray();
        try { return login.iniciar(contexto, correo, clave); }
        finally { Arrays.fill(clave, '\0'); }
    }

    private Usuario cuenta(String correo) {
        return gestor.ejecutar(r -> r.crearUsuarioRepository().buscarPorEmail(correo).orElseThrow());
    }

    private void assertSinSesion(ContextoSesion contexto) {
        assertTrue(contexto.consultarActual().isEmpty());
        assertThrows(SecurityException.class, contexto::requerirUsuarioId);
    }

    private List<BitacoraAuditoria> auditoriasLogin() {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorPeriodo(FECHA, FECHA.plusSeconds(1)))
                .stream().filter(a -> a.getAccionRealizada().startsWith("LOGIN_")).toList();
    }

    private BitacoraAuditoria unicoFallo(String correo) {
        List<BitacoraAuditoria> fallos = auditoriasLogin().stream()
                .filter(a -> "LOGIN_FALLIDO".equals(a.getAccionRealizada()) && a.getDetalles().contains(correo)).toList();
        assertEquals(1, fallos.size());
        return fallos.get(0);
    }

    private void assertSinSecretos(BitacoraAuditoria auditoria, Usuario cuenta) {
        assertFalse(auditoria.getDetalles().contains(CLAVE));
        assertFalse(auditoria.getDetalles().contains(cuenta.getPasswordHash()));
    }

    private void ejecutarSql(String sql, Integer... valores) throws SQLException {
        try (var conexion = acceso.abrirConexion(); var consulta = conexion.prepareStatement(sql)) {
            for (int i = 0; i < valores.length; i++) consulta.setInt(i + 1, valores[i]);
            assertEquals(1, consulta.executeUpdate());
        }
    }

    private Visita solicitar(Aplicacion guarda, Aplicacion funcionaria) {
        return new SolicitudIngresoCase(guarda.ejecutor, RELOJ).solicitarSinAgenda(guarda.id(),
                funcionaria.sesion.getEmpresaId(), funcionaria.id(),
                "SES-" + UUID.randomUUID().toString().substring(0, 12), "Invitada de prueba", "foto-sesion.png", null);
    }

    private <T> T esperar(LinkedBlockingQueue<T> cola, Predicate<T> corresponde) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < limite) {
            T aviso = cola.poll(Math.max(1, limite - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (aviso == null || corresponde.test(aviso)) return aviso;
        }
        return null;
    }
}
