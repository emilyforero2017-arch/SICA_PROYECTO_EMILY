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
@DisplayName("Recorridos: consulta, asignación y retiro de permisos de los roles")
class RolesPermisosIT {

    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private final List<ContextoSesion> contextos = new ArrayList<>();
    private Aplicacion admin;

    private final class Aplicacion {
        final ContextoSesion contexto = new ContextoSesion();
        final EjecutorOperaciones ejecutor = new EjecutorOperaciones(gestor, contexto);
        final RolPermisoCase permisos = new RolPermisoCase(ejecutor);
        final SesionUsuario sesion;

        Aplicacion(String correo) {
            contextos.add(contexto);
            char[] clave = "SicaDemo2026!".toCharArray();
            try {
                sesion = new IniciarSesionCase(gestor, new ServicioContrasenasPbkdf2())
                        .iniciar(contexto, correo, clave);
            } finally { Arrays.fill(clave, '\0'); }
        }

        Integer id() { return sesion.getUsuarioId(); }
    }

    @BeforeEach
    void preparar() throws Exception {
        base = BaseDatosTemporal.crear();
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        admin = new Aplicacion("admin@sica.local");
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
    @DisplayName("El superusuario consulta los cuatro roles y las acciones vigentes sin generar auditoría de otra auditoría")
    void consultaCatalogos() {
        Set<Long> antes = idsAuditoriasAdmin();
        List<Rol> roles = admin.permisos.listarRoles(admin.id());
        assertEquals(Set.of("Superusuario", "Supervisor de Seguridad", "Guarda de Seguridad", "Funcionario de Empresa"),
                roles.stream().map(Rol::getNombreRol).collect(Collectors.toSet()));
        List<Permiso> permisos = admin.permisos.listarPermisos(admin.id());
        Set<String> nombres = permisos.stream().map(Permiso::getNombrePermiso).collect(Collectors.toSet());
        assertTrue(nombres.containsAll(Set.of("asignar_permiso_rol", "retirar_permiso_rol", "consultar_roles_permisos")));
        assertFalse(nombres.contains("activar_usuario"));
        assertFalse(nombres.contains("asignar_rol_usuario"));
        assertEquals(permisos.size(), nombres.size());
        assertEquals(antes, idsAuditoriasAdmin());
    }

    @Test
    @DisplayName("Cada rol muestra solo sus permisos y consultar no modifica las asignaciones")
    void consultaAsignacionesPorRol() {
        Set<Long> antes = idsAuditoriasAdmin();
        for (Rol rol : admin.permisos.listarRoles(admin.id())) {
            List<Permiso> obtenidos = admin.permisos.consultarPermisosDeRol(admin.id(), rol.getId());
            Set<Integer> esperados = gestor.ejecutar(r -> r.crearPermisoRepository().listarPorRol(rol.getId()))
                    .stream().map(Permiso::getId).collect(Collectors.toSet());
            assertEquals(esperados, obtenidos.stream().map(Permiso::getId).collect(Collectors.toSet()));
            assertEquals(esperados.size(), obtenidos.size());
            assertEquals("Superusuario".equals(rol.getNombreRol()),
                    obtenidos.stream().anyMatch(p -> "asignar_permiso_rol".equals(p.getNombrePermiso())));
        }
        assertEquals(antes, idsAuditoriasAdmin());
    }

    @Test
    @DisplayName("Guarda, funcionario y supervisor no pueden administrar permisos con sus asignaciones iniciales")
    void exigePermisosAdministrativos() {
        Integer guarda = rol("Guarda de Seguridad");
        Integer consulta = permiso("consultar_roles_permisos");
        Set<Integer> antes = permisosDe(guarda);
        for (String correo : List.of("guarda@sica.local", "ana@sol.sica.local", "supervisor@sica.local")) {
            Aplicacion otra = new Aplicacion(correo);
            assertThrows(SecurityException.class, () -> otra.permisos.listarRoles(otra.id()));
            assertThrows(SecurityException.class, () -> otra.permisos.listarPermisos(otra.id()));
            assertThrows(SecurityException.class, () -> otra.permisos.consultarPermisosDeRol(otra.id(), guarda));
            assertThrows(SecurityException.class, () -> otra.permisos.asignarPermiso(otra.id(), guarda, consulta));
            assertThrows(SecurityException.class, () -> otra.permisos.retirarPermiso(otra.id(), guarda, consulta));
        }
        assertEquals(antes, permisosDe(guarda));
    }

    @Test
    @DisplayName("La administración requiere una sesión propia vigente y no acepta identificarse como otra cuenta")
    void exigeSesionPropia() {
        Aplicacion guarda = new Aplicacion("guarda@sica.local");
        assertThrows(SecurityException.class, () -> guarda.permisos.listarRoles(admin.id()));
        RolPermisoCase sinSesion = new RolPermisoCase(new EjecutorOperaciones(gestor, new ContextoSesion()));
        assertThrows(SecurityException.class, () -> sinSesion.listarRoles(admin.id()));
        new CerrarSesionCase(admin.contexto).cerrar();
        assertThrows(SecurityException.class, () -> admin.permisos.listarRoles(admin.id()));
        assertThrows(SecurityException.class, () -> admin.permisos.asignarPermiso(admin.id(),
                rol("Guarda de Seguridad"), permiso("consultar_roles_permisos")));
    }

    @Test
    @DisplayName("Identificadores ausentes o inexistentes se rechazan antes de modificar o auditar asignaciones")
    void validaIdentificadores() {
        Integer rol = rol("Guarda de Seguridad");
        Integer permiso = permiso("consultar_roles_permisos");
        Set<Long> antes = idsAuditoriasAdmin();
        Set<Integer> asignaciones = permisosDe(rol);
        assertThrows(IllegalArgumentException.class, () -> admin.permisos.consultarPermisosDeRol(admin.id(), null));
        assertThrows(IllegalArgumentException.class, () -> admin.permisos.consultarPermisosDeRol(admin.id(), Integer.MAX_VALUE));
        for (Integer[] par : List.of(new Integer[]{null, permiso}, new Integer[]{rol, null},
                new Integer[]{Integer.MAX_VALUE, permiso}, new Integer[]{rol, Integer.MAX_VALUE})) {
            assertThrows(IllegalArgumentException.class, () -> admin.permisos.asignarPermiso(admin.id(), par[0], par[1]));
            assertThrows(IllegalArgumentException.class, () -> admin.permisos.retirarPermiso(admin.id(), par[0], par[1]));
        }
        assertEquals(asignaciones, permisosDe(rol));
        assertEquals(antes, idsAuditoriasAdmin());
    }

    @Test
    @DisplayName("Asignar agrega una sola relación y su auditoría sin cambiar el rol fijo de las cuentas")
    void asignaSinDuplicarNiCambiarCuentas() {
        Integer rol = rol("Guarda de Seguridad");
        Integer permiso = permiso("consultar_roles_permisos");
        List<Usuario> cuentas = gestor.ejecutar(r -> r.crearUsuarioRepository().listar());
        assertTrue(admin.permisos.asignarPermiso(admin.id(), rol, permiso));
        assertFalse(admin.permisos.asignarPermiso(admin.id(), rol, permiso));
        assertTrue(permisosDe(rol).contains(permiso));
        List<BitacoraAuditoria> auditorias = auditorias(rol, "ASIGNACION_PERMISO_ROL");
        assertEquals(1, auditorias.size());
        BitacoraAuditoria registro = auditorias.get(0);
        assertEquals(admin.id(), registro.getUsuarioId());
        assertTrue(registro.getDetalles().contains("consultar_roles_permisos"));
        assertTrue(registro.getDetalles().contains("Guarda de Seguridad"));
        assertTrue(registro.getDetalles().contains("permiso_id: " + permiso));
        for (Usuario original : cuentas) {
            Usuario actual = gestor.ejecutar(r -> r.crearUsuarioRepository().buscarPorId(original.getId()).orElseThrow());
            assertEquals(original.getRolId(), actual.getRolId());
            assertEquals(original.getEmpresaId(), actual.getEmpresaId());
            assertEquals(original.isEstaActivo(), actual.isEstaActivo());
        }
    }

    @Test
    @DisplayName("Retirar elimina solo la relación, conserva el catálogo y no duplica auditorías si se repite")
    void retiraSinEliminarElPermiso() {
        Integer rol = rol("Guarda de Seguridad");
        Integer permiso = permiso("consultar_visita_porteria");
        assertTrue(admin.permisos.retirarPermiso(admin.id(), rol, permiso));
        assertFalse(admin.permisos.retirarPermiso(admin.id(), rol, permiso));
        assertFalse(permisosDe(rol).contains(permiso));
        assertTrue(admin.permisos.listarPermisos(admin.id()).stream().anyMatch(p -> permiso.equals(p.getId())));
        assertEquals(1, auditorias(rol, "RETIRO_PERMISO_ROL").size());
        assertTrue(auditorias(rol, "RETIRO_PERMISO_ROL").get(0).getDetalles().contains("sin asignar"));
    }

    @Test
    @DisplayName("Asignar y retirar se reflejan en las sesiones ya abiertas sin modificar sus roles")
    void aplicaCambiosASesionesAbiertas() {
        Aplicacion guarda = new Aplicacion("guarda@sica.local");
        Aplicacion segundo = new Aplicacion("guarda@sica.local");
        Integer rol = guarda.sesion.getRolId();
        Integer permiso = permiso("consultar_roles_permisos");
        assertThrows(SecurityException.class, () -> guarda.permisos.listarRoles(guarda.id()));
        assertTrue(admin.permisos.asignarPermiso(admin.id(), rol, permiso));
        assertEquals(4, guarda.permisos.listarRoles(guarda.id()).size());
        assertEquals(4, segundo.permisos.listarRoles(segundo.id()).size());
        assertTrue(admin.permisos.retirarPermiso(admin.id(), rol, permiso));
        assertThrows(SecurityException.class, () -> guarda.permisos.listarRoles(guarda.id()));
        assertThrows(SecurityException.class, () -> segundo.permisos.listarRoles(segundo.id()));
        assertEquals(rol, guarda.sesion.getRolId());
        assertTrue(guarda.contexto.consultarActual().isPresent());
    }

    @Test
    @DisplayName("Si falla la auditoría de asignación, la relación se deshace y el fallo queda registrado aparte")
    void deshaceAsignacionFallida() {
        Integer rol = rol("Guarda de Seguridad");
        Integer permiso = permiso("consultar_roles_permisos");
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> admin.permisos.asignarPermiso(admin.id(), rol, permiso));
        assertFalse(permisosDe(rol).contains(permiso));
        assertTrue(auditorias(rol, "ASIGNACION_PERMISO_ROL").isEmpty());
        assertEquals(1, auditorias(rol, "ASIGNACION_PERMISO_ROL_FALLIDA").size());
        assertEquals(2, acceso.conexionesDeAuditoria().stream().distinct().count());
        assertTrue(admin.permisos.asignarPermiso(admin.id(), rol, permiso));
        assertTrue(permisosDe(rol).contains(permiso));
    }

