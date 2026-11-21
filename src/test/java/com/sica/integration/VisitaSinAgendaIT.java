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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sica.pruebas.jdbc", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Recorrido: solicitud de visita sin agenda, decisión, avisos e ingreso")
class VisitaSinAgendaIT {

    private static final ZoneId ZONA = ZoneId.of("America/Bogota");
    private static final LocalDateTime APROBACION = LocalDateTime.of(2026, 12, 15, 10, 0);
    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private RelojSolicitud reloj;
    private final List<Aplicacion> aplicaciones = new ArrayList<>();
    private Aplicacion ana;
    private Aplicacion guarda;
    private Integer empresaId;

    // Cada aplicación conserva su sesión y obtiene los datos compartidos desde MySQL.
    private final class Aplicacion implements AutoCloseable {
        final ContextoSesion contexto = new ContextoSesion();
        final EjecutorOperaciones ejecutor = new EjecutorOperaciones(gestor, contexto);
        final SolicitudIngresoCase solicitudes = new SolicitudIngresoCase(ejecutor, reloj);
        final ControlSolicitudCase entradas = new ControlSolicitudCase(ejecutor, reloj);
        final ControlSalidaCase salidas = new ControlSalidaCase(ejecutor, reloj);
        final AvisosSolicitudCase avisos = new AvisosSolicitudCase(ejecutor);
        final SesionUsuario sesion;

        Aplicacion(String correo) {
            char[] clave = "SicaDemo2026!".toCharArray();
            try {
                sesion = new IniciarSesionCase(gestor, new ServicioContrasenasPbkdf2())
                        .iniciar(contexto, correo, clave);
            } finally { Arrays.fill(clave, '\0'); }
        }

        Integer id() { return sesion.getUsuarioId(); }
        @Override public void close() { new CerrarSesionCase(contexto).cerrar(); }
    }

    // Permite comprobar minutos y segundos concretos sin cambiar la hora del computador.
    private static final class RelojSolicitud extends Clock {
        private Instant actual;
        private final ArrayDeque<Instant> siguientes = new ArrayDeque<>();
        synchronized void marcar(LocalDateTime hora) {
            siguientes.clear();
            actual = hora.atZone(ZONA).toInstant();
        }
        synchronized void avanzarDuranteRevision(LocalDateTime inicio, LocalDateTime fin) {
            marcar(inicio);
            siguientes.add(inicio.atZone(ZONA).toInstant());
            siguientes.add(fin.atZone(ZONA).toInstant());
        }
        @Override public ZoneId getZone() { return ZONA; }
        @Override public Clock withZone(ZoneId zona) { return Clock.fixed(instant(), zona); }
        @Override public synchronized Instant instant() {
            if (!siguientes.isEmpty()) actual = siguientes.removeFirst();
            return actual;
        }
    }

    @BeforeAll
    void prepararBase() throws Exception { base = BaseDatosTemporal.crear(); }

    @BeforeEach
    void prepararCuentas() {
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        reloj = new RelojSolicitud();
        reloj.marcar(APROBACION.minusMinutes(15));
        Usuario[] cuentas = gestor.ejecutar(r -> {
            String marca = UUID.randomUUID().toString().replace("-", "");
            empresaId = r.crearEmpresaRepository().guardar(new Empresa("Sol prueba " + marca, "Ana prueba")).getId();
            Usuario funcionaria = r.crearUsuarioRepository().buscarPorEmail("ana@sol.sica.local").orElseThrow();
            Usuario porteria = r.crearUsuarioRepository().buscarPorEmail("guarda@sica.local").orElseThrow();
            return new Usuario[]{
                    r.crearUsuarioRepository().guardar(new Usuario("Ana prueba", "ana-" + marca + "@prueba.invalid",
                            funcionaria.getPasswordHash(), funcionaria.getRolId(), empresaId)),
                    r.crearUsuarioRepository().guardar(new Usuario("Guarda prueba", "guarda-" + marca + "@prueba.invalid",
                            porteria.getPasswordHash(), porteria.getRolId(), null))};
        });
        ana = ingresar(cuentas[0].getEmail());
        guarda = ingresar(cuentas[1].getEmail());
    }

