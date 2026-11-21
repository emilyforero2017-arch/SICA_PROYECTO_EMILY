package com.sica.integration;

import com.sica.infrastructure.database.GestorTransaccionesJdbc;
import com.sica.infrastructure.security.ServicioContrasenasPbkdf2;
import com.sica.model.application.usecase.*;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.entity.Visita;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sica.pruebas.jdbc", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Recorridos: trabajador con carnet y pase por carnet olvidado")
class IngresoTrabajadorIT {

    private static final ZoneId ZONA = ZoneId.of("America/Bogota");
    private static final LocalDateTime HORA = LocalDateTime.of(2026, 12, 15, 10, 0);
    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private RelojTrabajador reloj;
    private final List<Aplicacion> aplicaciones = new ArrayList<>();
    private Aplicacion ana;
    private Aplicacion guarda;
    private Persona pedro;
    private Integer empresaId;

    private final class Aplicacion implements AutoCloseable {
        final ContextoSesion contexto = new ContextoSesion();
        final EjecutorOperaciones ejecutor = new EjecutorOperaciones(gestor, contexto);
        final PersonaCase personas = new PersonaCase(ejecutor);
        final ControlAccesoTrabajadorCase habitual = new ControlAccesoTrabajadorCase(ejecutor, reloj);
        final SolicitudIngresoCase solicitudes = new SolicitudIngresoCase(ejecutor, reloj);
        final ControlSolicitudCase pases = new ControlSolicitudCase(ejecutor, reloj);
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

    // Solo mueve la hora de estos recorridos de prueba.
    private static final class RelojTrabajador extends Clock {
        private volatile Instant actual;
        void marcar(LocalDateTime hora) { actual = hora.atZone(ZONA).toInstant(); }
        @Override public ZoneId getZone() { return ZONA; }
        @Override public Clock withZone(ZoneId zona) { return Clock.fixed(actual, zona); }
        @Override public Instant instant() { return actual; }
    }

    @BeforeAll
    void prepararBase() throws Exception { base = BaseDatosTemporal.crear(); }

