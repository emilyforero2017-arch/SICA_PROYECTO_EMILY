package com.sica.integration;

import com.sica.infrastructure.database.GestorTransaccionesJdbc;
import com.sica.infrastructure.security.ServicioContrasenasPbkdf2;
import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.application.usecase.*;
import com.sica.model.domain.entity.*;
import java.sql.SQLException;
import java.time.Clock;
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
@DisplayName("Recorridos: consultas de seguridad y reportes de accesos")
class ReportesAccesosIT {

    private static final ZoneId ZONA = ZoneId.of("America/Bogota");
    // Separa los movimientos de prueba de los ejemplos recientes incluidos en data.sql.
    private static final LocalDateTime DIA = LocalDateTime.of(2026, 1, 12, 8, 0);
    private BaseDatosTemporal base;
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private final List<Aplicacion> aplicaciones = new ArrayList<>();
    private Aplicacion supervisor;

    private final class Aplicacion implements AutoCloseable {
        final ContextoSesion contexto = new ContextoSesion();
        final EjecutorOperaciones ejecutor = new EjecutorOperaciones(gestor, contexto);
        final SeguridadAccesosCase reportes = new SeguridadAccesosCase(ejecutor);
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

    private record EstadoGuardado(Integer estado, LocalDateTime entrada, LocalDateTime salida,
                                  LocalDateTime cierre, Integer persona, Integer empresa) { }

    @BeforeEach
    void preparar() throws Exception {
        base = BaseDatosTemporal.crear();
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        supervisor = ingresar("supervisor@sica.local");
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
    @DisplayName("La consulta por documento reúne las visitas de esa persona en distintas empresas y estados")
    void consultaLaPersonaSinMezclarOtrasIdentidades() {
        Visita anterior = preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", "Fuera",
                DIA.minusDays(1), DIA.minusDays(1).plusHours(2), null);
        Persona laura = persona(anterior);
        Visita pendiente = preparar(TipoIngreso.INVITADO_NO_ANUNCIADO, "marta@luna.sica.local", laura,
                "Pendiente de Aprobación", null, null, null);
        Visita cancelada = preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", laura,
                "Cancelada", null, null, null);
        Visita expirada = preparar(TipoIngreso.INVITADO_PREREGISTRADO, "marta@luna.sica.local", laura,
                "Expirado", null, null, null);
        preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", "Dentro", DIA, null, null);

        List<DetalleAccesoSeguridad> datos = supervisor.reportes.consultarVisitasDePersona(supervisor.id(), laura.getDocumentoIdentidad());
        assertEquals(List.of(expirada.getId(), cancelada.getId(), pendiente.getId(), anterior.getId()), idsAccesos(datos));
        assertEquals(List.of("Expirado", "Cancelada", "Pendiente de Aprobación", "Fuera"),
                datos.stream().map(d -> d.getEstado().getNombreEstado()).toList());
        assertEquals(List.of("Empresa Luna", "Empresa Sol", "Empresa Luna", "Empresa Sol"),
                datos.stream().map(DetalleAccesoSeguridad::getEmpresa).toList());
        for (DetalleAccesoSeguridad detalle : datos) {
            assertEquals(laura.getId(), detalle.getPersona().getId());
            assertEquals("foto-reporte.png", detalle.getPersona().getUrlFoto());
            assertNull(detalle.getPersona().getEmpresaId());
            assertTrue(detalle.getFuncionario().isPresent());
        }
    }

