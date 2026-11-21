package com.sica.integration;

import com.sica.infrastructure.database.GestorTransaccionesJdbc;
import com.sica.infrastructure.security.ServicioContrasenasPbkdf2;
import com.sica.model.application.usecase.*;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.entity.Visita;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
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
@DisplayName("Recorrido: visita programada desde el registro hasta la salida")
class VisitaProgramadaIT {

    private static final ZoneId ZONA = ZoneId.of("America/Bogota");
    private static final LocalDateTime CITA = LocalDateTime.of(2026, 12, 15, 10, 0);
    private static final String CLAVE = "SicaDemo2026!";
    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private RelojPrueba reloj;
    private final List<Aplicacion> aplicaciones = new ArrayList<>();
    private Aplicacion ana;
    private Aplicacion guarda;
    private Integer empresaId;

    // Las sesiones son independientes; los datos se comparten mediante MySQL.
    private final class Aplicacion implements AutoCloseable {
        final ContextoSesion contexto = new ContextoSesion();
        final EjecutorOperaciones ejecutor = new EjecutorOperaciones(gestor, contexto);
        final VisitaCase visitas = new VisitaCase(ejecutor, reloj);
        final ControlAccesoProgramadoCase entradas = new ControlAccesoProgramadoCase(ejecutor, reloj);
        final ControlSalidaCase salidas = new ControlSalidaCase(ejecutor, reloj);
        final SesionUsuario sesion;

        Aplicacion(String correo) {
            char[] clave = CLAVE.toCharArray();
            try {
                sesion = new IniciarSesionCase(gestor, new ServicioContrasenasPbkdf2())
                        .iniciar(contexto, correo, clave);
            } finally { Arrays.fill(clave, '\0'); }
        }

        Integer id() { return sesion.getUsuarioId(); }
        @Override public void close() { new CerrarSesionCase(contexto).cerrar(); }
    }

    // Mueve solo la hora de la prueba: no cambia la del computador ni la del servidor.
    private static final class RelojPrueba extends Clock {
        private Instant actual;
        private final ArrayDeque<Instant> siguientes = new ArrayDeque<>();
        private int lecturas;

        synchronized void marcar(LocalDateTime hora) {
            siguientes.clear();
            actual = hora.atZone(ZONA).toInstant();
            lecturas = 0;
        }

        synchronized void pasarDuranteLaRevision(LocalDateTime primera, LocalDateTime segunda) {
            marcar(primera);
            siguientes.add(primera.atZone(ZONA).toInstant());
            siguientes.add(segunda.atZone(ZONA).toInstant());
        }

        synchronized int lecturas() { return lecturas; }
        @Override public ZoneId getZone() { return ZONA; }
        @Override public Clock withZone(ZoneId zona) { return Clock.fixed(instant(), zona); }
        @Override public synchronized Instant instant() {
            lecturas++;
            if (!siguientes.isEmpty()) actual = siguientes.removeFirst();
            return actual;
        }
    }

    @BeforeAll
    void prepararBase() throws Exception { base = BaseDatosTemporal.crear(); }

    @BeforeEach
    void prepararSesiones() {
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        reloj = new RelojPrueba();
        reloj.marcar(CITA.minusHours(2));
        String marca = UUID.randomUUID().toString().replace("-", "");
        // La empresa es distinta en cada prueba para poder desactivarla sin afectar las demás.
        Usuario cuenta = gestor.ejecutar(r -> {
            Empresa empresa = r.crearEmpresaRepository().guardar(new Empresa("Sol prueba " + marca, "Ana prueba"));
            empresaId = empresa.getId();
            Usuario modelo = r.crearUsuarioRepository().buscarPorEmail("ana@sol.sica.local").orElseThrow();
            return r.crearUsuarioRepository().guardar(new Usuario("Ana prueba", "ana-" + marca + "@prueba.invalid",
                    modelo.getPasswordHash(), modelo.getRolId(), empresaId));
        });
        ana = ingresar(cuenta.getEmail());
        guarda = ingresar("guarda@sica.local");
    }