    @AfterEach
    void cerrarSesiones(TestInfo prueba) {
        for (Aplicacion app : aplicaciones) app.close();
        aplicaciones.clear();
        System.out.println("Recorrido revisado: " + prueba.getDisplayName());
    }

    @AfterAll
    void eliminarBase() throws SQLException { if (base != null) base.close(); }

    @Test
    @DisplayName("El guarda solicita, Ana recibe el aviso y aprueba, portería recibe la decisión y registra entrada y salida")
    void completaElRecorridoConAvisosAutomaticos() throws Exception {
        DestinoVisitaCase destinos = new DestinoVisitaCase(guarda.ejecutor);
        assertTrue(destinos.listarEmpresas(guarda.id()).stream().anyMatch(e -> empresaId.equals(e.getId())));
        assertEquals(List.of(ana.id()), destinos.listarFuncionarios(guarda.id(), empresaId).stream()
                .map(FuncionarioDisponible::getId).toList());
        var pendientes = new LinkedBlockingQueue<AvisoSolicitudPendiente>();
        var decisiones = new LinkedBlockingQueue<AvisoSolicitud>();
        var errores = new CopyOnWriteArrayList<RuntimeException>();
        Visita solicitud;
        try (AutoCloseable avisoAna = ana.avisos.observarFuncionario(ana.id(), pendientes::add, errores::add);
             AutoCloseable avisoGuarda = guarda.avisos.observar(guarda.id(), decisiones::add, errores::add)) {
            solicitud = solicitar();
            AvisoSolicitudPendiente aviso = pendientes.poll(30, TimeUnit.SECONDS);
            assertNotNull(aviso, "Ana debe recibir el aviso sin solicitar manualmente el listado.");
            assertEquals(solicitud.getId(), aviso.getSolicitudId());
            assertEquals(ana.id(), aviso.getFuncionarioId());
            assertEquals("Laura prueba", aviso.getNombrePersona());
            assertEstado(solicitud, "Pendiente de Aprobación");
            assertEquals(List.of(solicitud.getId()), ana.solicitudes.listarPendientes(ana.id()).stream()
                    .map(d -> d.getSolicitud().getId()).toList());
            ResultadoSolicitudPorteria pendiente = guarda.entradas.consultarResultado(guarda.id(), solicitud.getId());
            assertFalse(pendiente.isPermiteIngreso());
            assertTrue(pendiente.getHorario().isEmpty());

            aprobar(solicitud);
            AvisoSolicitud decision = decisiones.poll(30, TimeUnit.SECONDS);
            assertNotNull(decision, "El guarda debe recibir la decisión confirmada automáticamente.");
            assertEquals(solicitud.getId(), decision.getSolicitudId());
            assertEquals(guarda.id(), decision.getGuardaId());
            assertEquals("Aprobado", decision.getResultado());
            assertEquals(ana.sesion.getNombre(), decision.getFuncionario());
            assertTrue(guarda.avisos.consultarAvisos(guarda.id(), Set.of(decision.getId())).isEmpty());
            assertTrue(ana.avisos.consultarAvisosFuncionario(ana.id(), Set.of()).isEmpty());
            assertTrue(errores.isEmpty(), () -> "Fallaron los avisos: " + errores);
        }

        ResultadoSolicitudPorteria resultado = guarda.entradas.consultarResultado(guarda.id(), solicitud.getId());
        assertTrue(resultado.isPermiteIngreso());
        assertEquals("foto-laura.png", resultado.getPersona().getUrlFoto());
        assertEquals(ana.sesion.getNombre(), resultado.getFuncionario());
        assertEquals(APROBACION, resultado.getHorario().orElseThrow().getDesde());
        assertEquals(APROBACION.plusMinutes(30).withSecond(59).withNano(999_999_999),
                resultado.getHorario().orElseThrow().getHasta());
        assertNull(visita(solicitud).getFechaEntrada());
        entrar(solicitud);
        assertEstado(solicitud, "Dentro");
        reloj.marcar(APROBACION.plusHours(2));
        guarda.salidas.registrarSalida(guarda.id(), documento(solicitud), solicitud.getId());
        Visita fuera = visita(solicitud);
        assertEstado(solicitud, "Fuera");
        assertEquals(APROBACION, fuera.getFechaEntrada());
        assertEquals(APROBACION.plusHours(2), fuera.getFechaSalida());
        assertNull(fuera.getFechaCierreSistema());
        assertEquals("ABC123", fuera.getVehiculoPlaca());
        assertNull(persona(solicitud).getEmpresaId());
        assertEquals(guarda.id(), auditorias(solicitud, "SOLICITUD_VISITA_NO_ANUNCIADA").get(0).getUsuarioId());
        assertEquals(ana.id(), auditorias(solicitud, "APROBACION_SOLICITUD").get(0).getUsuarioId());
        assertEquals(guarda.id(), auditorias(solicitud, "REGISTRO_ENTRADA").get(0).getUsuarioId());
        assertEquals(guarda.id(), auditorias(solicitud, "REGISTRO_SALIDA").get(0).getUsuarioId());
    }