    @BeforeEach
    void prepararTrabajador() {
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        reloj = new RelojTrabajador();
        reloj.marcar(HORA);
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
        pedro = registrarTrabajador("Pedro prueba");
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
    @DisplayName("Con carnet entra sin cita ni aprobación diaria y después queda registrada su salida")
    void completaIngresoHabitualYSalida() {
        Visita ingreso = entrarConCarnet(pedro);
        Visita guardado = visita(ingreso);
        assertEquals(TipoIngreso.TRABAJADOR_CON_CARNET, guardado.getTipoIngreso());
        assertEquals(empresaId, guardado.getEmpresaDestinoId());
        assertEquals(pedro.getId(), guardado.getPersonaId());
        assertNull(guardado.getFechaProgramada());
        assertNull(guardado.getFechaAprobacion());
        assertNull(guardado.getAprobadaPorId());
        assertNull(guardado.getFuncionarioDestinatarioId());
        assertEquals(HORA, guardado.getFechaEntrada());
        assertEquals("ABC123", guardado.getVehiculoPlaca());
        assertEstado(ingreso, "Dentro");
        assertEquals(guarda.id(), auditorias(ingreso, "REGISTRO_ENTRADA").get(0).getUsuarioId());
        reloj.marcar(HORA.plusHours(8));
        salir(pedro, ingreso);
        assertEstado(ingreso, "Fuera");
        assertEquals(HORA.plusHours(8), visita(ingreso).getFechaSalida());
        assertNull(visita(ingreso).getFechaCierreSistema());
        assertEquals(1, auditorias(ingreso, "REGISTRO_SALIDA").size());
        assertTrue(guarda.salidas.consultarIngresoAbierto(guarda.id(), pedro.getDocumentoIdentidad()).isEmpty());
    }

    @Test
    @DisplayName("Sin carnet necesita aprobación; el pase avisa al destinatario y al guarda y se usa una sola vez")
    void completaPasePorCarnetOlvidado() {
        assertThrows(IllegalArgumentException.class,
                () -> guarda.habitual.registrarEntrada(guarda.id(), pedro.getDocumentoIdentidad(), false, null));
        Visita pase = solicitar(pedro);
        assertEstado(pase, "Pendiente de Aprobación por Olvido");
        assertEquals(TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO, visita(pase).getTipoIngreso());
        assertEquals(empresaId, visita(pase).getEmpresaDestinoId());
        assertEquals(ana.id(), visita(pase).getFuncionarioDestinatarioId());
        assertFalse(guarda.pases.consultarResultado(guarda.id(), pase.getId()).isPermiteIngreso());
        assertThrows(IllegalStateException.class, () -> entrarConPase(pedro, pase));
        List<AvisoSolicitudPendiente> pendientes = ana.avisos.consultarAvisosFuncionario(ana.id(), Set.of());
        assertEquals(1, pendientes.size());
        assertEquals(pase.getId(), pendientes.get(0).getSolicitudId());
        assertEquals(TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO, pendientes.get(0).getTipoIngreso());
        assertTrue(pendientes.get(0).getMensaje().contains("carnet"));

        aprobar(pase, HORA.plusMinutes(5));
        ResultadoSolicitudPorteria resultado = guarda.pases.consultarResultado(guarda.id(), pase.getId());
        assertTrue(resultado.isPermiteIngreso());
        assertEquals(HORA.plusMinutes(5), resultado.getHorario().orElseThrow().getDesde());
        assertEquals(HORA.plusMinutes(35).withSecond(59).withNano(999_999_999), resultado.getHorario().orElseThrow().getHasta());
        assertEquals(ana.id(), visita(pase).getAprobadaPorId());
        assertEquals(1, guarda.avisos.consultarAvisos(guarda.id(), Set.of()).size());
        entrarConPase(pedro, pase);
        assertThrows(IllegalStateException.class, () -> entrarConPase(pedro, pase));
        reloj.marcar(HORA.plusMinutes(10));
        salir(pedro, pase);
        assertThrows(IllegalStateException.class, () -> entrarConPase(pedro, pase));
        assertEstado(pase, "Fuera");
        assertEquals(1, auditorias(pase, "SOLICITUD_CARNET_OLVIDADO").size());
        assertEquals(1, auditorias(pase, "APROBACION_SOLICITUD").size());
        assertEquals(1, auditorias(pase, "REGISTRO_ENTRADA").size());
        assertEquals(1, auditorias(pase, "REGISTRO_SALIDA").size());
    }

    @Test
    @DisplayName("Un pase aprobado a las 23:50 termina ese día y no permite entrar después de medianoche")
    void limitaElPaseAlDiaDeAprobacion() {
        Persona carlos = registrarTrabajador("Carlos prueba");
        Visita antesDeMedianoche = solicitar(pedro);
        Visita despuesDeMedianoche = solicitar(carlos);
        LocalDateTime noche = HORA.toLocalDate().atTime(23, 50);
        aprobar(antesDeMedianoche, noche);
        aprobar(despuesDeMedianoche, noche);
        assertEquals(HORA.toLocalDate().atTime(LocalTime.MAX), guarda.pases
                .consultarResultado(guarda.id(), antesDeMedianoche.getId()).getHorario().orElseThrow().getHasta());
        reloj.marcar(HORA.toLocalDate().atTime(LocalTime.MAX));
        entrarConPase(pedro, antesDeMedianoche);
        assertEquals(HORA.toLocalDate().atTime(23, 59, 59), visita(antesDeMedianoche).getFechaEntrada());
        reloj.marcar(HORA.toLocalDate().plusDays(1).atStartOfDay());
        assertThrows(IllegalStateException.class, () -> entrarConPase(carlos, despuesDeMedianoche));
        assertEstado(despuesDeMedianoche, "Expirado");
        assertSinMovimiento(despuesDeMedianoche);
        assertEstado(antesDeMedianoche, "Dentro");
        assertEquals(1, auditorias(despuesDeMedianoche, "EXPIRACION_VISITA").size());
    }

    @Test
    @DisplayName("El pase incluye su último minuto y los demás pases sin usar expiran en la revisión automática")
    void verificaTreintaMinutosYVencimiento() {
        Persona carlos = registrarTrabajador("Carlos prueba");
        Visita ultimoMinuto = solicitar(pedro);
        Visita vencido = solicitar(carlos);
        aprobar(ultimoMinuto, HORA);
        aprobar(vencido, HORA);
        reloj.marcar(HORA.plusMinutes(30).withSecond(59).withNano(999_999_999));
        entrarConPase(pedro, ultimoMinuto);
        assertEquals(HORA.plusMinutes(30).withSecond(59), visita(ultimoMinuto).getFechaEntrada());
        reloj.marcar(HORA.plusMinutes(31));
        assertTrue(new VencimientoVisitasCase(gestor, reloj).expirarPendientes() >= 1);
        assertEstado(vencido, "Expirado");
        assertThrows(IllegalStateException.class, () -> entrarConPase(carlos, vencido));
        assertSinMovimiento(vencido);
        assertEstado(ultimoMinuto, "Dentro");
        assertEquals(1, auditorias(vencido, "EXPIRACION_VISITA").size());
    }

    @Test
    @DisplayName("La prohibición bloquea el carnet y el pase aprobado; retirarla permite las entradas correspondientes")
    void compruebaProhibicionEnAmbasEntradas() {
        Visita pase = solicitar(pedro);
        aprobar(pase, HORA);
        Aplicacion supervisor = ingresar("supervisor@sica.local");
        RestriccionAccesoCase restricciones = new RestriccionAccesoCase(supervisor.ejecutor);
        restricciones.imponerProhibicion(supervisor.id(), pedro.getId(), "Revisión pendiente");
        assertThrows(IllegalStateException.class, () -> entrarConCarnet(pedro));
        assertThrows(IllegalStateException.class, () -> entrarConPase(pedro, pase));
        assertEstado(pase, "Aprobado");
        assertSinMovimiento(pase);
        restricciones.retirarProhibicion(supervisor.id(), pedro.getId(), "Situación aclarada");
        entrarConPase(pedro, pase);
        reloj.marcar(HORA.plusMinutes(10));
        salir(pedro, pase);
        reloj.marcar(HORA.plusDays(1));
        Visita habitual = entrarConCarnet(pedro);
        assertEstado(habitual, "Dentro");
        assertEquals(Boolean.TRUE, trabajador(pedro).getVinculacionLaboralActiva());
    }

    @Test
    @DisplayName("Finalizar el vínculo bloquea el carnet, nuevos pases, la aprobación pendiente y un pase ya aprobado")
    void revisaVinculacionAntesDeAprobarYDeEntrar() {
        Persona carlos = registrarTrabajador("Carlos prueba");
        Visita pendiente = solicitar(pedro);
        Visita aprobado = solicitar(carlos);
        aprobar(aprobado, HORA);
        ana.personas.finalizarVinculacionLaboral(ana.id(), pedro.getId(), "Terminó su contrato");
        ana.personas.finalizarVinculacionLaboral(ana.id(), carlos.getId(), "Terminó su contrato");
        assertThrows(IllegalStateException.class, () -> entrarConCarnet(pedro));
        assertThrows(IllegalStateException.class, () -> solicitar(pedro));
        assertThrows(IllegalStateException.class, () -> ana.solicitudes.aprobar(ana.id(), pendiente.getId()));
        assertThrows(IllegalStateException.class, () -> entrarConPase(carlos, aprobado));
        assertEstado(pendiente, "Pendiente de Aprobación por Olvido");
        assertEstado(aprobado, "Aprobado");
        assertSinMovimiento(pendiente);
        assertSinMovimiento(aprobado);
        assertEquals(Boolean.FALSE, trabajador(pedro).getVinculacionLaboralActiva());
        assertEquals(Boolean.FALSE, trabajador(carlos).getVinculacionLaboralActiva());
        assertEquals(empresaId, trabajador(pedro).getEmpresaId());
    }

    @Test
    @DisplayName("La empresa retirada bloquea nuevos ingresos laborales y permite registrar la salida pendiente")
    void permiteSalirDeUnaEmpresaRetirada() {
        Visita ingreso = entrarConCarnet(pedro);
        Persona carlos = registrarTrabajador("Carlos prueba");
        Visita pase = solicitar(carlos);
        aprobar(pase, HORA);
        Aplicacion admin = ingresar("admin@sica.local");
        new EmpresaUseCase(admin.ejecutor).desactivar(admin.id(), empresaId, "La empresa salió del conjunto");
        assertThrows(IllegalStateException.class, () -> entrarConCarnet(carlos));
        assertThrows(IllegalStateException.class, () -> entrarConPase(carlos, pase));
        assertThrows(IllegalStateException.class, () -> solicitar(carlos));
        reloj.marcar(HORA.plusHours(1));
        salir(pedro, ingreso);
        assertEstado(ingreso, "Fuera");
        assertEquals(HORA.plusHours(1), visita(ingreso).getFechaSalida());
        assertSinMovimiento(pase);
        assertEquals(Boolean.FALSE, trabajador(pedro).getVinculacionLaboralActiva());
    }

    @Test
    @DisplayName("Comprueba permisos, destinatario de la empresa y que el pase corresponda a un trabajador")
    void compruebaPermisosYTipoDePersona() {
        Persona laura = ana.personas.registrarInvitado(ana.id(), "Laura prueba", documentoNuevo(), "foto-laura.png");
        Aplicacion marta = ingresar("marta@luna.sica.local");
        assertThrows(IllegalArgumentException.class, () -> entrarConCarnet(laura));
        assertThrows(IllegalArgumentException.class, () -> solicitar(laura));
        assertThrows(IllegalArgumentException.class, () -> guarda.solicitudes.solicitarPorCarnetOlvidado(
                guarda.id(), pedro.getDocumentoIdentidad(), marta.id(), null));
        assertThrows(SecurityException.class, () -> ana.habitual.registrarEntrada(
                ana.id(), pedro.getDocumentoIdentidad(), true, null));
        Visita pase = solicitar(pedro);
        assertThrows(SecurityException.class, () -> guarda.solicitudes.aprobar(guarda.id(), pase.getId()));
        assertThrows(IllegalArgumentException.class, () -> marta.solicitudes.aprobar(marta.id(), pase.getId()));
        aprobar(pase, HORA);
        assertThrows(IllegalArgumentException.class, () -> guarda.pases.registrarEntrada(
                guarda.id(), pase.getId(), laura.getDocumentoIdentidad()));
        assertEstado(pase, "Aprobado");
        assertSinMovimiento(pase);
    }

    @Test
    @DisplayName("Si falla la solicitud por carnet olvidado, no queda un pase ni se altera al trabajador")
    void deshaceSolicitudFallida() {
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> solicitar(pedro));
        assertTrue(gestor.ejecutar(r -> r.crearVisitaRepository().listarPorPersona(pedro.getId())).isEmpty());
        assertEquals(Boolean.TRUE, trabajador(pedro).getVinculacionLaboralActiva());
        assertEquals(empresaId, trabajador(pedro).getEmpresaId());
        assertTrue(ana.avisos.consultarAvisosFuncionario(ana.id(), Set.of()).isEmpty());
        assertEquals(1, fallosDelGuarda("SOLICITUD_CARNET_OLVIDADO_FALLIDA").size());
    }

