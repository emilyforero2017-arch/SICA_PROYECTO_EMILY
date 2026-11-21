package com.sica.integration;

import com.sica.infrastructure.database.GestorTransaccionesJdbc;
import com.sica.infrastructure.security.ServicioContrasenasPbkdf2;
import com.sica.model.application.usecase.*;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Persona;
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sica.pruebas.jdbc", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Recorridos: gestionar trabajadores y sus restricciones")
class GestionTrabajadoresYRestriccionesIT {

    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private final List<Aplicacion> aplicaciones = new ArrayList<>();
    private Aplicacion ana;
    private Persona pedro;

    // Cada instancia tiene su propia sesión, igual que dos aplicaciones en computadores distintos.
    private final class Aplicacion implements AutoCloseable {
        final ContextoSesion contexto = new ContextoSesion();
        final EjecutorOperaciones ejecutor = new EjecutorOperaciones(gestor, contexto);
        final PersonaCase personas = new PersonaCase(ejecutor);
        final RestriccionAccesoCase seguridad = new RestriccionAccesoCase(ejecutor);
        final SesionUsuario sesion;

        Aplicacion(String correo) {
            char[] clave = "SicaDemo2026!".toCharArray();
            try {
                sesion = new IniciarSesionCase(gestor, new ServicioContrasenasPbkdf2())
                        .iniciar(contexto, correo, clave);
            } finally {
                Arrays.fill(clave, '\0');
            }
        }

        Integer id() { return sesion.getUsuarioId(); }
        @Override public void close() { new CerrarSesionCase(contexto).cerrar(); }
    }

    @BeforeAll
    void prepararBase() throws Exception { base = BaseDatosTemporal.crear(); }

    @BeforeEach
    void prepararTrabajador() {
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        ana = ingresar("ana@sol.sica.local");
        pedro = ana.personas.registrarTrabajador(ana.id(), "Pedro prueba", documentoNuevo(), "foto-pedro.png");
    }

    @AfterEach
    void cerrarSesiones() {
        for (Aplicacion app : aplicaciones) app.close();
        aplicaciones.clear();
    }

    @AfterAll
    void eliminarBase() throws SQLException { if (base != null) base.close(); }

    @Test
    @DisplayName("Actualizar nombre y foto conserva identidad, empresa y prohibición")
    void actualizaDatosConservandoLaRestriccion() {
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        supervisor.seguridad.imponerProhibicion(supervisor.id(), pedro.getId(), "Revisión de seguridad");
        Persona anterior = consultar(pedro.getId());

        ana.personas.actualizarTrabajador(ana.id(), pedro.getId(), "Pedro actualizado", "foto-renovada.png");

        Persona guardado = consultar(pedro.getId());
        assertAll(
                () -> assertEquals("Pedro actualizado", guardado.getNombre()),
                () -> assertEquals("foto-renovada.png", guardado.getUrlFoto()),
                () -> assertEquals(anterior.getDocumentoIdentidad(), guardado.getDocumentoIdentidad()),
                () -> assertEquals(anterior.getEmpresaId(), guardado.getEmpresaId()),
                () -> assertEquals(anterior.getEstadoAccesoId(), guardado.getEstadoAccesoId()),
                () -> assertEquals(Boolean.TRUE, guardado.getVinculacionLaboralActiva()));
        BitacoraAuditoria registro = auditoria(pedro.getId(), "ACTUALIZACION_TRABAJADOR").get(0);
        assertTrue(registro.getDetalles().contains("Pedro prueba"));
        assertTrue(registro.getDetalles().contains("Pedro actualizado"));
        assertEquals(1, auditoria(pedro.getId(), "PROHIBICION_INGRESO").size());
    }

    @Test
    @DisplayName("Finalizar el vínculo conserva la empresa, la prohibición y el historial")
    void finalizaVinculoSinBorrarNiRetirarProhibicion() {
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        supervisor.seguridad.imponerProhibicion(supervisor.id(), pedro.getId(), "Pendiente de revisión");
        Persona anterior = consultar(pedro.getId());

        ana.personas.finalizarVinculacionLaboral(ana.id(), pedro.getId(), "Terminó su contrato");

        Persona guardado = consultar(pedro.getId());
        assertAll(
                () -> assertEquals(Boolean.FALSE, guardado.getVinculacionLaboralActiva()),
                () -> assertEquals(anterior.getDocumentoIdentidad(), guardado.getDocumentoIdentidad()),
                () -> assertEquals(anterior.getEmpresaId(), guardado.getEmpresaId()),
                () -> assertEquals(anterior.getEstadoAccesoId(), guardado.getEstadoAccesoId()),
                () -> assertEquals(anterior.getUrlFoto(), guardado.getUrlFoto()));
        assertTrue(ana.personas.listarTrabajadores(ana.id()).stream()
                .anyMatch(p -> p.getId().equals(pedro.getId()) && Boolean.FALSE.equals(p.getVinculacionLaboralActiva())));
        assertEquals(1, auditoria(pedro.getId(), "BAJA_TRABAJADOR").size());
        assertEquals(1, auditoria(pedro.getId(), "REGISTRO_TRABAJADOR").size());
        assertThrows(IllegalStateException.class,
                () -> ana.personas.finalizarVinculacionLaboral(ana.id(), pedro.getId(), "Repetido"));
        assertEquals(1, auditoria(pedro.getId(), "BAJA_TRABAJADOR").size());
    }