    @Test
    @DisplayName("El rechazo queda con responsable y motivo, avisa al guarda y no autoriza el ingreso")
    void rechazaSinAutorizar() {
        Visita solicitud = solicitar();
        reloj.marcar(APROBACION);
        ana.solicitudes.rechazar(ana.id(), solicitud.getId(), "No puede atender la visita");
        ResultadoSolicitudPorteria resultado = guarda.entradas.consultarResultado(guarda.id(), solicitud.getId());
        assertEquals("Rechazado", resultado.getEstado().getNombreEstado());
        assertFalse(resultado.isPermiteIngreso());
        assertTrue(resultado.getHorario().isEmpty());
        assertNull(visita(solicitud).getFechaAprobacion());
        assertNull(visita(solicitud).getAprobadaPorId());
        assertThrows(IllegalStateException.class, () -> entrar(solicitud));
        assertThrows(IllegalStateException.class, () -> ana.solicitudes.aprobar(ana.id(), solicitud.getId()));
        assertSinMovimiento(solicitud);
        BitacoraAuditoria registro = auditorias(solicitud, "RECHAZO_SOLICITUD").get(0);
        assertEquals(ana.id(), registro.getUsuarioId());
        assertTrue(registro.getDetalles().contains("No puede atender la visita"));
        List<AvisoSolicitud> avisos = guarda.avisos.consultarAvisos(guarda.id(), Set.of());
        assertEquals(1, avisos.size());
        assertEquals("Rechazado", avisos.get(0).getResultado());
        assertTrue(guarda.avisos.consultarAvisos(guarda.id(), Set.of(avisos.get(0).getId())).isEmpty());
    }

    @Test
    @DisplayName("Los treinta minutos empiezan al aprobar, aunque la solicitud lleve varias horas esperando")
    void cuentaDesdeLaAprobacionEIncluyeElUltimoMinuto() {
        Visita solicitud = solicitar();
        assertThrows(IllegalStateException.class, () -> entrar(solicitud));
        LocalDateTime aprobacionTardia = APROBACION.plusHours(4);
        reloj.marcar(aprobacionTardia);
        ana.solicitudes.aprobar(ana.id(), solicitud.getId());
        reloj.marcar(aprobacionTardia.minusNanos(1));
        assertThrows(IllegalStateException.class, () -> entrar(solicitud));
        reloj.marcar(aprobacionTardia.plusMinutes(30).withSecond(59).withNano(999_999_999));
        entrar(solicitud);
        assertEquals(aprobacionTardia, visita(solicitud).getFechaAprobacion());
        assertEquals(aprobacionTardia.plusMinutes(30).withSecond(59), visita(solicitud).getFechaEntrada());
        reloj.marcar(aprobacionTardia.plusHours(2));
        assertEquals("Dentro", guarda.entradas.consultarResultado(guarda.id(), solicitud.getId()).getEstado().getNombreEstado());
        assertTrue(auditorias(solicitud, "EXPIRACION_VISITA").isEmpty());
    }