    @AfterEach
    void cerrarSesiones() {
        for (Aplicacion app : aplicaciones) app.close();
        aplicaciones.clear();
    }

    @AfterAll
    void eliminarBase() throws SQLException { if (base != null) base.close(); }

    @Test
    @DisplayName("Ana agenda a Laura, portería la identifica y guarda su entrada y salida con auditoría")
    void completaElRecorrido() {
        Visita cita = agendar();
        Persona laura = persona(cita);
        assertNull(laura.getEmpresaId());
        assertEquals(empresaId, cita.getEmpresaDestinoId());
        assertEquals(ana.id(), cita.getFuncionarioDestinatarioId());
        assertEquals(ana.id(), cita.getAprobadaPorId());
        assertEstado(cita.getId(), "Aprobado");
        assertEquals(1, auditorias("personas", laura.getId(), "REGISTRO_INVITADO").size());
        assertEquals(1, auditorias("visitas", cita.getId(), "PREREGISTRO_VISITA").size());

        reloj.marcar(CITA.minusMinutes(30));
        ConsultaIngresoProgramado consulta = guarda.entradas.consultar(guarda.id(), laura.getDocumentoIdentidad()).orElseThrow();
        DetalleIngresoProgramado detalle = consulta.getVisitas().get(0);
        assertAll(
                () -> assertEquals(laura.getId(), consulta.getPersona().getId()),
                () -> assertEquals("foto-laura.png", consulta.getPersona().getUrlFoto()),
                () -> assertEquals("Activo", consulta.getEstadoAcceso()),
                () -> assertEquals(ana.sesion.getNombre(), detalle.getFuncionario()),
                () -> assertTrue(detalle.getEmpresa().startsWith("Sol prueba ")),
                () -> assertEquals(CITA.minusHours(1), detalle.getHorario().getDesde()),
                () -> assertEquals(CITA.plusHours(1).withSecond(59).withNano(999_999_999), detalle.getHorario().getHasta()),
                () -> assertTrue(detalle.isPermiteIngreso()),
                () -> assertNull(visita(cita.getId()).getFechaEntrada()));

        entrar(cita);
        assertEstado(cita.getId(), "Dentro");
        assertEquals(CITA.minusMinutes(30), visita(cita.getId()).getFechaEntrada());
        assertEquals(cita.getId(), guarda.salidas.consultarIngresoAbierto(guarda.id(), laura.getDocumentoIdentidad())
                .orElseThrow().getId());
        reloj.marcar(CITA.plusHours(3));
        salir(cita);
        Visita cerrada = visita(cita.getId());
        assertEstado(cita.getId(), "Fuera");
        assertEquals(CITA.minusMinutes(30), cerrada.getFechaEntrada());
        assertEquals(CITA.plusHours(3), cerrada.getFechaSalida());
        assertNull(cerrada.getFechaCierreSistema());
        assertEquals("ABC123", cerrada.getVehiculoPlaca());
        assertTrue(guarda.salidas.consultarIngresoAbierto(guarda.id(), laura.getDocumentoIdentidad()).isEmpty());
        assertEquals(guarda.id(), auditorias("visitas", cita.getId(), "REGISTRO_ENTRADA").get(0).getUsuarioId());
        assertEquals(guarda.id(), auditorias("visitas", cita.getId(), "REGISTRO_SALIDA").get(0).getUsuarioId());
    }

    @Test
    @DisplayName("La misma invitada puede tener otra cita en otra empresa sin duplicar su identidad")
    void reutilizaIdentidadEntreEmpresas() {
        Visita primera = agendar();
        Persona laura = persona(primera);
        Aplicacion marta = ingresar("marta@luna.sica.local");
        Visita segunda = marta.visitas.preregistrar(marta.id(), laura.getDocumentoIdentidad(),
                "No debe reemplazar el nombre", "otra-foto.png", CITA.plusDays(1), null);
        assertEquals(primera.getPersonaId(), segunda.getPersonaId());
        assertNotEquals(primera.getEmpresaDestinoId(), segunda.getEmpresaDestinoId());
        assertEquals("Laura prueba", persona(segunda).getNombre());
        assertEquals("foto-laura.png", persona(segunda).getUrlFoto());
        assertNull(persona(segunda).getEmpresaId());
        assertEquals(1, auditorias("personas", laura.getId(), "REGISTRO_INVITADO").size());
        assertEquals(2, gestor.ejecutar(r -> r.crearVisitaRepository().listarPorPersona(laura.getId())).size());
    }