    @Test
    @DisplayName("Ana no puede actualizar ni retirar trabajadores de la empresa de Marta")
    void respetaLaEmpresaDelFuncionario() {
        Aplicacion marta = ingresar("marta@luna.sica.local");
        Persona trabajadorLuna = marta.personas.registrarTrabajador(
                marta.id(), "Trabajador de Luna", documentoNuevo(), "foto-luna.png");

        assertThrows(IllegalArgumentException.class, () -> ana.personas.actualizarTrabajador(
                ana.id(), trabajadorLuna.getId(), "Cambio indebido", "otra-foto.png"));
        assertThrows(IllegalArgumentException.class, () -> ana.personas.finalizarVinculacionLaboral(
                ana.id(), trabajadorLuna.getId(), "Retiro indebido"));

        Persona conservado = consultar(trabajadorLuna.getId());
        assertEquals(trabajadorLuna.getNombre(), conservado.getNombre());
        assertEquals(trabajadorLuna.getEmpresaId(), conservado.getEmpresaId());
        assertEquals(Boolean.TRUE, conservado.getVinculacionLaboralActiva());
        assertTrue(auditoria(trabajadorLuna.getId(), "ACTUALIZACION_TRABAJADOR").isEmpty());
        assertTrue(auditoria(trabajadorLuna.getId(), "BAJA_TRABAJADOR").isEmpty());
    }

    @Test
    @DisplayName("Las operaciones de trabajadores no se pueden usar para editar invitados")
    void noConfundeInvitadosConTrabajadores() {
        Persona laura = ana.personas.registrarInvitado(ana.id(), "Laura prueba", documentoNuevo(), "foto-laura.png");
        assertThrows(IllegalArgumentException.class,
                () -> ana.personas.actualizarTrabajador(ana.id(), laura.getId(), "Otra identidad", "otra.png"));
        assertThrows(IllegalArgumentException.class,
                () -> ana.personas.finalizarVinculacionLaboral(ana.id(), laura.getId(), "No corresponde"));
        Persona conservada = consultar(laura.getId());
        assertEquals(laura.getNombre(), conservada.getNombre());
        assertEquals(laura.getUrlFoto(), conservada.getUrlFoto());
        assertNull(conservada.getEmpresaId());
        assertNull(conservada.getVinculacionLaboralActiva());
    }

    @Test
    @DisplayName("Retirar una prohibición no reactiva un vínculo laboral finalizado")
    void retirarProhibicionConservaLaBajaLaboral() {
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        supervisor.seguridad.imponerProhibicion(supervisor.id(), pedro.getId(), "Situación por revisar");
        ana.personas.finalizarVinculacionLaboral(ana.id(), pedro.getId(), "Finalizó su contrato");
        supervisor.seguridad.retirarProhibicion(supervisor.id(), pedro.getId(), "Situación aclarada");

        Persona guardado = consultar(pedro.getId());
        assertEquals(estado("Activo"), guardado.getEstadoAccesoId());
        assertEquals(Boolean.FALSE, guardado.getVinculacionLaboralActiva());
        assertEquals(pedro.getEmpresaId(), guardado.getEmpresaId());
        ConsultaRestriccionAcceso situacion = supervisor.seguridad
                .consultarSituacion(supervisor.id(), pedro.getDocumentoIdentidad()).orElseThrow();
        assertEquals(2, situacion.getHistorial().size());
        assertEquals("RETIRO_PROHIBICION_INGRESO", situacion.getHistorial().get(0).getAccionRealizada());
        assertEquals("PROHIBICION_INGRESO", situacion.getHistorial().get(1).getAccionRealizada());
        assertEquals(supervisor.sesion.getNombre(), situacion.getNombreResponsable(supervisor.id()));
    }