    @Test
    @DisplayName("A las diez y treinta y uno expira al consultar, intentar entrar o ejecutar la revisión automática")
    void confirmaElVencimientoSinInventarMovimientos() {
        Visita porConsulta = solicitar();
        Visita porEntrada = solicitar();
        Visita porTarea = solicitar();
        aprobar(porConsulta);
        aprobar(porEntrada);
        aprobar(porTarea);
        reloj.marcar(APROBACION.plusMinutes(31));
        assertFalse(guarda.entradas.consultarResultado(guarda.id(), porConsulta.getId()).isPermiteIngreso());
        assertThrows(IllegalStateException.class, () -> entrar(porEntrada));
        VencimientoVisitasCase vencimiento = new VencimientoVisitasCase(gestor, reloj);
        assertTrue(vencimiento.expirarPendientes() >= 1);
        vencimiento.expirarPendientes();
        for (Visita solicitud : List.of(porConsulta, porEntrada, porTarea)) {
            assertEstado(solicitud, "Expirado");
            assertSinMovimiento(solicitud);
            assertEquals(1, auditorias(solicitud, "EXPIRACION_VISITA").size());
            assertTrue(auditorias(solicitud, "REGISTRO_ENTRADA").isEmpty());
        }
    }

    @Test
    @DisplayName("Si la revisión cruza de las diez y treinta a las diez y treinta y uno, no guarda la entrada")
    void vuelveAComprobarLaHoraAntesDeGuardar() {
        Visita solicitud = solicitar();
        aprobar(solicitud);
        reloj.avanzarDuranteRevision(APROBACION.plusMinutes(30).withSecond(59), APROBACION.plusMinutes(31));
        assertThrows(IllegalStateException.class, () -> entrar(solicitud));
        assertEstado(solicitud, "Expirado");
        assertSinMovimiento(solicitud);
        assertEquals(1, auditorias(solicitud, "EXPIRACION_VISITA").size());
    }

    @Test
    @DisplayName("Un aviso de aprobación no permite entrar si después se impone una prohibición")
    void revisaRestriccionesDespuesDeAprobar() {
        Visita solicitud = solicitar();
        aprobar(solicitud);
        assertEquals("Aprobado", guarda.avisos.consultarAvisos(guarda.id(), Set.of()).get(0).getResultado());
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        RestriccionAccesoCase restricciones = new RestriccionAccesoCase(supervisor.ejecutor);
        restricciones.imponerProhibicion(supervisor.id(), solicitud.getPersonaId(), "Situación por revisar");
        assertFalse(guarda.entradas.consultarResultado(guarda.id(), solicitud.getId()).isPermiteIngreso());
        assertThrows(IllegalStateException.class, () -> entrar(solicitud));
        assertEstado(solicitud, "Aprobado");
        assertSinMovimiento(solicitud);
        restricciones.retirarProhibicion(supervisor.id(), solicitud.getPersonaId(), "Situación aclarada");
        entrar(solicitud);
        assertEstado(solicitud, "Dentro");
        assertEquals(1, auditorias(solicitud, "APROBACION_SOLICITUD").size());
    }

    @Test
    @DisplayName("Una empresa retirada impide nuevas entradas y conserva la salida de quien ya estaba dentro")
    void empresaRetiradaNoImpideRegistrarLaSalida() {
        Visita dentro = solicitar();
        Visita pendiente = solicitar();
        aprobar(dentro);
        aprobar(pendiente);
        entrar(dentro);
        Aplicacion admin = ingresar("admin@sica.local");
        new EmpresaUseCase(admin.ejecutor).desactivar(admin.id(), empresaId, "La empresa se retiró");
        assertThrows(IllegalStateException.class, () -> entrar(pendiente));
        assertSinMovimiento(pendiente);
        reloj.marcar(APROBACION.plusHours(1));
        guarda.salidas.registrarSalida(guarda.id(), documento(dentro), dentro.getId());
        assertEstado(dentro, "Fuera");
        assertEquals(APROBACION.plusHours(1), visita(dentro).getFechaSalida());
    }

