package com.sica.integration;

import com.sica.infrastructure.database.GestorTransaccionesJdbc;
import com.sica.infrastructure.security.ServicioContrasenasPbkdf2;
import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.application.port.GestorTransacciones;
import com.sica.model.application.usecase.*;
import com.sica.model.domain.entity.*;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sica.pruebas.jdbc", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Recorridos: cierre del sistema por 24 horas sin salida")
class CierreSalidas24HorasIT {

    private static final ZoneId ZONA = ZoneId.of("America/Bogota");
    // Esta fecha evita mezclar los ingresos estudiados con las visitas recientes de demostración.
    private static final LocalDateTime ENTRADA = LocalDateTime.of(2026, 1, 12, 8, 0);
    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private RelojCierre reloj;
    private final List<ContextoSesion> sesiones = new ArrayList<>();

    private record Operador(Integer id, EjecutorOperaciones ejecutor) { }

    private static final class RelojCierre extends Clock {
        private volatile Instant actual;
        void marcar(LocalDateTime hora) { actual = hora.atZone(ZONA).toInstant(); }
        @Override public ZoneId getZone() { return ZONA; }
        @Override public Clock withZone(ZoneId zona) { return Clock.fixed(actual, zona); }
        @Override public Instant instant() { return actual; }
    }

    @BeforeEach
    void prepararBase() throws Exception {
        // Cada prueba empieza con su propia base para que los cierres no afecten a otro recorrido.
        base = BaseDatosTemporal.crear();
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        reloj = new RelojCierre();
        reloj.marcar(ENTRADA);
    }

    @AfterEach
    void limpiar(TestInfo prueba) throws SQLException {
        try {
            for (ContextoSesion sesion : sesiones) new CerrarSesionCase(sesion).cerrar();
        } finally {
            if (base != null) base.close();
            System.out.println("Recorrido revisado: " + prueba.getDisplayName());
        }
    }

    @Test
    @DisplayName("Los cuatro tipos de ingreso se cierran a las 24 horas, nunca antes, y una sola vez")
    void cierraTodosLosTiposEnElLimiteExacto() {
        List<Visita> ingresos = Arrays.stream(TipoIngreso.values())
                .map(tipo -> prepararIngreso(tipo, ENTRADA, true)).toList();
        RegularizacionSalidasCase cierres = new RegularizacionSalidasCase(gestor, reloj);
        reloj.marcar(ENTRADA.plusHours(24).minusNanos(1));
        assertEquals(0, cierres.cerrarIngresosSinSalida());
        for (Visita ingreso : ingresos) comprobarAbierto(ingreso);

        reloj.marcar(ENTRADA.plusHours(24));
        assertEquals(4, cierres.cerrarIngresosSinSalida());
        for (Visita ingreso : ingresos) comprobarCierre(ingreso, ENTRADA.plusHours(24), null);
        assertEquals(0, cierres.cerrarIngresosSinSalida());
        for (Visita ingreso : ingresos) assertEquals(1, auditorias(ingreso, "CIERRE_SALIDA_NO_REGISTRADA").size());
    }

    @Test
    @DisplayName("Si la revisión ocurre tarde, guarda la hora real del cierre y conserva desconocida la salida")
    void registraCuandoSeEjecutoLaRevision() {
        Visita ingreso = prepararIngreso(TipoIngreso.INVITADO_PREREGISTRADO, ENTRADA, true);
        reloj.marcar(ENTRADA.plusHours(27));
        assertEquals(1, new RegularizacionSalidasCase(gestor, reloj).cerrarIngresosSinSalida());
        comprobarCierre(ingreso, ENTRADA.plusHours(27), null);
        BitacoraAuditoria registro = auditorias(ingreso, "CIERRE_SALIDA_NO_REGISTRADA").get(0);
        assertEquals(ENTRADA.plusHours(27), registro.getFechaHora());
        assertTrue(registro.getDetalles().contains(ENTRADA.plusHours(24).toString()));
        assertTrue(registro.getDetalles().contains("hora real de salida se desconoce"));
    }

