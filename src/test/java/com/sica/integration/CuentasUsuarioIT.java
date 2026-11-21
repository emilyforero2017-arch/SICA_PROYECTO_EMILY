package com.sica.integration;

import com.sica.infrastructure.database.GestorTransaccionesJdbc;
import com.sica.infrastructure.security.ServicioContrasenasPbkdf2;
import com.sica.model.application.usecase.*;
import com.sica.model.domain.entity.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sica.pruebas.jdbc", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Recorridos: registro, actualización, listado y desactivación de cuentas")
class CuentasUsuarioIT {

    private static final String CLAVE = "ClaveCuentasPrueba2026!";
    private final ServicioContrasenasPbkdf2 contrasenas = new ServicioContrasenasPbkdf2();
    private final List<ContextoSesion> sesiones = new ArrayList<>();
    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private Aplicacion admin;
    private Set<Long> auditoriasIniciales;

    private final class Aplicacion implements AutoCloseable {
        final ContextoSesion contexto = new ContextoSesion();
        final EjecutorOperaciones ejecutor = new EjecutorOperaciones(gestor, contexto);
        final UsuarioCase cuentas = new UsuarioCase(ejecutor, contrasenas);
        final SesionUsuario sesion;

        Aplicacion(String correo, String claveTexto) {
            sesiones.add(contexto);
            char[] clave = claveTexto.toCharArray();
            try {
                sesion = new IniciarSesionCase(gestor, contrasenas).iniciar(contexto, correo, clave);
            } finally { Arrays.fill(clave, '\0'); }
        }

        Integer id() { return sesion.getUsuarioId(); }
        @Override public void close() { new CerrarSesionCase(contexto).cerrar(); }
    }