    @Test
    @DisplayName("Antes de las nueve no ingresa ni expira; a las nueve exactas sí ingresa")
    void respetaInicioExacto() {
        Visita cita = agendar();
        reloj.marcar(CITA.minusHours(1).minusNanos(1));
        assertThrows(IllegalStateException.class, () -> entrar(cita));
        assertEstado(cita.getId(), "Aprobado");
        assertNull(visita(cita.getId()).getFechaEntrada());
        reloj.marcar(CITA.minusHours(1));
        entrar(cita);
        assertEquals(CITA.minusHours(1), visita(cita.getId()).getFechaEntrada());
    }

    @Test
    @DisplayName("Permite todo el minuto de las once y MySQL no redondea la entrada a las once y uno")
    void permiteElUltimoMinutoCompleto() {
        Visita cita = agendar();
        reloj.marcar(CITA.plusHours(1).withSecond(59).withNano(999_999_999));
        entrar(cita);
        assertEstado(cita.getId(), "Dentro");
        assertEquals(CITA.plusHours(1).withSecond(59), visita(cita.getId()).getFechaEntrada());
        assertTrue(auditorias("visitas", cita.getId(), "EXPIRACION_VISITA").isEmpty());
    }

    @Test
    @DisplayName("Consultar a las once y uno conserva la expiración sin duplicar la auditoría")
    void consultaExpiraLaAutorizacion() {
        Visita cita = agendar();
        reloj.marcar(CITA.plusHours(1).plusMinutes(1));
        for (int i = 0; i < 2; i++) {
            DetalleIngresoProgramado detalle = guarda.entradas.consultar(guarda.id(), documento(cita))
                    .orElseThrow().getVisitas().get(0);
            assertFalse(detalle.isPermiteIngreso());
            assertTrue(detalle.getMensaje().contains("expirada"));
        }
        assertEstado(cita.getId(), "Expirado");
        assertSinMovimiento(cita);
        assertEquals(1, auditorias("visitas", cita.getId(), "EXPIRACION_VISITA").size());
        assertNull(auditorias("visitas", cita.getId(), "EXPIRACION_VISITA").get(0).getUsuarioId());
    }

    @Test
    @DisplayName("Un intento tardío conserva Expirado aunque termine avisando que no puede entrar")
    void entradaTardiaConfirmaLaExpiracion() {
        Visita cita = agendar();
        reloj.marcar(CITA.plusHours(1).plusMinutes(1));
        assertThrows(IllegalStateException.class, () -> entrar(cita));
        assertEstado(cita.getId(), "Expirado");
        assertSinMovimiento(cita);
        assertEquals(1, auditorias("visitas", cita.getId(), "EXPIRACION_VISITA").size());
        assertTrue(auditorias("visitas", cita.getId(), "REGISTRO_ENTRADA_FALLIDO").isEmpty());
    }

    @Test
    @DisplayName("Vuelve a comprobar el horario si la revisión comienza a las once y termina a las once y uno")
    void noUsaUnaHoraAnteriorALaRevision() {
        Visita cita = agendar();
        reloj.pasarDuranteLaRevision(CITA.plusHours(1).withSecond(59), CITA.plusHours(1).plusMinutes(1));
        assertThrows(IllegalStateException.class, () -> entrar(cita));
        assertTrue(reloj.lecturas() >= 2);
        assertEstado(cita.getId(), "Expirado");
        assertSinMovimiento(cita);
        assertEquals(1, auditorias("visitas", cita.getId(), "EXPIRACION_VISITA").size());
    }