    @Test
    @DisplayName("Si falla la auditoría de retiro, el permiso continúa asignado y se puede reintentar")
    void deshaceRetiroFallido() {
        Aplicacion guarda = new Aplicacion("guarda@sica.local");
        Integer rol = guarda.sesion.getRolId();
        Integer permiso = permiso("consultar_visita_porteria");
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> admin.permisos.retirarPermiso(admin.id(), rol, permiso));
        assertTrue(permisosDe(rol).contains(permiso));
        assertEquals(guarda.id(), guarda.ejecutor.ejecutar(guarda.id(), "consultar_visita_porteria", (r, u) -> u.getId()));
        assertTrue(auditorias(rol, "RETIRO_PERMISO_ROL").isEmpty());
        assertEquals(1, auditorias(rol, "RETIRO_PERMISO_ROL_FALLIDO").size());
        assertTrue(admin.permisos.retirarPermiso(admin.id(), rol, permiso));
        assertThrows(SecurityException.class, () -> guarda.ejecutor.ejecutar(guarda.id(), "consultar_visita_porteria", (r, u) -> u.getId()));
    }

    @Test
    @DisplayName("Dos superusuarios que asignan simultáneamente confirman una sola relación y una sola auditoría")
    void coordinaAsignacionesSimultaneas() throws Exception { comprobarSimultaneas(true); }

    @Test
    @DisplayName("Dos superusuarios que retiran simultáneamente confirman un solo retiro y una sola auditoría")
    void coordinaRetirosSimultaneos() throws Exception { comprobarSimultaneas(false); }

    private void comprobarSimultaneas(boolean asignar) throws Exception {
        Aplicacion segunda = new Aplicacion("admin@sica.local");
        Integer rol = rol("Guarda de Seguridad");
        Integer permiso = permiso(asignar ? "consultar_roles_permisos" : "consultar_visita_porteria");
        CountDownLatch inicio = new CountDownLatch(1);
        var hilos = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Boolean>> resultados = new ArrayList<>();
            for (Aplicacion app : List.of(admin, segunda)) resultados.add(hilos.submit(() -> {
                if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("No comenzó la prueba.");
                return asignar ? app.permisos.asignarPermiso(app.id(), rol, permiso)
                        : app.permisos.retirarPermiso(app.id(), rol, permiso);
            }));
            inicio.countDown();
            int cambios = 0;
            for (var resultado : resultados) if (resultado.get(30, TimeUnit.SECONDS)) cambios++;
            assertEquals(1, cambios);
        } finally {
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(25, TimeUnit.SECONDS));
        }
        assertEquals(asignar, permisosDe(rol).contains(permiso));
        assertEquals(1, auditorias(rol, asignar ? "ASIGNACION_PERMISO_ROL" : "RETIRO_PERMISO_ROL").size());
    }

    private Integer rol(String nombre) { return gestor.ejecutar(r -> r.crearRolRepository().buscarPorNombre(nombre).orElseThrow().getId()); }
    private Integer permiso(String nombre) { return gestor.ejecutar(r -> r.crearPermisoRepository().buscarPorNombre(nombre).orElseThrow().getId()); }
    private Set<Integer> permisosDe(Integer rol) {
        return gestor.ejecutar(r -> r.crearPermisoRepository().listarPorRol(rol)).stream().map(Permiso::getId).collect(Collectors.toSet());
    }
    private List<BitacoraAuditoria> auditorias(Integer rol, String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorRegistro("rol_permisos", rol))
                .stream().filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }
    private Set<Long> idsAuditoriasAdmin() {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(admin.id()))
                .stream().map(BitacoraAuditoria::getId).collect(Collectors.toSet());
    }
}