    @Test
    @DisplayName("Si falla la auditoría de la entrada con carnet, se deshace el ingreso y puede volver a intentarse")
    void deshaceEntradaHabitualFallida() {
        acceso.fallarProximasAuditorias(1);
        assertThrows(IllegalStateException.class, () -> entrarConCarnet(pedro));
        assertTrue(gestor.ejecutar(r -> r.crearVisitaRepository().listarPorPersona(pedro.getId())).isEmpty());
        assertEquals(1, fallosDelGuarda("REGISTRO_ENTRADA_FALLIDO").size());
        Visita ingreso = entrarConCarnet(pedro);
        assertEstado(ingreso, "Dentro");
        assertEquals(1, auditorias(ingreso, "REGISTRO_ENTRADA").size());
    }

    @Test
    @DisplayName("Un nuevo ingreso con carnet regulariza la salida olvidada sin inventar la hora real")
    void regularizaElIngresoHabitualAnterior() {
        Visita anterior = entrarConCarnet(pedro);
        reloj.marcar(HORA.plusDays(1));
        Visita nuevo = entrarConCarnet(pedro);
        comprobarRegularizacion(anterior, nuevo, HORA.plusDays(1));
    }

    @Test
    @DisplayName("Si falla la nueva entrada con carnet, también se deshace el cierre del ingreso anterior")
    void deshaceRegularizacionHabitualSiFallaLaNuevaEntrada() {
        Visita anterior = entrarConCarnet(pedro);
        reloj.marcar(HORA.plusDays(1));
        // Primero se audita el cierre anterior y después la nueva entrada.
        acceso.fallarAuditoriaNumero(2);
        assertThrows(IllegalStateException.class, () -> entrarConCarnet(pedro));
        assertEstado(anterior, "Dentro");
        assertEquals(HORA, visita(anterior).getFechaEntrada());
        assertNull(visita(anterior).getFechaSalida());
        assertNull(visita(anterior).getFechaCierreSistema());
        assertTrue(auditorias(anterior, "CIERRE_SALIDA_NO_REGISTRADA").isEmpty());
        assertEquals(1, gestor.ejecutar(r -> r.crearVisitaRepository().listarPorPersona(pedro.getId())).size());
        assertEquals(1, fallosDelGuarda("REGISTRO_ENTRADA_FALLIDO").size());
    }

