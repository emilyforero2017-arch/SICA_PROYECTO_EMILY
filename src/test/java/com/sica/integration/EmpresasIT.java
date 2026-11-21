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
@DisplayName("Recorridos: registro, consultas y actualización de empresas")
class EmpresasIT {

    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private final List<Aplicacion> aplicaciones = new ArrayList<>();
    private Aplicacion admin;
    private Set<Long> auditoriasIniciales;

    private final class Aplicacion implements AutoCloseable {
        final ContextoSesion contexto = new ContextoSesion();
        final EjecutorOperaciones ejecutor = new EjecutorOperaciones(gestor, contexto);
        final EmpresaUseCase empresas = new EmpresaUseCase(ejecutor);
        final SesionUsuario sesion;

        Aplicacion(String correo) {
            char[] clave = "SicaDemo2026!".toCharArray();
            try {
                sesion = new IniciarSesionCase(gestor, new ServicioContrasenasPbkdf2()).iniciar(contexto, correo, clave);
            } finally { Arrays.fill(clave, '\0'); }
        }

        Integer id() { return sesion.getUsuarioId(); }
        @Override public void close() { new CerrarSesionCase(contexto).cerrar(); }
    }

    private record Relacionadas(Usuario funcionaria, Persona trabajador, Visita visita, Incidente incidente) { }

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
            for (Aplicacion app : aplicaciones) app.close();
        } finally {
            if (base != null) base.close();
            System.out.println("Recorrido revisado: " + prueba.getDisplayName());
        }
    }

    @Test
    @DisplayName("El superusuario registra una empresa activa, la consulta y conserva su auditoría")
    void registraConsultaYAuditaLaEmpresa() {
        Empresa entrada = new Empresa("Empresa nueva de prueba", "Ana contacto");
        Empresa registrada = admin.empresas.registrar(admin.id(), entrada);
        assertNull(entrada.getId());
        assertNotNull(registrada.getId());
        Empresa guardada = admin.empresas.buscarPorId(admin.id(), registrada.getId());
        assertEmpresa(registrada, guardada);
        assertTrue(guardada.isEstaActiva());
        assertTrue(idsEmpresas(admin.empresas.listar(admin.id())).contains(guardada.getId()));
        assertTrue(idsEmpresas(admin.empresas.listarActivas(admin.id())).contains(guardada.getId()));
        List<BitacoraAuditoria> auditorias = auditoriasEmpresa(guardada.getId(), "REGISTRO_EMPRESA");
        assertEquals(1, auditorias.size());
        assertEquals(admin.id(), auditorias.get(0).getUsuarioId());
        assertTrue(auditorias.get(0).getDetalles().contains(guardada.getNombre()));
        gestor.ejecutar(r -> {
            assertTrue(r.crearUsuarioRepository().listarPorEmpresa(guardada.getId()).isEmpty());
            assertTrue(r.crearPersonaRepository().listarTrabajadoresPorEmpresa(guardada.getId()).isEmpty());
            return null;
        });
    }

    @Test
    @DisplayName("La consulta general conserva las retiradas y la lista de activas las excluye")
    void distingueElCatalogoGeneralDeLasEmpresasDisponibles() {
        Empresa zeta = registrar("Zeta prueba");
        Empresa alfa = registrar("Alfa prueba");
        Empresa beta = registrar("Beta prueba");
        admin.empresas.desactivar(admin.id(), beta.getId(), "Empresa retirada");
        Set<Integer> propias = Set.of(zeta.getId(), alfa.getId(), beta.getId());
        List<Integer> generales = admin.empresas.listar(admin.id()).stream().filter(e -> propias.contains(e.getId()))
                .map(Empresa::getId).toList();
        assertEquals(List.of(alfa.getId(), beta.getId(), zeta.getId()), generales);
        assertEquals(Set.of(alfa.getId(), zeta.getId()), admin.empresas.listarActivas(admin.id()).stream()
                .filter(e -> propias.contains(e.getId())).map(Empresa::getId).collect(Collectors.toSet()));
        assertFalse(admin.empresas.buscarPorId(admin.id(), beta.getId()).isEstaActiva());
    }

    @Test
    @DisplayName("Actualizar nombre y contacto conserva el estado activo, las relaciones y los valores anteriores en auditoría")
    void actualizaSinCambiarLaActividadNiElHistorial() {
        Relacionadas antes = relacionadasLuna();
        Empresa anterior = admin.empresas.buscarPorId(admin.id(), antes.funcionaria().getEmpresaId());
        Empresa propuesta = new Empresa(anterior.getId(), "Empresa Estrella renovada", "Beatriz contacto", false);
        Empresa actualizada = admin.empresas.actualizar(admin.id(), propuesta);
        assertTrue(actualizada.isEstaActiva());
        assertEquals(anterior.getId(), actualizada.getId());
        assertEquals(propuesta.getNombre(), actualizada.getNombre());
        assertEquals(propuesta.getContactoPrincipal(), actualizada.getContactoPrincipal());
        assertEmpresa(actualizada, admin.empresas.buscarPorId(admin.id(), anterior.getId()));
        comprobarRelaciones(antes, relacionadasLuna());
        BitacoraAuditoria auditoria = auditoriasEmpresa(anterior.getId(), "ACTUALIZACION_EMPRESA").get(0);
        assertEquals(admin.id(), auditoria.getUsuarioId());
        assertTrue(auditoria.getDetalles().contains(anterior.getNombre()));
        assertTrue(auditoria.getDetalles().contains(propuesta.getNombre()));
        assertTrue(auditoria.getDetalles().contains(anterior.getContactoPrincipal()));
        assertTrue(auditoria.getDetalles().contains(propuesta.getContactoPrincipal()));
    }

    @Test
    @DisplayName("Actualizar una empresa retirada no reactiva la empresa, sus cuentas ni los vínculos laborales")
    void noReactivaUnaEmpresaAlCorregirSusDatos() {
        Relacionadas iniciales = relacionadasLuna();
        Integer empresaId = iniciales.funcionaria().getEmpresaId();
        admin.empresas.desactivar(admin.id(), empresaId, "La empresa se retiró");
        Relacionadas retiradas = relacionadasLuna();
        assertFalse(retiradas.funcionaria().isEstaActivo());
        assertEquals(Boolean.FALSE, retiradas.trabajador().getVinculacionLaboralActiva());
        Empresa actualizada = admin.empresas.actualizar(admin.id(), new Empresa(empresaId, "Luna archivo histórico", "Contacto corregido"));
        assertFalse(actualizada.isEstaActiva());
        assertFalse(admin.empresas.buscarPorId(admin.id(), empresaId).isEstaActiva());
        comprobarRelaciones(retiradas, relacionadasLuna());
        assertFalse(idsEmpresas(admin.empresas.listarActivas(admin.id())).contains(empresaId));
        assertTrue(idsEmpresas(admin.empresas.listar(admin.id())).contains(empresaId));
        assertEquals(1, auditoriasEmpresa(empresaId, "DESACTIVACION_EMPRESA").size());
        assertEquals(1, auditoriasEmpresa(empresaId, "ACTUALIZACION_EMPRESA").size());
    }

    @Test
    @DisplayName("Valida nombres, tamaños e identificadores antes de guardar y admite los tamaños máximos definidos")
    void validaLosDatosDeRegistroYActualizacion() {
        Empresa limite = admin.empresas.registrar(admin.id(), new Empresa("N".repeat(100), "C".repeat(100)));
        assertEquals(100, admin.empresas.buscarPorId(admin.id(), limite.getId()).getNombre().length());
        assertEquals(100, admin.empresas.buscarPorId(admin.id(), limite.getId()).getContactoPrincipal().length());
        Set<Integer> empresasAntes = idsEmpresas(admin.empresas.listar(admin.id()));
        Set<Long> auditoriasAntes = idsAuditoriasAdmin();
        assertThrows(IllegalArgumentException.class, () -> admin.empresas.registrar(admin.id(), null));
        assertThrows(IllegalArgumentException.class, () -> admin.empresas.registrar(admin.id(), new Empresa(limite.getId(), "Con identificador", "Contacto")));
        assertThrows(IllegalArgumentException.class, () -> admin.empresas.registrar(admin.id(), new Empresa(null, "Nueva inactiva", "Contacto", false)));
        for (String nombre : Arrays.asList(null, "", " ", "N".repeat(101))) {
            assertThrows(IllegalArgumentException.class, () -> admin.empresas.registrar(admin.id(), new Empresa(nombre, "Contacto")));
        }
        assertThrows(IllegalArgumentException.class, () -> admin.empresas.registrar(admin.id(), new Empresa("Nombre válido", "C".repeat(101))));
        assertThrows(IllegalArgumentException.class, () -> admin.empresas.actualizar(admin.id(), null));
        assertThrows(IllegalArgumentException.class, () -> admin.empresas.actualizar(admin.id(), new Empresa("Sin identificador", "Contacto")));
        assertThrows(IllegalArgumentException.class, () -> admin.empresas.actualizar(admin.id(), new Empresa(Integer.MAX_VALUE, "Inexistente", "Contacto")));
        assertThrows(IllegalArgumentException.class, () -> admin.empresas.buscarPorId(admin.id(), null));
        assertThrows(IllegalArgumentException.class, () -> admin.empresas.buscarPorId(admin.id(), Integer.MAX_VALUE));
        assertEquals(empresasAntes, idsEmpresas(admin.empresas.listar(admin.id())));
        assertEquals(auditoriasAntes, idsAuditoriasAdmin());
    }

    @Test
    @DisplayName("Las cuentas sin permisos de administración no registran, actualizan ni consultan este catálogo")
    void respetaLosPermisosDeAdministracion() {
        Empresa empresa = admin.empresas.buscarPorId(admin.id(), relacionadasLuna().funcionaria().getEmpresaId());
        for (String correo : List.of("guarda@sica.local", "ana@sol.sica.local", "supervisor@sica.local")) {
            Aplicacion otra = ingresar(correo);
            assertThrows(SecurityException.class, () -> otra.empresas.registrar(otra.id(), new Empresa("No autorizada", "Contacto")));
            assertThrows(SecurityException.class, () -> otra.empresas.actualizar(otra.id(), new Empresa(empresa.getId(), "Cambio no autorizado", "Contacto")));
            assertThrows(SecurityException.class, () -> otra.empresas.listar(otra.id()));
            assertThrows(SecurityException.class, () -> otra.empresas.listarActivas(otra.id()));
            assertThrows(SecurityException.class, () -> otra.empresas.buscarPorId(otra.id(), empresa.getId()));
        }
        assertEmpresa(empresa, admin.empresas.buscarPorId(admin.id(), empresa.getId()));
    }

    @Test
    @DisplayName("El identificador del superusuario no sustituye una sesión abierta")
    void requiereSesionParaAdministrarEmpresas() {
        EmpresaUseCase sinSesion = new EmpresaUseCase(new EjecutorOperaciones(gestor, new ContextoSesion()));
        assertThrows(SecurityException.class, () -> sinSesion.registrar(admin.id(), new Empresa("Sin sesión", "Contacto")));
        assertThrows(SecurityException.class, () -> sinSesion.listar(admin.id()));
        admin.close();
        assertThrows(SecurityException.class, () -> admin.empresas.registrar(admin.id(), new Empresa("Sesión cerrada", "Contacto")));
        assertThrows(SecurityException.class, () -> admin.empresas.listar(admin.id()));
        Aplicacion nueva = ingresar("admin@sica.local");
        assertFalse(nueva.empresas.listar(nueva.id()).isEmpty());
    }

    @Test
    @DisplayName("Si falla la auditoría del registro, no queda una empresa a medias y el intento fallido se conserva")
    void deshaceRegistroSiFallaLaAuditoria() {
        Set<Integer> anteriores = idsEmpresas(admin.empresas.listar(admin.id()));
        String nombre = "Empresa prueba " + UUID.randomUUID();
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> admin.empresas.registrar(admin.id(), new Empresa(nombre, "Contacto")));
        assertEquals(anteriores, idsEmpresas(admin.empresas.listar(admin.id())));
        assertTrue(auditoriasNuevas("REGISTRO_EMPRESA").isEmpty());
        List<BitacoraAuditoria> fallos = auditoriasNuevas("REGISTRO_EMPRESA_FALLIDO");
        assertEquals(1, fallos.size());
        assertEquals(admin.id(), fallos.get(0).getUsuarioId());
        assertEquals("empresas", fallos.get(0).getTablaAfectada());
        assertNull(fallos.get(0).getRegistroIdAfectado());
        Empresa reintento = admin.empresas.registrar(admin.id(), new Empresa(nombre, "Contacto"));
        assertEquals(1, auditoriasEmpresa(reintento.getId(), "REGISTRO_EMPRESA").size());
        assertEquals(1, auditoriasNuevas("REGISTRO_EMPRESA_FALLIDO").size());
    }

    @Test
    @DisplayName("Si falla la auditoría de una actualización, se conservan los datos anteriores y las relaciones")
    void deshaceActualizacionSiFallaLaAuditoria() {
        Relacionadas relaciones = relacionadasLuna();
        Empresa anterior = admin.empresas.buscarPorId(admin.id(), relaciones.funcionaria().getEmpresaId());
        Empresa propuesta = new Empresa(anterior.getId(), "Cambio que debe deshacerse", "Otro contacto");
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> admin.empresas.actualizar(admin.id(), propuesta));
        assertEmpresa(anterior, admin.empresas.buscarPorId(admin.id(), anterior.getId()));
        comprobarRelaciones(relaciones, relacionadasLuna());
        assertTrue(auditoriasEmpresa(anterior.getId(), "ACTUALIZACION_EMPRESA").isEmpty());
        assertEquals(1, auditoriasEmpresa(anterior.getId(), "ACTUALIZACION_EMPRESA_FALLIDA").size());
        admin.empresas.actualizar(admin.id(), propuesta);
        assertEquals(propuesta.getNombre(), admin.empresas.buscarPorId(admin.id(), anterior.getId()).getNombre());
        assertEquals(1, auditoriasEmpresa(anterior.getId(), "ACTUALIZACION_EMPRESA").size());
    }

    @Test
    @DisplayName("Los errores al escribir la propia empresa también generan auditoría de fallo sin guardar cambios")
    void registraFallosRealesDeEscrituraEnMySql() throws SQLException {
        Empresa anterior = admin.empresas.buscarPorId(admin.id(), relacionadasLuna().funcionaria().getEmpresaId());
        Set<Integer> catalogo = idsEmpresas(admin.empresas.listar(admin.id()));
        // Estas protecciones provocan errores únicamente en la base temporal de esta prueba.
        try (var conexion = acceso.abrirConexion(); var consulta = conexion.createStatement()) {
            consulta.execute("CREATE TRIGGER prueba_empresa_insertar BEFORE INSERT ON empresas FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Fallo de escritura de empresa provocado para la prueba.'");
            consulta.execute("CREATE TRIGGER prueba_empresa_actualizar BEFORE UPDATE ON empresas FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Fallo de actualización de empresa provocado para la prueba.'");
        }
        IllegalStateException registro = assertThrows(IllegalStateException.class,
                () -> admin.empresas.registrar(admin.id(), new Empresa("No debe guardarse", "Contacto")));
        assertInstanceOf(SQLException.class, registro.getCause());
        assertEquals("45000", ((SQLException) registro.getCause()).getSQLState());
        IllegalStateException cambio = assertThrows(IllegalStateException.class,
                () -> admin.empresas.actualizar(admin.id(), new Empresa(anterior.getId(), "No debe actualizarse", "Contacto")));
        assertInstanceOf(SQLException.class, cambio.getCause());
        assertEquals("45000", ((SQLException) cambio.getCause()).getSQLState());
        assertEquals(catalogo, idsEmpresas(admin.empresas.listar(admin.id())));
        assertEmpresa(anterior, admin.empresas.buscarPorId(admin.id(), anterior.getId()));
        assertEquals(1, auditoriasNuevas("REGISTRO_EMPRESA_FALLIDO").size());
        assertEquals(1, auditoriasNuevas("ACTUALIZACION_EMPRESA_FALLIDA").size());
        assertTrue(auditoriasNuevas("REGISTRO_EMPRESA").isEmpty());
        assertTrue(auditoriasNuevas("ACTUALIZACION_EMPRESA").isEmpty());
    }

    @Test
    @DisplayName("Actualizar y desactivar desde dos sesiones no vuelve a activar la empresa")
    void coordinaActualizacionYDesactivacion() throws Exception {
        Empresa empresa = registrar("Empresa concurrente");
        Aplicacion segunda = ingresar("admin@sica.local");
        CountDownLatch inicio = new CountDownLatch(1);
        var hilos = Executors.newFixedThreadPool(2);
        try {
            var actualizar = hilos.submit(() -> {
                if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("No comenzó la prueba.");
                return admin.empresas.actualizar(admin.id(), new Empresa(empresa.getId(), "Nombre actualizado", "Nuevo contacto"));
            });
            var retirar = hilos.submit(() -> {
                if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("No comenzó la prueba.");
                return segunda.empresas.desactivar(segunda.id(), empresa.getId(), "Retiro simultáneo");
            });
            inicio.countDown();
            actualizar.get(30, TimeUnit.SECONDS);
            retirar.get(30, TimeUnit.SECONDS);
        } finally {
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(25, TimeUnit.SECONDS));
        }
        Empresa finalizada = admin.empresas.buscarPorId(admin.id(), empresa.getId());
        assertFalse(finalizada.isEstaActiva());
        assertEquals("Nombre actualizado", finalizada.getNombre());
        assertEquals("Nuevo contacto", finalizada.getContactoPrincipal());
        assertEquals(1, auditoriasEmpresa(empresa.getId(), "ACTUALIZACION_EMPRESA").size());
        assertEquals(1, auditoriasEmpresa(empresa.getId(), "DESACTIVACION_EMPRESA").size());
    }

    @Test
    @DisplayName("Una asignación incorrecta de superusuario a una empresa no permite desactivarlo indirectamente")
    void protegeAlSuperusuarioInclusoAnteUnaAsignacionIncorrecta() {
        Relacionadas antes = relacionadasLuna();
        Integer empresaId = antes.funcionaria().getEmpresaId();
        // Simula datos mal asignados: los casos de creación de cuentas no permiten esta combinación.
        Usuario malAsignado = gestor.ejecutar(r -> {
            Usuario modelo = r.crearUsuarioRepository().buscarPorId(admin.id()).orElseThrow();
            return r.crearUsuarioRepository().guardar(new Usuario("Superusuario mal asignado", UUID.randomUUID() + "@prueba.invalid",
                    modelo.getPasswordHash(), modelo.getRolId(), empresaId));
        });
        assertThrows(IllegalStateException.class, () -> admin.empresas.desactivar(admin.id(), empresaId, "Intento de retiro"));
        assertTrue(admin.empresas.buscarPorId(admin.id(), empresaId).isEstaActiva());
        comprobarRelaciones(antes, relacionadasLuna());
        assertTrue(gestor.ejecutar(r -> r.crearUsuarioRepository().buscarPorId(malAsignado.getId()).orElseThrow()).isEstaActivo());
        assertTrue(auditoriasNuevas("DESACTIVACION_EMPRESA").isEmpty());
        assertTrue(auditoriasNuevas("DESACTIVACION_EMPRESA_FALLIDA").isEmpty());
    }

    private Aplicacion ingresar(String correo) {
        Aplicacion app = new Aplicacion(correo);
        aplicaciones.add(app);
        return app;
    }

    private Empresa registrar(String nombre) { return admin.empresas.registrar(admin.id(), new Empresa(nombre, "Contacto de prueba")); }

    private Set<Integer> idsEmpresas(List<Empresa> empresas) { return empresas.stream().map(Empresa::getId).collect(Collectors.toSet()); }

    private Set<Long> idsAuditoriasAdmin() {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(admin.id()))
                .stream().map(BitacoraAuditoria::getId).collect(Collectors.toSet());
    }

    private List<BitacoraAuditoria> auditoriasNuevas(String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(admin.id()))
                .stream().filter(a -> !auditoriasIniciales.contains(a.getId()))
                .filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }

    private List<BitacoraAuditoria> auditoriasEmpresa(Integer id, String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorRegistro("empresas", id))
                .stream().filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }

    private Relacionadas relacionadasLuna() {
        return gestor.ejecutar(r -> {
            Usuario funcionaria = r.crearUsuarioRepository().buscarPorEmail("marta@luna.sica.local").orElseThrow();
            Persona trabajador = r.crearPersonaRepository().listarTrabajadoresPorEmpresa(funcionaria.getEmpresaId()).get(0);
            Visita visita = r.crearVisitaRepository().listarPorFuncionarioDestinatario(funcionaria.getId()).stream()
                    .filter(v -> v.getFechaSalida() != null).findFirst().orElseThrow();
            Incidente incidente = r.crearIncidenteRepository().listarPorVisita(visita.getId()).get(0);
            return new Relacionadas(funcionaria, trabajador, visita, incidente);
        });
    }

    private void assertEmpresa(Empresa esperada, Empresa actual) {
        assertEquals(esperada.getId(), actual.getId());
        assertEquals(esperada.getNombre(), actual.getNombre());
        assertEquals(esperada.getContactoPrincipal(), actual.getContactoPrincipal());
        assertEquals(esperada.isEstaActiva(), actual.isEstaActiva());
    }

    private void comprobarRelaciones(Relacionadas antes, Relacionadas despues) {
        assertEquals(antes.funcionaria().getId(), despues.funcionaria().getId());
        assertEquals(antes.funcionaria().getEmpresaId(), despues.funcionaria().getEmpresaId());
        assertEquals(antes.funcionaria().getNombre(), despues.funcionaria().getNombre());
        assertEquals(antes.funcionaria().isEstaActivo(), despues.funcionaria().isEstaActivo());
        assertEquals(antes.trabajador().getId(), despues.trabajador().getId());
        assertEquals(antes.trabajador().getEmpresaId(), despues.trabajador().getEmpresaId());
        assertEquals(antes.trabajador().getDocumentoIdentidad(), despues.trabajador().getDocumentoIdentidad());
        assertEquals(antes.trabajador().getEstadoAccesoId(), despues.trabajador().getEstadoAccesoId());
        assertEquals(antes.trabajador().getVinculacionLaboralActiva(), despues.trabajador().getVinculacionLaboralActiva());
        assertEquals(antes.visita().getId(), despues.visita().getId());
        assertEquals(antes.visita().getEmpresaDestinoId(), despues.visita().getEmpresaDestinoId());
        assertEquals(antes.visita().getFechaEntrada(), despues.visita().getFechaEntrada());
        assertEquals(antes.visita().getFechaSalida(), despues.visita().getFechaSalida());
        assertEquals(antes.visita().getFechaCierreSistema(), despues.visita().getFechaCierreSistema());
        assertEquals(antes.visita().getEstadoVisitaId(), despues.visita().getEstadoVisitaId());
        assertEquals(antes.incidente().getId(), despues.incidente().getId());
        assertEquals(antes.incidente().getVisitaId(), despues.incidente().getVisitaId());
        assertEquals(antes.incidente().getDescripcion(), despues.incidente().getDescripcion());
        assertEquals(antes.incidente().getFechaIncidente(), despues.incidente().getFechaIncidente());
    }
}