    @Test
    @DisplayName("No altera una salida real ni un cierre anterior realizado al volver la persona")
    void respetaLosIngresosQueYaSeCerraron() {
        Visita conSalida = prepararIngreso(TipoIngreso.INVITADO_NO_ANUNCIADO, ENTRADA, true);
        Visita regularizado = prepararIngreso(TipoIngreso.TRABAJADOR_CON_CARNET, ENTRADA, true);
        Operador guarda = ingresar("guarda@sica.local");
        reloj.marcar(ENTRADA.plusHours(1));
        new ControlSalidaCase(guarda.ejecutor(), reloj).registrarSalida(
                guarda.id(), documento(conSalida), conSalida.getId());
        reloj.marcar(ENTRADA.plusHours(23));
        Visita nuevo = new ControlAccesoTrabajadorCase(guarda.ejecutor(), reloj).registrarEntrada(
                guarda.id(), documento(regularizado), true, "DEF456");
        reloj.marcar(ENTRADA.plusHours(24));
        assertEquals(0, new RegularizacionSalidasCase(gestor, reloj).cerrarIngresosSinSalida());
        gestor.ejecutar(r -> {
            Visita salida = r.crearVisitaRepository().buscarPorId(conSalida.getId()).orElseThrow();
            assertEquals(ENTRADA.plusHours(1), salida.getFechaSalida());
            assertNull(salida.getFechaCierreSistema());
            assertEquals("Fuera", estado(r, salida));
            return null;
        });
        comprobarCierre(regularizado, ENTRADA.plusHours(23), guarda.id());
        comprobarAbierto(nuevo);
        assertTrue(auditorias(conSalida, "CIERRE_SALIDA_NO_REGISTRADA").isEmpty());
    }

    @Test
    @DisplayName("No cierra autorizaciones sin entrada ni permanencias que todavía no cumplen 24 horas")
    void distingueAutorizacionDePermanencia() {
        Visita autorizacion = prepararIngreso(TipoIngreso.INVITADO_PREREGISTRADO, ENTRADA, false);
        Visita reciente = prepararIngreso(TipoIngreso.TRABAJADOR_CON_CARNET, ENTRADA.plusHours(1), true);
        reloj.marcar(ENTRADA.plusHours(24));
        assertEquals(0, new RegularizacionSalidasCase(gestor, reloj).cerrarIngresosSinSalida());
        gestor.ejecutar(r -> {
            Visita guardada = r.crearVisitaRepository().buscarPorId(autorizacion.getId()).orElseThrow();
            assertEquals("Aprobado", estado(r, guardada));
            assertNull(guardada.getFechaEntrada());
            assertNull(guardada.getFechaSalida());
            assertNull(guardada.getFechaCierreSistema());
            return null;
        });
        comprobarAbierto(reciente);
        assertTrue(auditorias(autorizacion, "CIERRE_SALIDA_NO_REGISTRADA").isEmpty());
    }

    @Test
    @DisplayName("El cierre del sistema funciona sin sesión y también cuando la empresa ya se retiró")
    void cierraAunqueLaEmpresaEsteRetirada() {
        Visita ingreso = prepararIngreso(TipoIngreso.TRABAJADOR_CON_CARNET, ENTRADA, true);
        Operador admin = ingresar("admin@sica.local");
        new EmpresaUseCase(admin.ejecutor()).desactivar(admin.id(), ingreso.getEmpresaDestinoId(), "Empresa retirada");
        for (ContextoSesion sesion : sesiones) new CerrarSesionCase(sesion).cerrar();
        reloj.marcar(ENTRADA.plusHours(24));
        assertEquals(1, new RegularizacionSalidasCase(gestor, reloj).cerrarIngresosSinSalida());
        comprobarCierre(ingreso, ENTRADA.plusHours(24), null);
        gestor.ejecutar(r -> {
            assertFalse(r.crearEmpresaRepository().buscarPorId(ingreso.getEmpresaDestinoId()).orElseThrow().isEstaActiva());
            assertEquals(Boolean.FALSE, r.crearPersonaRepository().buscarPorId(ingreso.getPersonaId())
                    .orElseThrow().getVinculacionLaboralActiva());
            return null;
        });
    }