    @Test
    @DisplayName("Un pase por carnet olvidado también regulariza una entrada anterior sin salida")
    void regularizaElIngresoAnteriorConUnPase() {
        Visita anterior = entrarConCarnet(pedro);
        reloj.marcar(HORA.plusDays(1));
        Visita pase = solicitar(pedro);
        aprobar(pase, HORA.plusDays(1));
        entrarConPase(pedro, pase);
        comprobarRegularizacion(anterior, pase, HORA.plusDays(1));
    }

    @Test
    @DisplayName("Si falla el ingreso con pase, conserva el ingreso anterior abierto y la autorización sin usar")
    void deshaceRegularizacionDelPaseYPermiteReintentar() {
        Visita anterior = entrarConCarnet(pedro);
        reloj.marcar(HORA.plusDays(1));
        Visita pase = solicitar(pedro);
        aprobar(pase, HORA.plusDays(1));
        acceso.fallarAuditoriaNumero(2);
        assertThrows(IllegalStateException.class, () -> entrarConPase(pedro, pase));
        assertEstado(anterior, "Dentro");
        assertNull(visita(anterior).getFechaSalida());
        assertNull(visita(anterior).getFechaCierreSistema());
        assertEstado(pase, "Aprobado");
        assertSinMovimiento(pase);
        assertTrue(auditorias(anterior, "CIERRE_SALIDA_NO_REGISTRADA").isEmpty());
        assertEquals(1, auditorias(pase, "REGISTRO_ENTRADA_FALLIDO").size());
        entrarConPase(pedro, pase);
        comprobarRegularizacion(anterior, pase, HORA.plusDays(1));
    }