    @BeforeEach
    void preparar() throws Exception {
        base = BaseDatosTemporal.crear();
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        admin = ingresar("admin@sica.local");
        auditoriasIniciales = idsAuditoriasAdmin();
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
    @DisplayName("Las cuentas nacen activas con su rol, empresa cuando corresponde y un hash real que permite iniciar sesión")
    void registraLosRolesConContrasenasHasheadas() {
        Integer empresa = cuentaPorEmail("ana@sol.sica.local").getEmpresaId();
        List<String> hashes = new ArrayList<>();
        for (String nombreRol : List.of("Funcionario de Empresa", "Guarda de Seguridad", "Supervisor de Seguridad", "Superusuario")) {
            Integer rol = rol(nombreRol);
            Integer empresaElegida = nombreRol.equals("Funcionario de Empresa") ? empresa : null;
            Usuario nuevo = registrar("Cuenta " + nombreRol, correoNuevo(), rol, empresaElegida);
            Usuario guardado = cuenta(nuevo.getId());
            assertUsuario(nuevo, guardado);
            assertTrue(guardado.isEstaActivo());
            assertEquals(rol, guardado.getRolId());
            assertEquals(empresaElegida, guardado.getEmpresaId());
            assertNotEquals(CLAVE, guardado.getPasswordHash());
            char[] clave = CLAVE.toCharArray();
            try { assertTrue(contrasenas.verificar(clave, guardado.getPasswordHash())); }
            finally { Arrays.fill(clave, '\0'); }
            hashes.add(guardado.getPasswordHash());
            Aplicacion nueva = ingresar(nuevo.getEmail(), CLAVE);
            assertEquals(nuevo.getId(), nueva.id());
            assertEquals(rol, nueva.sesion.getRolId());
            assertEquals(empresaElegida, nueva.sesion.getEmpresaId());
            List<BitacoraAuditoria> auditorias = auditoriasCuenta(nuevo.getId(), "CREACION_USUARIO");
            assertEquals(1, auditorias.size());
            assertEquals(admin.id(), auditorias.get(0).getUsuarioId());
            assertFalse(auditorias.get(0).getDetalles().contains(CLAVE));
            assertFalse(auditorias.get(0).getDetalles().contains(guardado.getPasswordHash()));
        }
        // La misma contraseña recibe una sal diferente en cada cuenta.
        assertEquals(hashes.size(), hashes.stream().distinct().count());
    }

    @Test
    @DisplayName("El listado de administración solo muestra cuentas activas y conserva las retiradas fuera de esa lista")
    void listaSoloCuentasActivas() {
        Usuario activa = registrarGuarda("Cuenta activa");
        Usuario retirada = registrarGuarda("Cuenta retirada");
        admin.cuentas.desactivar(admin.id(), retirada.getId(), "Terminó su función");
        List<Usuario> cuentas = admin.cuentas.listar(admin.id());
        assertTrue(cuentas.stream().allMatch(Usuario::isEstaActivo));
        assertTrue(idsCuentas(cuentas).contains(activa.getId()));
        assertFalse(idsCuentas(cuentas).contains(retirada.getId()));
        assertFalse(cuenta(retirada.getId()).isEstaActivo());
        assertEquals(1, auditoriasCuenta(retirada.getId(), "CREACION_USUARIO").size());
        assertEquals(1, auditoriasCuenta(retirada.getId(), "DESACTIVACION_USUARIO").size());
    }

    @Test
    @DisplayName("Actualizar nombre y correo conserva rol, empresa, hash y estado, y registra los valores anteriores")
    void actualizaSinCambiarElRolNiLaContrasena() {
        Usuario original = registrar("Identidad anterior", correoNuevo(), rol("Funcionario de Empresa"),
                cuentaPorEmail("ana@sol.sica.local").getEmpresaId());
        String correo = correoNuevo();
        Usuario actualizada = admin.cuentas.actualizar(admin.id(), original.getId(), "Identidad renovada", correo);
        assertEquals("Identidad renovada", actualizada.getNombre());
        assertEquals(correo, actualizada.getEmail());
        assertCamposConservados(original, actualizada);
        assertUsuario(actualizada, cuenta(original.getId()));
        assertTrue(gestor.ejecutar(r -> r.crearUsuarioRepository().buscarPorEmail(original.getEmail())).isEmpty());
        assertEquals(original.getId(), ingresar(correo, CLAVE).id());
        BitacoraAuditoria auditoria = auditoriasCuenta(original.getId(), "ACTUALIZACION_USUARIO").get(0);
        for (String valor : List.of(original.getNombre(), original.getEmail(), actualizada.getNombre(), correo)) {
            assertTrue(auditoria.getDetalles().contains(valor));
        }
        assertFalse(auditoria.getDetalles().contains(CLAVE));
        assertFalse(auditoria.getDetalles().contains(original.getPasswordHash()));
    }

    @Test
    @DisplayName("El superusuario no puede desactivar su propia cuenta ni la de otro superusuario")
    void protegeLasCuentasDeSuperusuario() {
        Usuario segunda = registrar("Otro superusuario", correoNuevo(), rol("Superusuario"), null);
        Set<Long> antes = idsAuditoriasAdmin();
        assertThrows(IllegalStateException.class, () -> admin.cuentas.desactivar(admin.id(), admin.id(), "Intento propio"));
        assertThrows(IllegalStateException.class, () -> admin.cuentas.desactivar(admin.id(), segunda.getId(), "Intento sobre otro superusuario"));
        assertTrue(cuenta(admin.id()).isEstaActivo());
        assertTrue(cuenta(segunda.getId()).isEstaActivo());
        assertEquals(antes, idsAuditoriasAdmin());
    }

    @Test
    @DisplayName("Desactivar conserva la cuenta y la auditoría, impide nuevos logins y bloquea la sesión que seguía abierta")
    void desactivaSinBorrarElHistorial() {
        Usuario original = registrarGuarda("Guarda que se retira");
        Aplicacion guarda = ingresar(original.getEmail(), CLAVE);
        assertEquals(original.getId(), operacionDePorteria(guarda));
        Usuario desactivada = admin.cuentas.desactivar(admin.id(), original.getId(), "Renunció a su cargo");
        assertFalse(desactivada.isEstaActivo());
        assertCamposSinActividad(original, cuenta(original.getId()));
        assertThrows(IllegalStateException.class, () -> operacionDePorteria(guarda));
        assertThrows(SecurityException.class, () -> ingresar(original.getEmail(), CLAVE));
        assertFalse(idsCuentas(admin.cuentas.listar(admin.id())).contains(original.getId()));
        assertEquals(1, auditoriasCuenta(original.getId(), "CREACION_USUARIO").size());
        List<BitacoraAuditoria> cierre = auditoriasCuenta(original.getId(), "DESACTIVACION_USUARIO");
        assertEquals(1, cierre.size());
        assertEquals(admin.id(), cierre.get(0).getUsuarioId());
        assertTrue(cierre.get(0).getDetalles().contains("Renunció a su cargo"));
        assertThrows(IllegalStateException.class, () -> admin.cuentas.desactivar(admin.id(), original.getId(), "Repetido"));
        assertEquals(1, auditoriasCuenta(original.getId(), "DESACTIVACION_USUARIO").size());
    }

    @Test
    @DisplayName("Corregir los datos de una cuenta inactiva no la reactiva ni cambia su rol o contraseña")
    void actualizarNoReactivaUnaCuenta() {
        Usuario original = registrarGuarda("Identidad anterior");
        admin.cuentas.desactivar(admin.id(), original.getId(), "Terminó su función");
        String correo = correoNuevo();
        Usuario actualizada = admin.cuentas.actualizar(admin.id(), original.getId(), "Identidad corregida", correo);
        assertFalse(actualizada.isEstaActivo());
        assertFalse(cuenta(original.getId()).isEstaActivo());
        assertCamposSinActividad(original, actualizada);
        assertEquals(correo, actualizada.getEmail());
        assertEquals(original.getRolId(), actualizada.getRolId());
        assertFalse(idsCuentas(admin.cuentas.listar(admin.id())).contains(original.getId()));
        assertThrows(SecurityException.class, () -> ingresar(correo, CLAVE));
    }

    @Test
    @DisplayName("Toda cuenta exige un rol existente y solo los funcionarios se vinculan a una empresa activa")
    void validaLaRelacionEntreRolYEmpresa() {
        Integer funcionario = rol("Funcionario de Empresa");
        Integer empresa = cuentaPorEmail("ana@sol.sica.local").getEmpresaId();
        Integer retirada = cuentaPorEmail("marta@luna.sica.local").getEmpresaId();
        new EmpresaUseCase(admin.ejecutor).desactivar(admin.id(), retirada, "Empresa retirada");
        Set<Integer> antes = idsTodasLasCuentas();
        Set<Long> auditorias = idsAuditoriasAdmin();
        assertThrows(IllegalArgumentException.class, () -> registrar("Sin rol", correoNuevo(), null, null));
        assertThrows(IllegalArgumentException.class, () -> registrar("Rol inexistente", correoNuevo(), Integer.MAX_VALUE, null));
        assertThrows(IllegalArgumentException.class, () -> registrar("Sin empresa", correoNuevo(), funcionario, null));
        assertThrows(IllegalArgumentException.class, () -> registrar("Empresa inexistente", correoNuevo(), funcionario, Integer.MAX_VALUE));
        assertThrows(IllegalStateException.class, () -> registrar("Empresa retirada", correoNuevo(), funcionario, retirada));
        for (String rol : List.of("Guarda de Seguridad", "Supervisor de Seguridad", "Superusuario")) {
            Integer elegido = rol(rol);
            assertThrows(IllegalArgumentException.class, () -> registrar("Empresa incorrecta", correoNuevo(), elegido, empresa));
        }
        assertEquals(antes, idsTodasLasCuentas());
        assertEquals(auditorias, idsAuditoriasAdmin());
    }

    @Test
    @DisplayName("Rechaza datos incompletos y acepta los tamaños máximos definidos para nombre y correo")
    void validaDatosAntesDeGuardar() {
        Integer guarda = rol("Guarda de Seguridad");
        String sufijo = "@prueba.invalid";
        Usuario limite = registrar("N".repeat(100), "e".repeat(100 - sufijo.length()) + sufijo, guarda, null);
        assertEquals(100, cuenta(limite.getId()).getNombre().length());
        assertEquals(100, cuenta(limite.getId()).getEmail().length());
        Set<Integer> antes = idsTodasLasCuentas();
        Set<Long> auditorias = idsAuditoriasAdmin();
        for (String nombre : Arrays.asList(null, "", " ", "N".repeat(101))) {
            assertThrows(IllegalArgumentException.class, () -> registrar(nombre, correoNuevo(), guarda, null));
            assertThrows(IllegalArgumentException.class, () -> admin.cuentas.actualizar(admin.id(), limite.getId(), nombre, correoNuevo()));
        }
        for (String correo : Arrays.asList(null, "", " ", "e".repeat(101))) {
            assertThrows(IllegalArgumentException.class, () -> registrar("Nombre válido", correo, guarda, null));
            assertThrows(IllegalArgumentException.class, () -> admin.cuentas.actualizar(admin.id(), limite.getId(), "Nombre válido", correo));
        }
        for (char[] vacia : Arrays.asList(null, new char[0], "   ".toCharArray())) {
            assertThrows(IllegalArgumentException.class,
                    () -> registrarConClave(admin, "Sin contraseña", correoNuevo(), vacia, guarda, null));
        }
        assertThrows(IllegalArgumentException.class, () -> admin.cuentas.actualizar(admin.id(), null, "Nombre", correoNuevo()));
        assertThrows(IllegalArgumentException.class, () -> admin.cuentas.actualizar(admin.id(), Integer.MAX_VALUE, "Nombre", correoNuevo()));
        assertThrows(IllegalArgumentException.class, () -> admin.cuentas.desactivar(admin.id(), null, "Motivo"));
        assertThrows(IllegalArgumentException.class, () -> admin.cuentas.desactivar(admin.id(), Integer.MAX_VALUE, "Motivo"));
        for (String motivo : Arrays.asList(null, "", " ")) {
            assertThrows(IllegalArgumentException.class, () -> admin.cuentas.desactivar(admin.id(), limite.getId(), motivo));
        }
        assertEquals(antes, idsTodasLasCuentas());
        assertEquals(auditorias, idsAuditoriasAdmin());
    }

    @Test
    @DisplayName("El correo no se duplica, tampoco al actualizar o si la cuenta anterior está inactiva")
    void conservaLaUnicidadDelCorreo() {
        Usuario primera = registrarGuarda("Cuenta primera");
        Usuario segunda = registrarGuarda("Cuenta segunda");
        assertThrows(IllegalArgumentException.class, () -> registrar("Duplicada", primera.getEmail(), primera.getRolId(), null));
        assertThrows(IllegalArgumentException.class, () -> admin.cuentas.actualizar(admin.id(), segunda.getId(), "Cambio indebido", primera.getEmail()));
        assertUsuario(segunda, cuenta(segunda.getId()));
        // Conservar el correo de la misma cuenta no es un duplicado de otra persona.
        admin.cuentas.actualizar(admin.id(), primera.getId(), "Nombre corregido", primera.getEmail());
        admin.cuentas.desactivar(admin.id(), primera.getId(), "Cuenta retirada");
        Set<Integer> cuentas = idsTodasLasCuentas();
        Set<Long> auditorias = idsAuditoriasAdmin();
        assertThrows(IllegalArgumentException.class, () -> registrar("Reutilización", primera.getEmail(), primera.getRolId(), null));
        assertThrows(IllegalArgumentException.class, () -> admin.cuentas.actualizar(admin.id(), segunda.getId(), "Cambio indebido", primera.getEmail()));
        assertEquals(cuentas, idsTodasLasCuentas());
        assertEquals(auditorias, idsAuditoriasAdmin());
        assertUsuario(segunda, cuenta(segunda.getId()));
    }

    @Test
    @DisplayName("Una creación con auditoría fallida se deshace y puede intentarse de nuevo sin dejar cuentas huérfanas")
    void deshaceRegistroSiFallaLaAuditoria() {
        Set<Integer> antes = idsTodasLasCuentas();
        String correo = correoNuevo();
        Integer guarda = rol("Guarda de Seguridad");
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> registrar("Cuenta fallida", correo, guarda, null));
        assertTrue(gestor.ejecutar(r -> r.crearUsuarioRepository().buscarPorEmail(correo)).isEmpty());
        assertEquals(antes, idsTodasLasCuentas());
        assertTrue(auditoriasNuevas("CREACION_USUARIO").isEmpty());
        List<BitacoraAuditoria> fallos = auditoriasNuevas("CREACION_USUARIO_FALLIDA");
        assertEquals(1, fallos.size());
        assertNull(fallos.get(0).getRegistroIdAfectado());
        assertEquals("usuarios", fallos.get(0).getTablaAfectada());
        Usuario nuevo = registrar("Cuenta confirmada", correo, guarda, null);
        assertTrue(cuenta(nuevo.getId()).isEstaActivo());
        assertEquals(1, auditoriasCuenta(nuevo.getId(), "CREACION_USUARIO").size());
    }