    @Test
    @DisplayName("Si falla la auditoría, deshace el cierre, registra el fallo aparte y permite reintentarlo")
    void deshaceElCierreSiFallaLaAuditoria() {
        Visita ingreso = prepararIngreso(TipoIngreso.INVITADO_PREREGISTRADO, ENTRADA, true);
        reloj.marcar(ENTRADA.plusHours(24));
        acceso.fallarProximasAuditorias(1);
        RegularizacionSalidasCase cierres = new RegularizacionSalidasCase(gestor, reloj);
        IllegalStateException error = assertThrows(IllegalStateException.class, cierres::cerrarIngresosSinSalida);
        assertEquals(1, error.getSuppressed().length);
        comprobarAbierto(ingreso);
        assertTrue(auditorias(ingreso, "CIERRE_SALIDA_NO_REGISTRADA").isEmpty());
        List<BitacoraAuditoria> fallos = auditorias(ingreso, "CIERRE_SALIDA_NO_REGISTRADA_FALLIDO");
        assertEquals(1, fallos.size());
        assertNull(fallos.get(0).getUsuarioId());
        assertEquals(1, cierres.cerrarIngresosSinSalida());
        comprobarCierre(ingreso, ENTRADA.plusHours(24), null);
        assertEquals(1, auditorias(ingreso, "CIERRE_SALIDA_NO_REGISTRADA_FALLIDO").size());
    }

    @Test
    @DisplayName("Un ingreso que falla no impide confirmar los cierres de las otras personas")
    void continuaConLosDemasIngresosAunqueUnoFalle() {
        List<Visita> ingresos = List.of(
                prepararIngreso(TipoIngreso.INVITADO_NO_ANUNCIADO, ENTRADA, true),
                prepararIngreso(TipoIngreso.TRABAJADOR_CON_CARNET, ENTRADA, true),
                prepararIngreso(TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO, ENTRADA, true));
        reloj.marcar(ENTRADA.plusHours(24));
        acceso.fallarProximasAuditorias(1);
        RegularizacionSalidasCase cierres = new RegularizacionSalidasCase(gestor, reloj);
        assertThrows(IllegalStateException.class, cierres::cerrarIngresosSinSalida);
        int abiertos = 0;
        for (Visita ingreso : ingresos) {
            if (auditorias(ingreso, "CIERRE_SALIDA_NO_REGISTRADA").isEmpty()) {
                abiertos++;
                comprobarAbierto(ingreso);
                assertEquals(1, auditorias(ingreso, "CIERRE_SALIDA_NO_REGISTRADA_FALLIDO").size());
            } else {
                comprobarCierre(ingreso, ENTRADA.plusHours(24), null);
            }
        }
        assertEquals(1, abiertos);
        assertEquals(1, cierres.cerrarIngresosSinSalida());
        for (Visita ingreso : ingresos) comprobarCierre(ingreso, ENTRADA.plusHours(24), null);
    }