    @Test
    @DisplayName("Una prohibición posterior impide entrar; retirarla no cambia ni duplica la cita")
    void respetaUnaProhibicionPosterior() {
        Visita cita = agendar();
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        RestriccionAccesoCase restricciones = new RestriccionAccesoCase(supervisor.ejecutor);
        restricciones.imponerProhibicion(supervisor.id(), cita.getPersonaId(), "Revisión pendiente");
        reloj.marcar(CITA);
        DetalleIngresoProgramado detalle = guarda.entradas.consultar(guarda.id(), documento(cita))
                .orElseThrow().getVisitas().get(0);
        assertFalse(detalle.isPermiteIngreso());
        assertThrows(IllegalStateException.class, () -> entrar(cita));
        assertEstado(cita.getId(), "Aprobado");
        assertSinMovimiento(cita);
        restricciones.retirarProhibicion(supervisor.id(), cita.getPersonaId(), "Situación aclarada");
        entrar(cita);
        assertEstado(cita.getId(), "Dentro");
        assertEquals(1, auditorias("visitas", cita.getId(), "REGISTRO_ENTRADA").size());
    }

    @Test
    @DisplayName("Desactivar la empresa impide nuevas entradas y permite registrar la salida pendiente")
    void empresaRetiradaPermiteSalirPeroNoEntrar() {
        Visita dentro = agendar();
        Visita pendiente = agendar();
        reloj.marcar(CITA);
        entrar(dentro);
        Aplicacion admin = ingresar("admin@sica.local");
        new EmpresaUseCase(admin.ejecutor).desactivar(admin.id(), empresaId, "La empresa se retiró");
        assertThrows(IllegalStateException.class, () -> entrar(pendiente));
        assertSinMovimiento(pendiente);
        reloj.marcar(CITA.plusHours(2));
        salir(dentro);
        assertEstado(dentro.getId(), "Fuera");
        assertEquals(CITA.plusHours(2), visita(dentro.getId()).getFechaSalida());
        assertFalse(gestor.ejecutar(r -> r.crearUsuarioRepository().buscarPorId(ana.id()).orElseThrow()).isEstaActivo());
    }

    @Test
    @DisplayName("Comprueba permisos y que el documento pertenezca a la visita")
    void compruebaPermisosYDocumento() {
        Visita cita = agendar();
        Visita otra = agendar();
        reloj.marcar(CITA);
        assertThrows(IllegalStateException.class,
                () -> guarda.entradas.registrarEntrada(guarda.id(), documento(otra), cita.getId()));
        assertThrows(SecurityException.class, () -> ana.entradas.registrarEntrada(ana.id(), documento(cita), cita.getId()));
        assertThrows(SecurityException.class, () -> guarda.visitas.preregistrar(
                guarda.id(), cita.getPersonaId(), CITA, null));
        entrar(cita);
        assertThrows(IllegalArgumentException.class,
                () -> guarda.salidas.registrarSalida(guarda.id(), documento(otra), cita.getId()));
        assertEstado(cita.getId(), "Dentro");
        assertSinMovimiento(otra);
    }

    @Test
    @DisplayName("Una visita usada no vuelve a entrar ni permite cancelar o reprogramar el ingreso")
    void noDuplicaEntradasNiSalidas() {
        Visita cita = agendar();
        reloj.marcar(CITA);
        entrar(cita);
        assertThrows(IllegalStateException.class, () -> entrar(cita));
        assertThrows(IllegalStateException.class, () -> ana.visitas.cancelar(ana.id(), cita.getId(), "Ya entró"));
        assertThrows(IllegalStateException.class,
                () -> ana.visitas.reprogramar(ana.id(), cita.getId(), CITA.plusDays(1), "Ya entró"));
        reloj.marcar(CITA.plusMinutes(20));
        salir(cita);
        assertThrows(IllegalStateException.class, () -> salir(cita));
        assertThrows(IllegalStateException.class, () -> entrar(cita));
        assertEquals(1, auditorias("visitas", cita.getId(), "REGISTRO_ENTRADA").size());
        assertEquals(1, auditorias("visitas", cita.getId(), "REGISTRO_SALIDA").size());
    }