    @Test
    @DisplayName("Solo figura dentro quien tiene entrada abierta; incluye los cuatro tipos y ordena por llegada")
    void distingueQuienSigueDentro() {
        List<Visita> dentro = new ArrayList<>();
        for (TipoIngreso tipo : TipoIngreso.values()) {
            dentro.add(preparar(tipo, "ana@sol.sica.local", "Dentro", DIA, null, null));
        }
        dentro.add(preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "ana@sol.sica.local", "Dentro", DIA.plusHours(1), null, null));
        List<Visita> excluidas = new ArrayList<>();
        excluidas.add(preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", "Fuera", DIA, DIA.plusHours(1), null));
        excluidas.add(preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "ana@sol.sica.local",
                "Cerrada por Sistema - Salida no registrada", DIA.minusDays(1), null, DIA));
        for (String estado : List.of("Aprobado", "Pendiente de Aprobación", "Rechazado")) {
            excluidas.add(preparar(TipoIngreso.INVITADO_NO_ANUNCIADO, "ana@sol.sica.local", estado, null, null, null));
        }
        for (String estado : List.of("Cancelada", "Expirado")) {
            excluidas.add(preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", estado, null, null, null));
        }

        List<DetalleAccesoSeguridad> reporte = supervisor.reportes.consultarIngresosAbiertos(supervisor.id());
        Set<Integer> propios = dentro.stream().map(Visita::getId).collect(Collectors.toSet());
        List<Integer> orden = new ArrayList<>(dentro.stream().map(Visita::getId).toList());
        Collections.reverse(orden);
        // El conjunto también conserva al trabajador de demostración que ya estaba dentro.
        assertEquals(orden, reporte.stream().filter(d -> propios.contains(d.getVisita().getId()))
                .map(d -> d.getVisita().getId()).toList());
        for (Visita excluida : excluidas) assertFalse(idsAccesos(reporte).contains(excluida.getId()));
        for (DetalleAccesoSeguridad detalle : reporte) {
            assertEquals("Dentro", detalle.getEstado().getNombreEstado());
            assertNotNull(detalle.getVisita().getFechaEntrada());
            assertNull(detalle.getVisita().getFechaSalida());
            assertNull(detalle.getVisita().getFechaCierreSistema());
            assertEquals(detalle.getVisita().getTipoIngreso() != TipoIngreso.TRABAJADOR_CON_CARNET,
                    detalle.getFuncionario().isPresent());
        }
    }