    @Test
    @DisplayName("Solo el funcionario destinatario puede resolver; otra cuenta no recibe sus avisos")
    void respetaDestinoEmpresaYPermisos() {
        Visita solicitud = solicitar();
        Aplicacion otraFuncionaria = ingresar(clonar("ana@sol.sica.local", "Otra funcionaria", empresaId).getEmail());
        Aplicacion marta = ingresar("marta@luna.sica.local");
        Aplicacion otroGuarda = ingresar(clonar("guarda@sica.local", "Otro guarda", null).getEmail());
        for (Aplicacion otra : List.of(otraFuncionaria, marta)) {
            assertTrue(otra.solicitudes.listarPendientes(otra.id()).stream()
                    .noneMatch(d -> solicitud.getId().equals(d.getSolicitud().getId())));
            assertTrue(otra.avisos.consultarAvisosFuncionario(otra.id(), Set.of()).stream()
                    .noneMatch(a -> solicitud.getId().equals(a.getSolicitudId())));
            assertThrows(IllegalArgumentException.class, () -> otra.solicitudes.aprobar(otra.id(), solicitud.getId()));
            assertThrows(IllegalArgumentException.class, () -> otra.solicitudes.rechazar(otra.id(), solicitud.getId()));
        }
        assertThrows(SecurityException.class, () -> guarda.solicitudes.aprobar(guarda.id(), solicitud.getId()));
        assertThrows(SecurityException.class, () -> ana.solicitudes.solicitarSinAgenda(
                ana.id(), empresaId, ana.id(), documentoNuevo(), "Otra persona", "foto.png", null));
        String documento = documentoNuevo();
        assertThrows(IllegalArgumentException.class, () -> guarda.solicitudes.solicitarSinAgenda(
                guarda.id(), empresaId, marta.id(), documento, "Otra persona", "foto.png", null));
        assertTrue(gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorDocumento(documento)).isEmpty());
        aprobar(solicitud);
        assertTrue(otroGuarda.avisos.consultarAvisos(otroGuarda.id(), Set.of()).isEmpty());
        assertEquals(1, guarda.avisos.consultarAvisos(guarda.id(), Set.of()).size());
    }

    @Test
    @DisplayName("Reutiliza a una invitada sin reemplazar su identidad, foto ni prohibición")
    void reutilizaLaIdentidadExistente() {
        Persona laura = new PersonaCase(ana.ejecutor).registrarInvitado(
                ana.id(), "Laura original", documentoNuevo(), "foto-original.png");
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        new RestriccionAccesoCase(supervisor.ejecutor).imponerProhibicion(supervisor.id(), laura.getId(), "Revisión pendiente");
        Visita solicitud = guarda.solicitudes.solicitarSinAgenda(guarda.id(), empresaId, ana.id(),
                laura.getDocumentoIdentidad(), "No reemplazar", "otra-foto.png", null);
        assertEquals(laura.getId(), solicitud.getPersonaId());
        Persona conservada = persona(solicitud);
        assertEquals("Laura original", conservada.getNombre());
        assertEquals("foto-original.png", conservada.getUrlFoto());
        assertNull(conservada.getEmpresaId());
        assertNull(conservada.getVinculacionLaboralActiva());
        aprobar(solicitud);
        assertFalse(guarda.entradas.consultarResultado(guarda.id(), solicitud.getId()).isPermiteIngreso());
        assertThrows(IllegalStateException.class, () -> entrar(solicitud));
        assertEquals(1, gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorRegistro("personas", laura.getId()))
                .stream().filter(a -> "REGISTRO_INVITADO".equals(a.getAccionRealizada())).count());
    }

    @Test
    @DisplayName("Si falla la creación de la solicitud, se deshacen el invitado nuevo y las auditorías de éxito")
    void deshaceInvitadoYSolicitudSiFallaLaAuditoria() {
        String documento = documentoNuevo();
        acceso.fallarAuditoriaNumero(2);
        assertThrows(IllegalStateException.class, () -> guarda.solicitudes.solicitarSinAgenda(
                guarda.id(), empresaId, ana.id(), documento, "Laura prueba", "foto-laura.png", "ABC123"));
        assertTrue(gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorDocumento(documento)).isEmpty());
        assertTrue(ana.solicitudes.listarPendientes(ana.id()).isEmpty());
        assertTrue(ana.avisos.consultarAvisosFuncionario(ana.id(), Set.of()).isEmpty());
        List<BitacoraAuditoria> registros = gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(guarda.id()));
        assertEquals(1, registros.stream().filter(a -> "SOLICITUD_VISITA_NO_ANUNCIADA_FALLIDA".equals(a.getAccionRealizada())).count());
        assertTrue(registros.stream().noneMatch(a -> Set.of("REGISTRO_INVITADO", "SOLICITUD_VISITA_NO_ANUNCIADA")
                .contains(a.getAccionRealizada())));
    }

    @Test
    @DisplayName("Una aprobación fallida conserva Pendiente y no genera un aviso de aprobación")
    void deshaceAprobacionFallida() {
        Visita solicitud = solicitar();
        reloj.marcar(APROBACION);
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> ana.solicitudes.aprobar(ana.id(), solicitud.getId()));
        assertEstado(solicitud, "Pendiente de Aprobación");
        assertNull(visita(solicitud).getFechaAprobacion());
        assertNull(visita(solicitud).getAprobadaPorId());
        assertTrue(guarda.avisos.consultarAvisos(guarda.id(), Set.of()).isEmpty());
        assertEquals(1, auditorias(solicitud, "APROBACION_SOLICITUD_FALLIDA").size());
        assertTrue(auditorias(solicitud, "APROBACION_SOLICITUD").isEmpty());
        aprobar(solicitud);
        assertEstado(solicitud, "Aprobado");
        assertEquals(1, guarda.avisos.consultarAvisos(guarda.id(), Set.of()).size());
    }

    @Test
    @DisplayName("Un rechazo fallido conserva Pendiente; el aviso aparece únicamente al confirmar el rechazo")
    void deshaceRechazoFallido() {
        Visita solicitud = solicitar();
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> ana.solicitudes.rechazar(ana.id(), solicitud.getId(), "No disponible"));
        assertEstado(solicitud, "Pendiente de Aprobación");
        assertEquals(1, auditorias(solicitud, "RECHAZO_SOLICITUD_FALLIDO").size());
        assertTrue(auditorias(solicitud, "RECHAZO_SOLICITUD").isEmpty());
        assertTrue(guarda.avisos.consultarAvisos(guarda.id(), Set.of()).isEmpty());
        ana.solicitudes.rechazar(ana.id(), solicitud.getId(), "No disponible");
        assertEquals("Rechazado", guarda.avisos.consultarAvisos(guarda.id(), Set.of()).get(0).getResultado());
        assertSinMovimiento(solicitud);
    }

    @Test
    @DisplayName("Una entrada fallida conserva la aprobación y el intento posterior no duplica el ingreso")
    void deshaceEntradaFallidaYEvitaReutilizarLaAutorizacion() {
        Visita solicitud = solicitar();
        aprobar(solicitud);
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> entrar(solicitud));
        assertEstado(solicitud, "Aprobado");
        assertSinMovimiento(solicitud);
        assertEquals(1, auditorias(solicitud, "REGISTRO_ENTRADA_FALLIDO").size());
        assertTrue(auditorias(solicitud, "REGISTRO_ENTRADA").isEmpty());
        entrar(solicitud);
        assertThrows(IllegalStateException.class, () -> entrar(solicitud));
        reloj.marcar(APROBACION.plusMinutes(10));
        guarda.salidas.registrarSalida(guarda.id(), documento(solicitud), solicitud.getId());
        assertThrows(IllegalStateException.class, () -> entrar(solicitud));
        assertEquals(1, auditorias(solicitud, "REGISTRO_ENTRADA").size());
        assertEquals(1, auditorias(solicitud, "REGISTRO_SALIDA").size());
    }

    @Test
    @DisplayName("Dos sesiones del destinatario no confirman decisiones opuestas sobre la misma solicitud")
    void coordinaDecisionesSimultaneas() throws Exception {
        Visita solicitud = solicitar();
        Aplicacion segunda = ingresar(ana.sesion.getEmail());
        reloj.marcar(APROBACION);
        CountDownLatch inicio = new CountDownLatch(1);
        var hilos = Executors.newFixedThreadPool(2);
        try {
            var primera = hilos.submit(decidir(ana, solicitud, true, inicio));
            var otra = hilos.submit(decidir(segunda, solicitud, false, inicio));
            inicio.countDown();
            assertEquals(Set.of("CONFIRMADA", "YA_RESUELTA"), Set.of(
                    primera.get(30, TimeUnit.SECONDS), otra.get(30, TimeUnit.SECONDS)));
        } finally {
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(25, TimeUnit.SECONDS));
        }
        assertEquals(1, auditorias(solicitud, "APROBACION_SOLICITUD").size()
                + auditorias(solicitud, "RECHAZO_SOLICITUD").size());
        assertEquals(1, guarda.avisos.consultarAvisos(guarda.id(), Set.of()).size());
        ResultadoSolicitudPorteria resultado = guarda.entradas.consultarResultado(guarda.id(), solicitud.getId());
        assertEquals("Aprobado".equals(resultado.getEstado().getNombreEstado()), resultado.isPermiteIngreso());
        assertSinMovimiento(solicitud);
    }

    private Callable<String> decidir(Aplicacion app, Visita solicitud, boolean aprobar, CountDownLatch inicio) {
        return () -> {
            if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("No comenzó la prueba.");
            try {
                if (aprobar) app.solicitudes.aprobar(app.id(), solicitud.getId());
                else app.solicitudes.rechazar(app.id(), solicitud.getId(), "No disponible");
                return "CONFIRMADA";
            } catch (IllegalStateException error) {
                if (!"La solicitud ya fue resuelta o no conserva un estado pendiente válido.".equals(error.getMessage())) throw error;
                return "YA_RESUELTA";
            }
        };
    }

    private Aplicacion ingresar(String correo) {
        Aplicacion app = new Aplicacion(correo);
        aplicaciones.add(app);
        return app;
    }

    private Usuario clonar(String correo, String nombre, Integer empresa) {
        return gestor.ejecutar(r -> {
            Usuario modelo = r.crearUsuarioRepository().buscarPorEmail(correo).orElseThrow();
            return r.crearUsuarioRepository().guardar(new Usuario(nombre, UUID.randomUUID() + "@prueba.invalid",
                    modelo.getPasswordHash(), modelo.getRolId(), empresa));
        });
    }

    private Visita solicitar() {
        return guarda.solicitudes.solicitarSinAgenda(guarda.id(), empresaId, ana.id(),
                documentoNuevo(), "Laura prueba", "foto-laura.png", "ABC123");
    }

    private void aprobar(Visita solicitud) {
        reloj.marcar(APROBACION);
        ana.solicitudes.aprobar(ana.id(), solicitud.getId());
    }

    private Visita visita(Visita solicitud) {
        return gestor.ejecutar(r -> r.crearVisitaRepository().buscarPorId(solicitud.getId()).orElseThrow());
    }

    private Persona persona(Visita solicitud) {
        return gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorId(solicitud.getPersonaId()).orElseThrow());
    }

    private String documento(Visita solicitud) { return persona(solicitud).getDocumentoIdentidad(); }
    private void entrar(Visita solicitud) { guarda.entradas.registrarEntrada(guarda.id(), solicitud.getId(), documento(solicitud)); }

    private void assertEstado(Visita solicitud, String nombre) {
        gestor.ejecutar(r -> {
            Visita guardada = r.crearVisitaRepository().buscarPorId(solicitud.getId()).orElseThrow();
            assertEquals(nombre, r.crearEstadoVisitaRepository().buscarPorId(guardada.getEstadoVisitaId()).orElseThrow().getNombreEstado());
            return null;
        });
    }

    private void assertSinMovimiento(Visita solicitud) {
        Visita guardada = visita(solicitud);
        assertNull(guardada.getFechaEntrada());
        assertNull(guardada.getFechaSalida());
        assertNull(guardada.getFechaCierreSistema());
    }

    private List<BitacoraAuditoria> auditorias(Visita solicitud, String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorRegistro("visitas", solicitud.getId()))
                .stream().filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }

    private String documentoNuevo() {
        return "SA" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