    private void comprobarRegularizacion(Visita anterior, Visita nuevo, LocalDateTime hora) {
        assertEstado(anterior, "Cerrada por Sistema - Salida no registrada");
        assertNull(visita(anterior).getFechaSalida());
        assertEquals(hora, visita(anterior).getFechaCierreSistema());
        assertEquals(HORA, visita(anterior).getFechaEntrada());
        assertEstado(nuevo, "Dentro");
        assertEquals(hora, visita(nuevo).getFechaEntrada());
        assertEquals(nuevo.getId(), guarda.salidas.consultarIngresoAbierto(guarda.id(), pedro.getDocumentoIdentidad()).orElseThrow().getId());
        assertEquals(1, auditorias(anterior, "CIERRE_SALIDA_NO_REGISTRADA").size());
        assertEquals(1, auditorias(nuevo, "REGISTRO_ENTRADA").size());
    }

    private Aplicacion ingresar(String correo) {
        Aplicacion app = new Aplicacion(correo);
        aplicaciones.add(app);
        return app;
    }

    private Persona registrarTrabajador(String nombre) {
        return ana.personas.registrarTrabajador(ana.id(), nombre, documentoNuevo(), "foto-trabajador.png");
    }

    private Persona trabajador(Persona persona) {
        return gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorId(persona.getId()).orElseThrow());
    }

    private Visita solicitar(Persona trabajador) {
        return guarda.solicitudes.solicitarPorCarnetOlvidado(guarda.id(), trabajador.getDocumentoIdentidad(), ana.id(), "ABC123");
    }

    private void aprobar(Visita pase, LocalDateTime hora) {
        reloj.marcar(hora);
        ana.solicitudes.aprobar(ana.id(), pase.getId());
    }

    private Visita entrarConCarnet(Persona trabajador) {
        return guarda.habitual.registrarEntrada(guarda.id(), trabajador.getDocumentoIdentidad(), true, "ABC123");
    }

    private void entrarConPase(Persona trabajador, Visita pase) {
        guarda.pases.registrarEntrada(guarda.id(), pase.getId(), trabajador.getDocumentoIdentidad());
    }

    private void salir(Persona trabajador, Visita ingreso) {
        guarda.salidas.registrarSalida(guarda.id(), trabajador.getDocumentoIdentidad(), ingreso.getId());
    }

    private Visita visita(Visita ingreso) {
        return gestor.ejecutar(r -> r.crearVisitaRepository().buscarPorId(ingreso.getId()).orElseThrow());
    }

    private void assertEstado(Visita ingreso, String nombre) {
        gestor.ejecutar(r -> {
            Visita guardado = r.crearVisitaRepository().buscarPorId(ingreso.getId()).orElseThrow();
            assertEquals(nombre, r.crearEstadoVisitaRepository().buscarPorId(guardado.getEstadoVisitaId()).orElseThrow().getNombreEstado());
            return null;
        });
    }

    private void assertSinMovimiento(Visita ingreso) {
        Visita guardado = visita(ingreso);
        assertNull(guardado.getFechaEntrada());
        assertNull(guardado.getFechaSalida());
        assertNull(guardado.getFechaCierreSistema());
    }

    private List<BitacoraAuditoria> auditorias(Visita ingreso, String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorRegistro("visitas", ingreso.getId()))
                .stream().filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }

    private List<BitacoraAuditoria> fallosDelGuarda(String accion) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(guarda.id()))
                .stream().filter(a -> accion.equals(a.getAccionRealizada())).toList();
    }

    private String documentoNuevo() {
        return "TC" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