    @Test
    @DisplayName("Dos revisiones simultáneas cierran el mismo ingreso una sola vez")
    void evitaDuplicadosEntreDosRevisiones() throws Exception {
        Visita ingreso = prepararIngreso(TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO, ENTRADA, true);
        reloj.marcar(ENTRADA.plusHours(24));
        CountDownLatch inicio = new CountDownLatch(1);
        var hilos = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> cerrar = () -> {
                if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("No comenzó la prueba.");
                return new RegularizacionSalidasCase(gestor, reloj).cerrarIngresosSinSalida();
            };
            var primera = hilos.submit(cerrar);
            var segunda = hilos.submit(cerrar);
            inicio.countDown();
            assertEquals(1, primera.get(30, TimeUnit.SECONDS) + segunda.get(30, TimeUnit.SECONDS));
        } finally {
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(25, TimeUnit.SECONDS));
        }
        comprobarCierre(ingreso, ENTRADA.plusHours(24), null);
        assertTrue(auditorias(ingreso, "CIERRE_SALIDA_NO_REGISTRADA_FALLIDO").isEmpty());
    }

    @Test
    @DisplayName("Si el guarda registra la salida mientras se revisa, el sistema conserva esa salida real")
    void releeLaSalidaAntesDeCerrar() throws Exception {
        Visita ingreso = prepararIngreso(TipoIngreso.INVITADO_NO_ANUNCIADO, ENTRADA, true);
        Operador guarda = ingresar("guarda@sica.local");
        reloj.marcar(ENTRADA.plusHours(24));
        revisarMientras(() -> new ControlSalidaCase(guarda.ejecutor(), reloj).registrarSalida(
                guarda.id(), documento(ingreso), ingreso.getId()));
        gestor.ejecutar(r -> {
            Visita guardada = r.crearVisitaRepository().buscarPorId(ingreso.getId()).orElseThrow();
            assertEquals("Fuera", estado(r, guardada));
            assertEquals(ENTRADA.plusHours(24), guardada.getFechaSalida());
            assertNull(guardada.getFechaCierreSistema());
            return null;
        });
        assertEquals(1, auditorias(ingreso, "REGISTRO_SALIDA").size());
        assertTrue(auditorias(ingreso, "CIERRE_SALIDA_NO_REGISTRADA").isEmpty());
    }

    @Test
    @DisplayName("Si una nueva llegada ya regularizó la anterior, la revisión no vuelve a cerrarla")
    void respetaLaRegularizacionDeUnaNuevaLlegada() throws Exception {
        Visita ingreso = prepararIngreso(TipoIngreso.TRABAJADOR_CON_CARNET, ENTRADA, true);
        Operador guarda = ingresar("guarda@sica.local");
        reloj.marcar(ENTRADA.plusHours(24));
        List<Visita> nuevos = new ArrayList<>();
        revisarMientras(() -> nuevos.add(new ControlAccesoTrabajadorCase(guarda.ejecutor(), reloj).registrarEntrada(
                guarda.id(), documento(ingreso), true, "DEF456")));
        comprobarCierre(ingreso, ENTRADA.plusHours(24), guarda.id());
        comprobarAbierto(nuevos.get(0));
    }

    // Deja que el guarda termine después de seleccionar los ingresos, antes de que el sistema los relea.
    private void revisarMientras(Runnable accionDelGuarda) throws Exception {
        PausaTrasSeleccion pausa = new PausaTrasSeleccion();
        var hilo = Executors.newSingleThreadExecutor();
        try {
            var revision = hilo.submit(() -> new RegularizacionSalidasCase(pausa, reloj).cerrarIngresosSinSalida());
            assertTrue(pausa.seleccionada.await(30, TimeUnit.SECONDS));
            accionDelGuarda.run();
            pausa.continuar.countDown();
            assertEquals(0, revision.get(30, TimeUnit.SECONDS));
        } finally {
            pausa.continuar.countDown();
            hilo.shutdownNow();
            assertTrue(hilo.awaitTermination(25, TimeUnit.SECONDS));
        }
    }

    private final class PausaTrasSeleccion implements GestorTransacciones {
        private final AtomicBoolean primera = new AtomicBoolean(true);
        final CountDownLatch seleccionada = new CountDownLatch(1);
        final CountDownLatch continuar = new CountDownLatch(1);

        @Override
        public <T> T ejecutar(Function<FabricarRepositorios, T> operacion) {
            T resultado = gestor.ejecutar(operacion);
            if (primera.getAndSet(false)) {
                seleccionada.countDown();
                try {
                    if (!continuar.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("La prueba no pudo continuar.");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("La prueba fue interrumpida.", error);
                }
            }
            return resultado;
        }
    }

    // Prepara registros como los producidos por los casos de ingreso ya comprobados.
    private Visita prepararIngreso(TipoIngreso tipo, LocalDateTime entrada, boolean yaEntro) {
        return gestor.ejecutar(r -> {
            Usuario ana = r.crearUsuarioRepository().buscarPorEmail("ana@sol.sica.local").orElseThrow();
            boolean trabajador = tipo == TipoIngreso.TRABAJADOR_CON_CARNET
                    || tipo == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO;
            Persona persona = r.crearPersonaRepository().guardar(new Persona(
                    trabajador ? "Pedro prueba" : "Laura prueba", documentoNuevo(),
                    trabajador ? ana.getEmpresaId() : null,
                    trabajador ? TipoPersona.TRABAJADOR : TipoPersona.INVITADO,
                    r.crearEstadoAccesoPersonaRepository().buscarPorNombre("Activo").orElseThrow().getId(),
                    "foto-prueba.png", trabajador ? Boolean.TRUE : null));
            boolean requiereAprobacion = tipo != TipoIngreso.TRABAJADOR_CON_CARNET;
            return r.crearVisitaRepository().guardar(new Visita(
                    persona.getId(), ana.getEmpresaId(), requiereAprobacion ? ana.getId() : null, tipo,
                    tipo == TipoIngreso.INVITADO_PREREGISTRADO ? entrada : null,
                    requiereAprobacion ? entrada.minusMinutes(10) : null,
                    yaEntro ? entrada : null, null, null,
                    r.crearEstadoVisitaRepository().buscarPorNombre(yaEntro ? "Dentro" : "Aprobado").orElseThrow().getId(),
                    "ABC123", requiereAprobacion ? ana.getId() : null));
        });
    }

    private Operador ingresar(String correo) {
        ContextoSesion contexto = new ContextoSesion();
        sesiones.add(contexto);
        char[] clave = "SicaDemo2026!".toCharArray();
        try {
            SesionUsuario sesion = new IniciarSesionCase(gestor, new ServicioContrasenasPbkdf2()).iniciar(contexto, correo, clave);
            return new Operador(sesion.getUsuarioId(), new EjecutorOperaciones(gestor, contexto));
        } finally { Arrays.fill(clave, '\0'); }
    }

    private void comprobarAbierto(Visita ingreso) {
        gestor.ejecutar(r -> {
            Visita guardada = r.crearVisitaRepository().buscarPorId(ingreso.getId()).orElseThrow();
            assertEquals("Dentro", estado(r, guardada));
            assertEquals(ingreso.getFechaEntrada(), guardada.getFechaEntrada());
            assertNull(guardada.getFechaSalida());
            assertNull(guardada.getFechaCierreSistema());
            return null;
        });
    }

    private void comprobarCierre(Visita ingreso, LocalDateTime horaCierre, Integer responsable) {
        gestor.ejecutar(r -> {
            Visita guardada = r.crearVisitaRepository().buscarPorId(ingreso.getId()).orElseThrow();
            assertEquals("Cerrada por Sistema - Salida no registrada", estado(r, guardada));
            assertEquals(ingreso.getFechaEntrada(), guardada.getFechaEntrada());
            assertNull(guardada.getFechaSalida());
            assertEquals(horaCierre, guardada.getFechaCierreSistema());
            assertEquals(ingreso.getPersonaId(), guardada.getPersonaId());
            assertEquals(ingreso.getEmpresaDestinoId(), guardada.getEmpresaDestinoId());
            assertEquals(ingreso.getFuncionarioDestinatarioId(), guardada.getFuncionarioDestinatarioId());
            assertEquals(ingreso.getFechaProgramada(), guardada.getFechaProgramada());
            assertEquals(ingreso.getFechaAprobacion(), guardada.getFechaAprobacion());
            assertEquals(ingreso.getAprobadaPorId(), guardada.getAprobadaPorId());
            assertEquals(ingreso.getTipoIngreso(), guardada.getTipoIngreso());
            assertEquals(ingreso.getVehiculoPlaca(), guardada.getVehiculoPlaca());
            List<BitacoraAuditoria> auditorias = r.crearBitacoraAuditoriaRepository().listarPorRegistro("visitas", ingreso.getId())
                    .stream().filter(a -> "CIERRE_SALIDA_NO_REGISTRADA".equals(a.getAccionRealizada())).toList();
            assertEquals(1, auditorias.size());
            assertEquals(responsable, auditorias.get(0).getUsuarioId());
            return null;
        });
    }

    private String estado(FabricarRepositorios r, Visita visita) {
        return r.crearEstadoVisitaRepository().buscarPorId(visita.getEstadoVisitaId()).orElseThrow().getNombreEstado();
    }

    private String documento(Visita ingreso) {
        return gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorId(ingreso.getPersonaId()).orElseThrow().getDocumentoIdentidad());
    }

    private List<BitacoraAuditoria> auditorias(Visita ingreso, String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorRegistro("visitas", ingreso.getId()))
                .stream().filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }

    private String documentoNuevo() {
        return "CS" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