    @Test
    @DisplayName("El reporte de hoy incluye una salida y un cierre de personas que habían entrado ayer")
    void incluyeMovimientosDeVisitasQueEmpezaronAntes() {
        Visita salida = preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", "Fuera",
                DIA.minusDays(1), DIA.plusHours(1), null);
        Visita cierre = preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "marta@luna.sica.local",
                "Cerrada por Sistema - Salida no registrada", DIA.minusDays(1), null, DIA.plusHours(2));
        Visita entrada = preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "ana@sol.sica.local", "Dentro",
                DIA.plusMinutes(30), null, null);
        List<DetalleMovimientoAcceso> reporte = historial(DIA, DIA.plusHours(3));
        assertEquals(List.of(cierre.getId(), salida.getId(), entrada.getId()), idsMovimientos(reporte));
        assertEquals(List.of(TipoMovimientoAcceso.CIERRE_SIN_SALIDA_REGISTRADA, TipoMovimientoAcceso.SALIDA,
                TipoMovimientoAcceso.ENTRADA), reporte.stream().map(DetalleMovimientoAcceso::getTipo).toList());
        assertEquals(List.of(DIA.plusHours(2), DIA.plusHours(1), DIA.plusMinutes(30)),
                reporte.stream().map(DetalleMovimientoAcceso::getFechaMovimiento).toList());
        assertNull(reporte.get(0).getAcceso().getVisita().getFechaSalida());
        assertEquals(DIA.minusDays(1), reporte.get(0).getAcceso().getVisita().getFechaEntrada());
        assertEquals("Empresa Luna", reporte.get(0).getAcceso().getEmpresa());
    }

    @Test
    @DisplayName("Cada entrada, salida y cierre del período aparece una sola vez sin inventar salidas")
    void noDuplicaLosMovimientosDeUnaVisita() {
        Visita salida = preparar(TipoIngreso.INVITADO_NO_ANUNCIADO, "ana@sol.sica.local", "Fuera",
                DIA.plusMinutes(15), DIA.plusMinutes(45), null);
        Visita cierre = preparar(TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO, "marta@luna.sica.local",
                "Cerrada por Sistema - Salida no registrada", DIA.minusDays(1), null, DIA.plusMinutes(30));
        List<DetalleMovimientoAcceso> reporte = historial(DIA.minusDays(1), DIA.plusHours(1));
        assertEquals(List.of(salida.getId(), cierre.getId(), salida.getId(), cierre.getId()), idsMovimientos(reporte));
        assertEquals(List.of(TipoMovimientoAcceso.SALIDA, TipoMovimientoAcceso.CIERRE_SIN_SALIDA_REGISTRADA,
                TipoMovimientoAcceso.ENTRADA, TipoMovimientoAcceso.ENTRADA),
                reporte.stream().map(DetalleMovimientoAcceso::getTipo).toList());
        assertEquals(4, reporte.stream().map(d -> d.getAcceso().getVisita().getId() + ":" + d.getTipo()
                + ":" + d.getFechaMovimiento()).distinct().count());
        assertTrue(reporte.stream().noneMatch(d -> d.getAcceso().getVisita().getId().equals(cierre.getId())
                && d.getTipo() == TipoMovimientoAcceso.SALIDA));
    }

    @Test
    @DisplayName("Incluye el comienzo del período y excluye el final, también al consultar fracciones de segundo")
    void respetaLosLimitesDeFecha() {
        Visita anterior = preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "ana@sol.sica.local", "Dentro", DIA.minusSeconds(1), null, null);
        Visita inicio = preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "ana@sol.sica.local", "Dentro", DIA, null, null);
        Visita siguiente = preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "ana@sol.sica.local", "Dentro", DIA.plusSeconds(1), null, null);
        preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "ana@sol.sica.local", "Dentro", DIA.plusHours(1), null, null);
        Visita salida = preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", "Fuera", DIA.minusHours(2), DIA, null);
        preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "ana@sol.sica.local", "Cerrada por Sistema - Salida no registrada",
                DIA.minusDays(2), null, DIA.plusHours(1));
        assertEquals(List.of(siguiente.getId(), salida.getId(), inicio.getId()), idsMovimientos(historial(DIA, DIA.plusHours(1))));
        assertEquals(List.of(siguiente.getId()), idsMovimientos(historial(DIA.plusNanos(1), DIA.plusSeconds(1).plusNanos(1))));
        assertEquals(List.of(anterior.getId()), idsMovimientos(historial(DIA.minusSeconds(1), DIA)));
    }

    @Test
    @DisplayName("Las coincidencias de hora conservan un orden estable y no pierden movimientos")
    void ordenaMovimientosQueCoincidenEnLaHora() {
        Visita instantanea = preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", "Fuera", DIA, DIA, null);
        Visita trabajador = preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "ana@sol.sica.local", "Dentro", DIA, null, null);
        List<DetalleMovimientoAcceso> reporte = historial(DIA, DIA.plusMinutes(1));
        assertEquals(List.of(trabajador.getId(), instantanea.getId(), instantanea.getId()), idsMovimientos(reporte));
        assertEquals(List.of(TipoMovimientoAcceso.ENTRADA, TipoMovimientoAcceso.SALIDA, TipoMovimientoAcceso.ENTRADA),
                reporte.stream().map(DetalleMovimientoAcceso::getTipo).toList());
    }

    @Test
    @DisplayName("Los permisos y una sesión vigente son obligatorios para consultar reportes de seguridad")
    void compruebaPermisosYSesion() {
        Visita visita = preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", "Dentro", DIA, null, null);
        String documento = persona(visita).getDocumentoIdentidad();
        for (String correo : List.of("guarda@sica.local", "ana@sol.sica.local", "admin@sica.local")) {
            Aplicacion otra = ingresar(correo);
            assertThrows(SecurityException.class, () -> otra.reportes.consultarVisitasDePersona(otra.id(), documento));
            assertThrows(SecurityException.class, () -> otra.reportes.consultarIngresosAbiertos(otra.id()));
            assertThrows(SecurityException.class, () -> otra.reportes.consultarHistorial(otra.id(), DIA, DIA.plusDays(1)));
        }
        ContextoSesion vacio = new ContextoSesion();
        SeguridadAccesosCase sinSesion = new SeguridadAccesosCase(new EjecutorOperaciones(gestor, vacio));
        assertThrows(SecurityException.class, () -> sinSesion.consultarIngresosAbiertos(supervisor.id()));
        supervisor.close();
        assertThrows(SecurityException.class, () -> supervisor.reportes.consultarVisitasDePersona(supervisor.id(), documento));
    }

    @Test
    @DisplayName("Responde sin datos cuando corresponde y rechaza documentos o períodos inválidos")
    void manejaConsultasVaciasYDatosInvalidos() {
        Persona sinVisitas = gestor.ejecutar(r -> nuevaPersona(r, false, null));
        assertTrue(supervisor.reportes.consultarVisitasDePersona(supervisor.id(), sinVisitas.getDocumentoIdentidad()).isEmpty());
        assertTrue(historial(DIA, DIA.plusDays(1)).isEmpty());
        for (String documento : Arrays.asList(null, "", " ", "X".repeat(21), "NO-REGISTRADO")) {
            assertThrows(IllegalArgumentException.class,
                    () -> supervisor.reportes.consultarVisitasDePersona(supervisor.id(), documento));
        }
        assertThrows(IllegalArgumentException.class, () -> supervisor.reportes.consultarHistorial(supervisor.id(), null, DIA));
        assertThrows(IllegalArgumentException.class, () -> supervisor.reportes.consultarHistorial(supervisor.id(), DIA, null));
        assertThrows(IllegalArgumentException.class, () -> historial(DIA, DIA));
        assertThrows(IllegalArgumentException.class, () -> historial(DIA, DIA.minusDays(1)));

        // Los datos iniciales sí tienen un trabajador dentro: registramos su salida para verificar la lista vacía.
        Aplicacion guarda = ingresar("guarda@sica.local");
        List<Visita> abiertos = gestor.ejecutar(r -> r.crearVisitaRepository().listarIngresosAbiertos());
        for (Visita ingreso : abiertos) {
            new ControlSalidaCase(guarda.ejecutor, Clock.system(ZONA)).registrarSalida(
                    guarda.id(), persona(ingreso).getDocumentoIdentidad(), ingreso.getId());
        }
        assertTrue(supervisor.reportes.consultarIngresosAbiertos(supervisor.id()).isEmpty());
    }

    @Test
    @DisplayName("La empresa retirada y el anfitrión inactivo conservan su historial y las salidas pendientes")
    void conservaDatosDeUnaEmpresaRetirada() {
        Visita anterior = preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", "Fuera", DIA, DIA.plusHours(1), null);
        Visita trabajador = preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "ana@sol.sica.local", "Dentro", DIA, null, null);
        Aplicacion admin = ingresar("admin@sica.local");
        new EmpresaUseCase(admin.ejecutor).desactivar(admin.id(), anterior.getEmpresaDestinoId(), "Empresa retirada");
        DetalleAccesoSeguridad guardado = supervisor.reportes.consultarVisitasDePersona(
                supervisor.id(), persona(anterior).getDocumentoIdentidad()).get(0);
        assertEquals("Empresa Sol", guardado.getEmpresa());
        assertEquals("Ana Gómez", guardado.getFuncionario().orElseThrow());
        assertEquals(anterior.getId(), guardado.getVisita().getId());
        assertTrue(idsAccesos(supervisor.reportes.consultarIngresosAbiertos(supervisor.id())).contains(trabajador.getId()));
        Aplicacion guarda = ingresar("guarda@sica.local");
        new ControlSalidaCase(guarda.ejecutor, reloj(DIA.plusHours(2))).registrarSalida(
                guarda.id(), persona(trabajador).getDocumentoIdentidad(), trabajador.getId());
        assertFalse(idsAccesos(supervisor.reportes.consultarIngresosAbiertos(supervisor.id())).contains(trabajador.getId()));
        List<DetalleMovimientoAcceso> movimientos = historial(DIA, DIA.plusHours(3));
        assertEquals(4, movimientos.size());
        assertTrue(movimientos.stream().anyMatch(d -> d.getAcceso().getVisita().getId().equals(trabajador.getId())
                && d.getTipo() == TipoMovimientoAcceso.SALIDA));
    }

    @Test
    @DisplayName("El cierre por 24 horas se refleja en los reportes como cierre, sin inventar una salida real")
    void reflejaElCierreDelSistemaEnLosReportes() {
        Visita invitada = preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", "Dentro", DIA.minusDays(1), null, null);
        Visita trabajador = preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "marta@luna.sica.local", "Dentro", DIA.minusDays(1), null, null);
        assertTrue(idsAccesos(supervisor.reportes.consultarIngresosAbiertos(supervisor.id())).containsAll(List.of(invitada.getId(), trabajador.getId())));
        assertEquals(2, new RegularizacionSalidasCase(gestor, reloj(DIA)).cerrarIngresosSinSalida());
        List<Integer> abiertos = idsAccesos(supervisor.reportes.consultarIngresosAbiertos(supervisor.id()));
        assertFalse(abiertos.contains(invitada.getId()));
        assertFalse(abiertos.contains(trabajador.getId()));
        List<DetalleMovimientoAcceso> movimientos = historial(DIA, DIA.plusMinutes(1));
        assertEquals(2, movimientos.size());
        for (DetalleMovimientoAcceso movimiento : movimientos) {
            assertEquals(TipoMovimientoAcceso.CIERRE_SIN_SALIDA_REGISTRADA, movimiento.getTipo());
            assertEquals(DIA, movimiento.getFechaMovimiento());
            assertNull(movimiento.getAcceso().getVisita().getFechaSalida());
            assertEquals("Cerrada por Sistema - Salida no registrada", movimiento.getAcceso().getEstado().getNombreEstado());
        }
    }

    @Test
    @DisplayName("Consultar reportes no modifica los movimientos ni genera escrituras de auditoría")
    void lasConsultasNoAlteranLosRegistros() {
        Visita cerrada = preparar(TipoIngreso.INVITADO_PREREGISTRADO, "ana@sol.sica.local", "Fuera", DIA, DIA.plusHours(1), null);
        Visita abierta = preparar(TipoIngreso.TRABAJADOR_CON_CARNET, "marta@luna.sica.local", "Dentro", DIA, null, null);
        List<Visita> visitas = List.of(cerrada, abierta);
        List<EstadoGuardado> antes = estados(visitas);
        Set<Long> auditoriasAntes = idsAuditoriasSupervisor();
        acceso.fallarProximasAuditorias(1);
        supervisor.reportes.consultarVisitasDePersona(supervisor.id(), persona(cerrada).getDocumentoIdentidad());
        supervisor.reportes.consultarIngresosAbiertos(supervisor.id());
        historial(DIA, DIA.plusDays(1));
        assertTrue(acceso.conexionesDeAuditoria().isEmpty());
        assertEquals(auditoriasAntes, idsAuditoriasSupervisor());
        assertEquals(antes, estados(visitas));
    }

    // Prepara estados que producen las operaciones de ingreso, salida y autorización ya comprobadas.
    private Visita preparar(TipoIngreso tipo, String correo, String estado,
            LocalDateTime entrada, LocalDateTime salida, LocalDateTime cierre) {
        return preparar(tipo, correo, null, estado, entrada, salida, cierre);
    }

    private Visita preparar(TipoIngreso tipo, String correo, Persona reutilizada, String estado,
            LocalDateTime entrada, LocalDateTime salida, LocalDateTime cierre) {
        return gestor.ejecutar(r -> {
            Usuario anfitrion = r.crearUsuarioRepository().buscarPorEmail(correo).orElseThrow();
            boolean trabajador = tipo == TipoIngreso.TRABAJADOR_CON_CARNET || tipo == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO;
            Persona persona = reutilizada == null ? nuevaPersona(r, trabajador, anfitrion.getEmpresaId()) : reutilizada;
            boolean habitual = tipo == TipoIngreso.TRABAJADOR_CON_CARNET;
            boolean aprobada = !habitual && !estado.startsWith("Pendiente") && !estado.equals("Rechazado");
            LocalDateTime referencia = entrada == null ? DIA : entrada;
            return r.crearVisitaRepository().guardar(new Visita(persona.getId(), anfitrion.getEmpresaId(),
                    habitual ? null : anfitrion.getId(), tipo,
                    tipo == TipoIngreso.INVITADO_PREREGISTRADO ? referencia : null,
                    aprobada ? referencia.minusMinutes(10) : null, entrada, salida, cierre,
                    r.crearEstadoVisitaRepository().buscarPorNombre(estado).orElseThrow().getId(),
                    "ABC123", aprobada ? anfitrion.getId() : null));
        });
    }

    private Persona nuevaPersona(FabricarRepositorios r, boolean trabajador, Integer empresa) {
        return r.crearPersonaRepository().guardar(new Persona(trabajador ? "Pedro prueba" : "Laura prueba",
                "RP" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), trabajador ? empresa : null,
                trabajador ? TipoPersona.TRABAJADOR : TipoPersona.INVITADO,
                r.crearEstadoAccesoPersonaRepository().buscarPorNombre("Activo").orElseThrow().getId(),
                "foto-reporte.png", trabajador ? Boolean.TRUE : null));
    }

    private Aplicacion ingresar(String correo) {
        Aplicacion app = new Aplicacion(correo);
        aplicaciones.add(app);
        return app;
    }

    private Persona persona(Visita visita) {
        return gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorId(visita.getPersonaId()).orElseThrow());
    }

    private Clock reloj(LocalDateTime hora) { return Clock.fixed(hora.atZone(ZONA).toInstant(), ZONA); }

    private List<DetalleMovimientoAcceso> historial(LocalDateTime desde, LocalDateTime hasta) {
        return supervisor.reportes.consultarHistorial(supervisor.id(), desde, hasta);
    }

    private List<Integer> idsAccesos(List<DetalleAccesoSeguridad> datos) {
        return datos.stream().map(d -> d.getVisita().getId()).toList();
    }

    private List<Integer> idsMovimientos(List<DetalleMovimientoAcceso> datos) {
        return datos.stream().map(d -> d.getAcceso().getVisita().getId()).toList();
    }

    private Set<Long> idsAuditoriasSupervisor() {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(supervisor.id()))
                .stream().map(BitacoraAuditoria::getId).collect(Collectors.toSet());
    }

    private List<EstadoGuardado> estados(List<Visita> visitas) {
        return gestor.ejecutar(r -> visitas.stream().map(v -> {
            Visita actual = r.crearVisitaRepository().buscarPorId(v.getId()).orElseThrow();
            return new EstadoGuardado(actual.getEstadoVisitaId(), actual.getFechaEntrada(), actual.getFechaSalida(),
                    actual.getFechaCierreSistema(), actual.getPersonaId(), actual.getEmpresaDestinoId());
        }).toList());
    }
}