    @Test
    @DisplayName("Una actualización fallida conserva nombre, correo, rol, empresa, hash y estado anteriores")
    void deshaceActualizacionSiFallaLaAuditoria() {
        Usuario original = registrar("Identidad anterior", correoNuevo(), rol("Funcionario de Empresa"),
                cuentaPorEmail("ana@sol.sica.local").getEmpresaId());
        String correo = correoNuevo();
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class,
                () -> admin.cuentas.actualizar(admin.id(), original.getId(), "Identidad renovada", correo));
        assertUsuario(original, cuenta(original.getId()));
        assertTrue(gestor.ejecutar(r -> r.crearUsuarioRepository().buscarPorEmail(correo)).isEmpty());
        assertTrue(auditoriasCuenta(original.getId(), "ACTUALIZACION_USUARIO").isEmpty());
        assertEquals(1, auditoriasCuenta(original.getId(), "ACTUALIZACION_USUARIO_FALLIDA").size());
        admin.cuentas.actualizar(admin.id(), original.getId(), "Identidad renovada", correo);
        assertEquals(original.getId(), ingresar(correo, CLAVE).id());
        assertEquals(1, auditoriasCuenta(original.getId(), "ACTUALIZACION_USUARIO").size());
    }

    @Test
    @DisplayName("Si falla la desactivación, la cuenta conserva el acceso hasta que se confirme la operación")
    void deshaceDesactivacionSiFallaLaAuditoria() {
        Usuario original = registrarGuarda("Guarda de prueba");
        Aplicacion guarda = ingresar(original.getEmail(), CLAVE);
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> admin.cuentas.desactivar(admin.id(), original.getId(), "Retiro de la cuenta"));
        assertUsuario(original, cuenta(original.getId()));
        assertEquals(original.getId(), operacionDePorteria(guarda));
        assertTrue(idsCuentas(admin.cuentas.listar(admin.id())).contains(original.getId()));
        assertTrue(auditoriasCuenta(original.getId(), "DESACTIVACION_USUARIO").isEmpty());
        assertEquals(1, auditoriasCuenta(original.getId(), "DESACTIVACION_USUARIO_FALLIDA").size());
        admin.cuentas.desactivar(admin.id(), original.getId(), "Retiro confirmado");
        assertFalse(cuenta(original.getId()).isEstaActivo());
        assertThrows(IllegalStateException.class, () -> operacionDePorteria(guarda));
        assertEquals(1, auditoriasCuenta(original.getId(), "DESACTIVACION_USUARIO").size());
    }

    @Test
    @DisplayName("Las cuentas sin permiso y las sesiones cerradas no administran usuarios")
    void exigePermisosYSesion() {
        Usuario objetivo = cuentaPorEmail("guarda@sica.local");
        for (String correo : List.of("guarda@sica.local", "ana@sol.sica.local", "supervisor@sica.local")) {
            Aplicacion otra = ingresar(correo);
            assertThrows(SecurityException.class,
                    () -> registrarConClave(otra, "No autorizada", correoNuevo(), CLAVE.toCharArray(), objetivo.getRolId(), null));
            assertThrows(SecurityException.class, () -> otra.cuentas.listar(otra.id()));
            assertThrows(SecurityException.class, () -> otra.cuentas.actualizar(otra.id(), objetivo.getId(), "Cambio indebido", correoNuevo()));
            assertThrows(SecurityException.class, () -> otra.cuentas.desactivar(otra.id(), objetivo.getId(), "Intento indebido"));
        }
        UsuarioCase sinSesion = new UsuarioCase(new EjecutorOperaciones(gestor, new ContextoSesion()), contrasenas);
        assertThrows(SecurityException.class, () -> sinSesion.listar(admin.id()));
        admin.close();
        assertThrows(SecurityException.class, () -> admin.cuentas.listar(admin.id()));
        assertUsuario(objetivo, cuenta(objetivo.getId()));
    }

    @Test
    @DisplayName("Dos sesiones que registran el mismo correo confirman una sola cuenta")
    void coordinaElRegistroDelMismoCorreo() throws Exception {
        Aplicacion segunda = ingresar("admin@sica.local");
        String correo = correoNuevo();
        Integer guarda = rol("Guarda de Seguridad");
        CountDownLatch inicio = new CountDownLatch(1);
        var hilos = Executors.newFixedThreadPool(2);
        try {
            var una = hilos.submit(registrarAlMismoTiempo(admin, "Registro primero", correo, guarda, inicio));
            var otra = hilos.submit(registrarAlMismoTiempo(segunda, "Registro segundo", correo, guarda, inicio));
            inicio.countDown();
            int confirmadas = (una.get(30, TimeUnit.SECONDS) ? 1 : 0) + (otra.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, confirmadas);
        } finally {
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(25, TimeUnit.SECONDS));
        }
        Usuario guardada = cuentaPorEmail(correo);
        assertTrue(guardada.isEstaActivo());
        assertEquals(1, gestor.ejecutar(r -> r.crearUsuarioRepository().listar()).stream().filter(u -> correo.equals(u.getEmail())).count());
        assertEquals(1, auditoriasCuenta(guardada.getId(), "CREACION_USUARIO").size());
    }

    @Test
    @DisplayName("Actualizar y desactivar desde dos sesiones conserva la cuenta inactiva y su rol original")
    void coordinaActualizacionYDesactivacion() throws Exception {
        Usuario original = registrarGuarda("Cuenta original");
        Aplicacion segunda = ingresar("admin@sica.local");
        String correo = correoNuevo();
        CountDownLatch inicio = new CountDownLatch(1);
        var hilos = Executors.newFixedThreadPool(2);
        try {
            var cambio = hilos.submit(() -> {
                if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("No comenzó la prueba.");
                return admin.cuentas.actualizar(admin.id(), original.getId(), "Cuenta actualizada", correo);
            });
            var retiro = hilos.submit(() -> {
                if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("No comenzó la prueba.");
                return segunda.cuentas.desactivar(segunda.id(), original.getId(), "Retiro simultáneo");
            });
            inicio.countDown();
            cambio.get(30, TimeUnit.SECONDS);
            retiro.get(30, TimeUnit.SECONDS);
        } finally {
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(25, TimeUnit.SECONDS));
        }
        Usuario guardada = cuenta(original.getId());
        assertCamposSinActividad(original, guardada);
        assertEquals("Cuenta actualizada", guardada.getNombre());
        assertEquals(correo, guardada.getEmail());
        assertEquals(1, auditoriasCuenta(original.getId(), "ACTUALIZACION_USUARIO").size());
        assertEquals(1, auditoriasCuenta(original.getId(), "DESACTIVACION_USUARIO").size());
    }

    private Callable<Boolean> registrarAlMismoTiempo(Aplicacion app, String nombre, String correo, Integer rol, CountDownLatch inicio) {
        return () -> {
            if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("No comenzó la prueba.");
            try {
                registrarConClave(app, nombre, correo, CLAVE.toCharArray(), rol, null);
                return true;
            } catch (IllegalArgumentException error) {
                if (!"Ya existe una cuenta con ese correo.".equals(error.getMessage())) throw error;
                return false;
            } catch (IllegalStateException error) {
                Throwable causa = error;
                while (causa != null && !(causa instanceof SQLException)) causa = causa.getCause();
                if (causa instanceof SQLException sql && sql.getErrorCode() == 1062 && "23000".equals(sql.getSQLState())) return false;
                throw error;
            }
        };
    }

    private Aplicacion ingresar(String correo) { return ingresar(correo, "SicaDemo2026!"); }
    private Aplicacion ingresar(String correo, String clave) { return new Aplicacion(correo, clave); }

    private Usuario registrarGuarda(String nombre) { return registrar(nombre, correoNuevo(), rol("Guarda de Seguridad"), null); }

    private Usuario registrar(String nombre, String correo, Integer rol, Integer empresa) {
        return registrarConClave(admin, nombre, correo, CLAVE.toCharArray(), rol, empresa);
    }

    private Usuario registrarConClave(Aplicacion app, String nombre, String correo, char[] clave, Integer rol, Integer empresa) {
        try { return app.cuentas.registrar(app.id(), nombre, correo, clave, rol, empresa); }
        finally { if (clave != null) Arrays.fill(clave, '\0'); }
    }

    private Usuario cuenta(Integer id) { return gestor.ejecutar(r -> r.crearUsuarioRepository().buscarPorId(id).orElseThrow()); }
    private Usuario cuentaPorEmail(String correo) { return gestor.ejecutar(r -> r.crearUsuarioRepository().buscarPorEmail(correo).orElseThrow()); }
    private Integer rol(String nombre) { return gestor.ejecutar(r -> r.crearRolRepository().buscarPorNombre(nombre).orElseThrow().getId()); }
    private String correoNuevo() { return UUID.randomUUID() + "@prueba.invalid"; }

    private Integer operacionDePorteria(Aplicacion app) {
        return app.ejecutor.ejecutar(app.id(), "consultar_visita_porteria", (r, usuario) -> usuario.getId());
    }

    private Set<Integer> idsCuentas(List<Usuario> cuentas) { return cuentas.stream().map(Usuario::getId).collect(Collectors.toSet()); }
    private Set<Integer> idsTodasLasCuentas() { return idsCuentas(gestor.ejecutar(r -> r.crearUsuarioRepository().listar())); }

    private Set<Long> idsAuditoriasAdmin() {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(admin.id()))
                .stream().map(BitacoraAuditoria::getId).collect(Collectors.toSet());
    }

    private List<BitacoraAuditoria> auditoriasNuevas(String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(admin.id()))
                .stream().filter(a -> !auditoriasIniciales.contains(a.getId()))
                .filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }

    private List<BitacoraAuditoria> auditoriasCuenta(Integer id, String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorRegistro("usuarios", id))
                .stream().filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }

    private void assertUsuario(Usuario esperada, Usuario actual) {
        assertEquals(esperada.getId(), actual.getId());
        assertEquals(esperada.getNombre(), actual.getNombre());
        assertEquals(esperada.getEmail(), actual.getEmail());
        assertCamposConservados(esperada, actual);
    }

    private void assertCamposConservados(Usuario original, Usuario actual) {
        assertEquals(original.getRolId(), actual.getRolId());
        assertEquals(original.getEmpresaId(), actual.getEmpresaId());
        assertTrue(original.getPasswordHash().equals(actual.getPasswordHash()), "La operación debe conservar el hash existente.");
        assertEquals(original.isEstaActivo(), actual.isEstaActivo());
    }

    private void assertCamposSinActividad(Usuario original, Usuario actual) {
        assertEquals(original.getRolId(), actual.getRolId());
        assertEquals(original.getEmpresaId(), actual.getEmpresaId());
        assertTrue(original.getPasswordHash().equals(actual.getPasswordHash()), "La operación debe conservar el hash existente.");
        assertFalse(actual.isEstaActivo());
    }
}