    @Test
    @DisplayName("Reprogramar cambia el intervalo y cancelar impide el ingreso; solo lo hace la anfitriona")
    void reprogramaYCancelaLaVisitaPropia() {
        Visita cita = agendar();
        Aplicacion marta = ingresar("marta@luna.sica.local");
        assertThrows(IllegalArgumentException.class, () -> marta.visitas.cancelar(marta.id(), cita.getId(), "No es propia"));
        ana.visitas.reprogramar(ana.id(), cita.getId(), CITA.plusHours(3), "Cambio de reunión");
        reloj.marcar(CITA);
        DetalleIngresoProgramado detalle = guarda.entradas.consultar(guarda.id(), documento(cita))
                .orElseThrow().getVisitas().get(0);
        assertFalse(detalle.isPermiteIngreso());
        assertEquals(CITA.plusHours(2), detalle.getHorario().getDesde());
        assertEquals(CITA.plusHours(4).withSecond(59).withNano(999_999_999), detalle.getHorario().getHasta());
        ana.visitas.cancelar(ana.id(), cita.getId(), "Reunión cancelada");
        reloj.marcar(CITA.plusHours(3));
        assertThrows(IllegalStateException.class, () -> entrar(cita));
        assertEstado(cita.getId(), "Cancelada");
        assertSinMovimiento(cita);
        assertEquals(1, auditorias("visitas", cita.getId(), "REPROGRAMACION_VISITA").size());
        assertEquals(1, auditorias("visitas", cita.getId(), "CANCELACION_VISITA").size());
    }