    @Test
    @DisplayName("La prohibición de un invitado conserva sus datos y no crea autorizaciones")
    void gestionaRestriccionDeInvitadoSinCrearUnaVisita() {
        Persona laura = ana.personas.registrarInvitado(ana.id(), "Laura prueba", documentoNuevo(), "foto-laura.png");
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        supervisor.seguridad.imponerProhibicion(supervisor.id(), laura.getId(), "Revisión requerida");
        assertEquals(estado("Con Prohibición de Ingreso"), consultar(laura.getId()).getEstadoAccesoId());
        supervisor.seguridad.retirarProhibicion(supervisor.id(), laura.getId(), "Revisión completada");

        Persona conservada = consultar(laura.getId());
        assertAll(
                () -> assertEquals(estado("Activo"), conservada.getEstadoAccesoId()),
                () -> assertEquals(laura.getNombre(), conservada.getNombre()),
                () -> assertEquals(laura.getDocumentoIdentidad(), conservada.getDocumentoIdentidad()),
                () -> assertEquals(laura.getUrlFoto(), conservada.getUrlFoto()),
                () -> assertNull(conservada.getEmpresaId()),
                () -> assertNull(conservada.getVinculacionLaboralActiva()));
        assertTrue(gestor.ejecutar(r -> r.crearVisitaRepository().listarPorPersona(laura.getId())).isEmpty());
    }

    @Test
    @DisplayName("Funcionarios y guardas no imponen ni retiran prohibiciones ni consultan su historial")
    void respetaPermisosDeSeguridad() {
        Aplicacion guarda = ingresar("guarda@sica.local");
        for (Aplicacion app : List.of(ana, guarda)) {
            assertThrows(SecurityException.class,
                    () -> app.seguridad.imponerProhibicion(app.id(), pedro.getId(), "No autorizado"));
            assertThrows(SecurityException.class,
                    () -> app.seguridad.retirarProhibicion(app.id(), pedro.getId(), "No autorizado"));
            assertThrows(SecurityException.class,
                    () -> app.seguridad.consultarSituacion(app.id(), pedro.getDocumentoIdentidad()));
        }
        assertEquals(estado("Activo"), consultar(pedro.getId()).getEstadoAccesoId());
        assertTrue(auditoria(pedro.getId(), "PROHIBICION_INGRESO").isEmpty());
    }

    @Test
    @DisplayName("Exige motivos para imponer y retirar una prohibición")
    void exigeMotivosDeSeguridad() {
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        assertThrows(IllegalArgumentException.class,
                () -> supervisor.seguridad.imponerProhibicion(supervisor.id(), pedro.getId(), " "));
        assertEquals(estado("Activo"), consultar(pedro.getId()).getEstadoAccesoId());
        supervisor.seguridad.imponerProhibicion(supervisor.id(), pedro.getId(), "Revisión pendiente");
        assertThrows(IllegalArgumentException.class,
                () -> supervisor.seguridad.retirarProhibicion(supervisor.id(), pedro.getId(), null));
        assertEquals(estado("Con Prohibición de Ingreso"), consultar(pedro.getId()).getEstadoAccesoId());
        assertTrue(auditoria(pedro.getId(), "RETIRO_PROHIBICION_INGRESO").isEmpty());
    }

    @Test
    @DisplayName("Repetir una decisión de seguridad no duplica sus registros confirmados")
    void rechazaDecisionesRepetidas() {
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        supervisor.seguridad.imponerProhibicion(supervisor.id(), pedro.getId(), "Revisión pendiente");
        assertThrows(IllegalStateException.class,
                () -> supervisor.seguridad.imponerProhibicion(supervisor.id(), pedro.getId(), "Repetida"));
        assertEquals(1, auditoria(pedro.getId(), "PROHIBICION_INGRESO").size());
        supervisor.seguridad.retirarProhibicion(supervisor.id(), pedro.getId(), "Situación aclarada");
        assertThrows(IllegalStateException.class,
                () -> supervisor.seguridad.retirarProhibicion(supervisor.id(), pedro.getId(), "Repetida"));
        assertEquals(1, auditoria(pedro.getId(), "RETIRO_PROHIBICION_INGRESO").size());
    }

    @Test
    @DisplayName("Si falla la auditoría al actualizar, conserva el nombre y la foto anteriores")
    void deshaceActualizacionSiFallaLaAuditoria() {
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class,
                () -> ana.personas.actualizarTrabajador(ana.id(), pedro.getId(), "No debe quedar", "no-debe.png"));
        Persona conservado = consultar(pedro.getId());
        assertEquals(pedro.getNombre(), conservado.getNombre());
        assertEquals(pedro.getUrlFoto(), conservado.getUrlFoto());
        assertTrue(auditoria(pedro.getId(), "ACTUALIZACION_TRABAJADOR").isEmpty());
        assertEquals(1, auditoria(pedro.getId(), "ACTUALIZACION_TRABAJADOR_FALLIDA").size());
    }

    @Test
    @DisplayName("Si falla la auditoría de la baja, la vinculación permanece activa")
    void deshaceBajaSiFallaLaAuditoria() {
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class,
                () -> ana.personas.finalizarVinculacionLaboral(ana.id(), pedro.getId(), "Fin de contrato"));
        assertEquals(Boolean.TRUE, consultar(pedro.getId()).getVinculacionLaboralActiva());
        assertTrue(auditoria(pedro.getId(), "BAJA_TRABAJADOR").isEmpty());
        assertEquals(1, auditoria(pedro.getId(), "BAJA_TRABAJADOR_FALLIDA").size());
    }

    @Test
    @DisplayName("Una prohibición fallida queda en auditoría, pero no como restricción vigente")
    void deshaceProhibicionSiFallaLaAuditoria() {
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class,
                () -> supervisor.seguridad.imponerProhibicion(supervisor.id(), pedro.getId(), "Revisión pendiente"));
        assertEquals(estado("Activo"), consultar(pedro.getId()).getEstadoAccesoId());
        assertEquals(1, auditoria(pedro.getId(), "PROHIBICION_INGRESO_FALLIDA").size());
        assertTrue(supervisor.seguridad.consultarSituacion(supervisor.id(), pedro.getDocumentoIdentidad())
                .orElseThrow().getHistorial().isEmpty());
    }

    @Test
    @DisplayName("Si falla el retiro, la prohibición permanece vigente y conserva su historial")
    void deshaceRetiroSiFallaLaAuditoria() {
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        supervisor.seguridad.imponerProhibicion(supervisor.id(), pedro.getId(), "Revisión pendiente");
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class,
                () -> supervisor.seguridad.retirarProhibicion(supervisor.id(), pedro.getId(), "Situación aclarada"));
        assertEquals(estado("Con Prohibición de Ingreso"), consultar(pedro.getId()).getEstadoAccesoId());
        assertEquals(1, auditoria(pedro.getId(), "RETIRO_PROHIBICION_INGRESO_FALLIDO").size());
        ConsultaRestriccionAcceso situacion = supervisor.seguridad
                .consultarSituacion(supervisor.id(), pedro.getDocumentoIdentidad()).orElseThrow();
        assertEquals(1, situacion.getHistorial().size());
        assertEquals("PROHIBICION_INGRESO", situacion.getHistorial().get(0).getAccionRealizada());
    }

    @Test
    @DisplayName("Dos sesiones simultáneas no imponen dos veces la misma prohibición")
    void coordinaDecisionesDesdeDosSesiones() throws Exception {
        Aplicacion primera = ingresar("supervisor@sica.local");
        Aplicacion segunda = ingresar("supervisor@sica.local");
        CountDownLatch inicio = new CountDownLatch(1);
        var hilos = Executors.newFixedThreadPool(2);
        try {
            var uno = hilos.submit(decidirProhibicion(primera, inicio));
            var dos = hilos.submit(decidirProhibicion(segunda, inicio));
            inicio.countDown();
            assertEquals(Set.of("APLICADA", "YA_EXISTIA"), Set.of(
                    uno.get(30, TimeUnit.SECONDS), dos.get(30, TimeUnit.SECONDS)));
        } finally {
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(25, TimeUnit.SECONDS));
        }
        assertEquals(1, auditoria(pedro.getId(), "PROHIBICION_INGRESO").size());
        assertTrue(auditoria(pedro.getId(), "PROHIBICION_INGRESO_FALLIDA").isEmpty());
    }

    private Callable<String> decidirProhibicion(Aplicacion app, CountDownLatch inicio) {
        return () -> {
            if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("La prueba no pudo comenzar.");
            try {
                app.seguridad.imponerProhibicion(app.id(), pedro.getId(), "Revisión simultánea");
                return "APLICADA";
            } catch (IllegalStateException error) {
                if (!"La persona ya tiene una prohibición de ingreso.".equals(error.getMessage())) throw error;
                return "YA_EXISTIA";
            }
        };
    }

    private Aplicacion ingresar(String correo) {
        Aplicacion app = new Aplicacion(correo);
        aplicaciones.add(app);
        return app;
    }

    private Persona consultar(Integer personaId) {
        return gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorId(personaId).orElseThrow());
    }

    private Integer estado(String nombre) {
        return gestor.ejecutar(r -> r.crearEstadoAccesoPersonaRepository().buscarPorNombre(nombre).orElseThrow().getId());
    }

    private List<BitacoraAuditoria> auditoria(Integer personaId, String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorRegistro("personas", personaId))
                .stream().filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }

    private String documentoNuevo() {
        return "GS" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