    @Test
    @DisplayName("Si falla la auditoría de la cita, también se deshacen el invitado y su primera auditoría")
    void deshacePersonaYCitaJuntas() {
        String documento = documentoNuevo();
        // La primera auditoría es la del invitado; la segunda corresponde a la cita.
        acceso.fallarAuditoriaNumero(2);
        assertThrows(IllegalStateException.class, () -> ana.visitas.preregistrar(
                ana.id(), documento, "Laura prueba", "foto-laura.png", CITA, "ABC123"));
        assertTrue(gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorDocumento(documento)).isEmpty());
        assertTrue(gestor.ejecutar(r -> r.crearVisitaRepository().listarPorFuncionarioDestinatario(ana.id())).isEmpty());
        List<BitacoraAuditoria> registros = gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(ana.id()));
        assertEquals(1, registros.stream().filter(a -> "PREREGISTRO_VISITA_FALLIDO".equals(a.getAccionRealizada())).count());
        assertTrue(registros.stream().noneMatch(a -> Set.of("REGISTRO_INVITADO", "PREREGISTRO_VISITA").contains(a.getAccionRealizada())));
    }

    @Test
    @DisplayName("Si falla la auditoría de entrada, conserva la aprobación y permite volver a intentarlo")
    void deshaceEntradaFallida() {
        Visita cita = agendar();
        reloj.marcar(CITA);
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> entrar(cita));
        assertEstado(cita.getId(), "Aprobado");
        assertSinMovimiento(cita);
        assertTrue(auditorias("visitas", cita.getId(), "REGISTRO_ENTRADA").isEmpty());
        assertEquals(1, auditorias("visitas", cita.getId(), "REGISTRO_ENTRADA_FALLIDO").size());
        entrar(cita);
        assertEstado(cita.getId(), "Dentro");
    }

    @Test
    @DisplayName("Si falla la auditoría de salida, la persona sigue dentro hasta que se confirme la salida")
    void deshaceSalidaFallida() {
        Visita cita = agendar();
        reloj.marcar(CITA);
        entrar(cita);
        reloj.marcar(CITA.plusMinutes(20));
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> salir(cita));
        assertEstado(cita.getId(), "Dentro");
        assertNull(visita(cita.getId()).getFechaSalida());
        assertEquals(CITA, visita(cita.getId()).getFechaEntrada());
        assertTrue(auditorias("visitas", cita.getId(), "REGISTRO_SALIDA").isEmpty());
        assertEquals(1, auditorias("visitas", cita.getId(), "REGISTRO_SALIDA_FALLIDO").size());
        salir(cita);
        assertEstado(cita.getId(), "Fuera");
    }

    @Test
    @DisplayName("Dos sesiones de portería simultáneas confirman una sola entrada")
    void noDuplicaConDosSesiones() throws Exception {
        Visita cita = agendar();
        String documento = documento(cita);
        Aplicacion segundoGuarda = ingresar("guarda@sica.local");
        reloj.marcar(CITA);
        CountDownLatch inicio = new CountDownLatch(1);
        var hilos = Executors.newFixedThreadPool(2);
        try {
            var uno = hilos.submit(confirmar(guarda, cita, documento, inicio));
            var dos = hilos.submit(confirmar(segundoGuarda, cita, documento, inicio));
            inicio.countDown();
            assertEquals(Set.of("REGISTRADA", "YA_INGRESO"), Set.of(
                    uno.get(30, TimeUnit.SECONDS), dos.get(30, TimeUnit.SECONDS)));
        } finally {
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(25, TimeUnit.SECONDS));
        }
        assertEstado(cita.getId(), "Dentro");
        assertEquals(1, auditorias("visitas", cita.getId(), "REGISTRO_ENTRADA").size());
        assertTrue(auditorias("visitas", cita.getId(), "REGISTRO_ENTRADA_FALLIDO").isEmpty());
        assertTrue(auditorias("visitas", cita.getId(), "CIERRE_SALIDA_NO_REGISTRADA").isEmpty());
    }

    private Callable<String> confirmar(Aplicacion app, Visita cita, String documento, CountDownLatch inicio) {
        return () -> {
            if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("No comenzó la prueba.");
            try {
                app.entradas.registrarEntrada(app.id(), documento, cita.getId());
                return "REGISTRADA";
            } catch (IllegalStateException error) {
                if (!"La visita ya ingresó o no conserva una autorización aprobada.".equals(error.getMessage())) throw error;
                return "YA_INGRESO";
            }
        };
    }

    private Aplicacion ingresar(String correo) {
        Aplicacion app = new Aplicacion(correo);
        aplicaciones.add(app);
        return app;
    }

    private Visita agendar() {
        return ana.visitas.preregistrar(ana.id(), documentoNuevo(), "Laura prueba", "foto-laura.png", CITA, "ABC123");
    }

    private Visita visita(Integer id) {
        return gestor.ejecutar(r -> r.crearVisitaRepository().buscarPorId(id).orElseThrow());
    }

    private Persona persona(Visita cita) {
        return gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorId(cita.getPersonaId()).orElseThrow());
    }

    private String documento(Visita cita) { return persona(cita).getDocumentoIdentidad(); }
    private void entrar(Visita cita) { guarda.entradas.registrarEntrada(guarda.id(), documento(cita), cita.getId()); }
    private void salir(Visita cita) { guarda.salidas.registrarSalida(guarda.id(), documento(cita), cita.getId()); }

    private void assertEstado(Integer id, String nombre) {
        Integer esperado = gestor.ejecutar(r -> r.crearEstadoVisitaRepository().buscarPorNombre(nombre).orElseThrow().getId());
        assertEquals(esperado, visita(id).getEstadoVisitaId());
    }

    private void assertSinMovimiento(Visita cita) {
        Visita guardada = visita(cita.getId());
        assertNull(guardada.getFechaEntrada());
        assertNull(guardada.getFechaSalida());
        assertNull(guardada.getFechaCierreSistema());
    }

    private List<BitacoraAuditoria> auditorias(String tabla, Integer id, String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorRegistro(tabla, id))
                .stream().filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }

    private String documentoNuevo() {
        return "VP" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
