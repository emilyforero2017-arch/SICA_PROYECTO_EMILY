package com.sica.integration;

import com.sica.infrastructure.database.GestorTransaccionesJdbc;
import com.sica.infrastructure.security.ServicioContrasenasPbkdf2;
import com.sica.model.application.usecase.CerrarSesionCase;
import com.sica.model.application.usecase.ContextoSesion;
import com.sica.model.application.usecase.EjecutorOperaciones;
import com.sica.model.application.usecase.IniciarSesionCase;
import com.sica.model.application.usecase.PersonaCase;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.TipoPersona;
import com.sica.model.domain.entity.Usuario;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

// Ejecutar con: mvn -Ppruebas-jdbc verify
@EnabledIfSystemProperty(named = "sica.pruebas.jdbc", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Recorrido: Ana inicia sesión y registra a Pedro")
class RegistrarTrabajadorIT {

    private static final String CONTRASENA_PRUEBA = "ClavePruebaSica2026!";
    private static final String FOTO_PEDRO = "foto-prueba-pedro.png";

    private BaseDatosTemporal base;
    private String hashPrueba;
    private final ServicioContrasenasPbkdf2 contrasenas = new ServicioContrasenasPbkdf2();
    private ConexionConFalloAuditoria acceso;
    private GestorTransaccionesJdbc gestor;
    private ContextoSesion contexto;
    private PersonaCase casos;
    private DatosPrueba datos;

    private record DatosPrueba(Usuario ana, Usuario guarda, Integer empresaSol,
                              Integer empresaLuna, Persona trabajadorLuna) { }

    @BeforeAll
    void prepararBaseTemporal() throws Exception {
        base = BaseDatosTemporal.crear();
        char[] clave = CONTRASENA_PRUEBA.toCharArray();
        try {
            hashPrueba = contrasenas.generarHash(clave);
        } finally {
            Arrays.fill(clave, '\0');
        }
    }

    @BeforeEach
    void prepararCuentasYEmpresas() {
        acceso = new ConexionConFalloAuditoria(base.configuracion());
        gestor = new GestorTransaccionesJdbc(acceso);
        contexto = new ContextoSesion();
        casos = new PersonaCase(new EjecutorOperaciones(gestor, contexto));
        String marca = UUID.randomUUID().toString().replace("-", "");

        // Cada prueba recibe sus propias cuentas y empresas para no depender de otra prueba.
        datos = gestor.ejecutar(repositorios -> {
            Empresa sol = repositorios.crearEmpresaRepository().guardar(
                    new Empresa("Empresa prueba Sol " + marca, "Contacto de prueba"));
            Empresa luna = repositorios.crearEmpresaRepository().guardar(
                    new Empresa("Empresa prueba Luna " + marca, "Contacto de prueba"));
            int rolFuncionario = repositorios.crearRolRepository()
                    .buscarPorNombre("Funcionario de Empresa").orElseThrow().getId();
            int rolGuarda = repositorios.crearRolRepository()
                    .buscarPorNombre("Guarda de Seguridad").orElseThrow().getId();
            int activo = repositorios.crearEstadoAccesoPersonaRepository()
                    .buscarPorNombre("Activo").orElseThrow().getId();
            Usuario ana = repositorios.crearUsuarioRepository().guardar(new Usuario(
                    "Ana prueba", "ana-" + marca + "@prueba.invalid",
                    hashPrueba, rolFuncionario, sol.getId()));
            Usuario guarda = repositorios.crearUsuarioRepository().guardar(new Usuario(
                    "Guarda prueba", "guarda-" + marca + "@prueba.invalid",
                    hashPrueba, rolGuarda, null));
            Persona trabajadorLuna = repositorios.crearPersonaRepository().guardar(new Persona(
                    "Trabajador de Luna", documentoNuevo(), luna.getId(),
                    TipoPersona.TRABAJADOR, activo, "foto-prueba-luna.png", true));
            return new DatosPrueba(ana, guarda, sol.getId(), luna.getId(), trabajadorLuna);
        });
    }

    @AfterEach
    void cerrarSesion() {
        if (contexto != null) new CerrarSesionCase(contexto).cerrar();
    }

    @AfterAll
    void eliminarBaseTemporal() throws SQLException {
        if (base != null) base.close();
    }

    @Test
    @DisplayName("Guarda al trabajador y su auditoría, y permite consultarlo desde otra conexión")
    void registraConEmpresaAuditoriaYConsulta() {
        iniciarSesion(datos.ana());
        String documento = documentoNuevo();
        Persona resultado = registrarConAna(documento, FOTO_PEDRO);

        // Esta consulta abre otra conexión: comprueba datos que ya fueron confirmados.
        gestor.ejecutar(repositorios -> {
            Persona guardado = repositorios.crearPersonaRepository()
                    .buscarPorDocumento(documento).orElseThrow();
            int activo = repositorios.crearEstadoAccesoPersonaRepository()
                    .buscarPorNombre("Activo").orElseThrow().getId();
            assertAll(
                    () -> assertEquals(resultado.getId(), guardado.getId()),
                    () -> assertEquals("Pedro prueba", guardado.getNombre()),
                    () -> assertEquals(datos.empresaSol(), guardado.getEmpresaId()),
                    () -> assertEquals(TipoPersona.TRABAJADOR, guardado.getTipoPersona()),
                    () -> assertEquals(activo, guardado.getEstadoAccesoId()),
                    () -> assertEquals(FOTO_PEDRO, guardado.getUrlFoto()),
                    () -> assertEquals(Boolean.TRUE, guardado.getVinculacionLaboralActiva()));

            List<BitacoraAuditoria> registros = repositorios.crearBitacoraAuditoriaRepository()
                    .listarPorRegistro("personas", guardado.getId());
            assertEquals(1, registros.size());
            BitacoraAuditoria registro = registros.get(0);
            assertEquals("REGISTRO_TRABAJADOR", registro.getAccionRealizada());
            assertEquals(datos.ana().getId(), registro.getUsuarioId());
            assertTrue(registro.getDetalles().contains(documento));
            assertFalse(registro.getDetalles().contains(CONTRASENA_PRUEBA));
            assertFalse(registro.getDetalles().contains(hashPrueba));
            return null;
        });

        List<Persona> listado = casos.listarTrabajadores(datos.ana().getId());
        assertEquals(List.of(resultado.getId()), listado.stream().map(Persona::getId).toList());
        assertFalse(listado.stream().anyMatch(p -> p.getId().equals(datos.trabajadorLuna().getId())));
    }

    @Test
    @DisplayName("Exige iniciar sesión antes de registrar un trabajador")
    void exigeSesion() {
        String documento = documentoNuevo();
        assertThrows(SecurityException.class, () -> registrarConAna(documento, FOTO_PEDRO));
        comprobarSinRegistro(documento);
        assertTrue(auditoriaDeRegistro(datos.ana().getId()).isEmpty());
    }

    @Test
    @DisplayName("El guarda autenticado no tiene permiso para registrar trabajadores")
    void rechazaAlGuarda() {
        iniciarSesion(datos.guarda());
        String documento = documentoNuevo();
        assertThrows(SecurityException.class, () -> casos.registrarTrabajador(
                datos.guarda().getId(), "Pedro prueba", documento, FOTO_PEDRO));
        comprobarSinRegistro(documento);
        assertTrue(auditoriaDeRegistro(datos.guarda().getId()).isEmpty());
    }

    @Test
    @DisplayName("Una sesión no puede operar usando la identificación de otra cuenta")
    void rechazaIdentificacionAjena() {
        iniciarSesion(datos.guarda());
        String documento = documentoNuevo();
        assertThrows(SecurityException.class, () -> registrarConAna(documento, FOTO_PEDRO));
        comprobarSinRegistro(documento);
    }

    @Test
    @DisplayName("Comprueba de nuevo la cuenta aunque Ana ya haya iniciado sesión")
    void rechazaCuentaDesactivadaDuranteSesion() {
        iniciarSesion(datos.ana());
        gestor.ejecutar(r -> r.crearUsuarioRepository().desactivar(datos.ana().getId()));
        String documento = documentoNuevo();
        assertThrows(IllegalStateException.class, () -> registrarConAna(documento, FOTO_PEDRO));
        comprobarSinRegistro(documento);
        assertTrue(auditoriaDeRegistro(datos.ana().getId()).isEmpty());
    }

    @Test
    @DisplayName("Comprueba de nuevo la empresa aunque Ana ya haya iniciado sesión")
    void rechazaEmpresaDesactivadaDuranteSesion() {
        iniciarSesion(datos.ana());
        gestor.ejecutar(r -> r.crearEmpresaRepository().desactivar(datos.empresaSol()));
        String documento = documentoNuevo();
        assertThrows(IllegalStateException.class, () -> registrarConAna(documento, FOTO_PEDRO));
        comprobarSinRegistro(documento);
        assertTrue(auditoriaDeRegistro(datos.ana().getId()).isEmpty());
    }

    @Test
    @DisplayName("La falta de foto se rechaza antes de empezar a guardar")
    void rechazaFotoAusente() {
        iniciarSesion(datos.ana());
        String documento = documentoNuevo();
        assertThrows(IllegalArgumentException.class, () -> registrarConAna(documento, null));
        comprobarSinRegistro(documento);
        assertTrue(auditoriaDeRegistro(datos.ana().getId()).isEmpty());
    }

    @Test
    @DisplayName("Un documento existente conserva su empresa y su prohibición")
    void rechazaDocumentoExistenteSinAlterarlo() {
        iniciarSesion(datos.ana());
        Persona anterior = gestor.ejecutar(repositorios -> {
            Persona persona = repositorios.crearPersonaRepository()
                    .buscarPorIdParaActualizar(datos.trabajadorLuna().getId()).orElseThrow();
            int prohibido = repositorios.crearEstadoAccesoPersonaRepository()
                    .buscarPorNombre("Con Prohibición de Ingreso").orElseThrow().getId();
            persona.setEstadoAccesoId(prohibido);
            assertTrue(repositorios.crearPersonaRepository().actualizar(persona));
            return persona;
        });

        assertThrows(IllegalArgumentException.class,
                () -> registrarConAna(anterior.getDocumentoIdentidad(), FOTO_PEDRO));

        Persona conservada = gestor.ejecutar(r -> r.crearPersonaRepository()
                .buscarPorDocumento(anterior.getDocumentoIdentidad()).orElseThrow());
        assertAll(
                () -> assertEquals(anterior.getId(), conservada.getId()),
                () -> assertEquals(datos.empresaLuna(), conservada.getEmpresaId()),
                () -> assertEquals(anterior.getEstadoAccesoId(), conservada.getEstadoAccesoId()),
                () -> assertEquals(anterior.getNombre(), conservada.getNombre()),
                () -> assertEquals(anterior.getUrlFoto(), conservada.getUrlFoto()));
        assertTrue(casos.listarTrabajadores(datos.ana().getId()).isEmpty());
        assertTrue(auditoriaDeRegistro(datos.ana().getId()).isEmpty());
    }

    @Test
    @DisplayName("Si falla la auditoría, deshace a Pedro y registra el fallo en otra transacción")
    void deshaceTrabajadorYConservaAuditoriaDelFallo() {
        iniciarSesion(datos.ana());
        String documento = documentoNuevo();
        acceso.fallarProximasAuditorias(1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registrarConAna(documento, FOTO_PEDRO));

        assertInstanceOf(SQLException.class, error.getCause());
        comprobarSinRegistro(documento);
        List<BitacoraAuditoria> registros = auditoriaDeRegistro(datos.ana().getId());
        assertEquals(1, registros.size());
        assertEquals("REGISTRO_TRABAJADOR_FALLIDO", registros.get(0).getAccionRealizada());
        assertNull(registros.get(0).getRegistroIdAfectado());
        List<Integer> conexiones = acceso.conexionesDeAuditoria();
        assertEquals(2, conexiones.size());
        assertNotEquals(conexiones.get(0), conexiones.get(1));
    }

    @Test
    @DisplayName("Si tampoco puede guardar el fallo, conserva los dos errores y deshace a Pedro")
    void conservaErrorPrincipalSiTambienFallaSuAuditoria() {
        iniciarSesion(datos.ana());
        String documento = documentoNuevo();
        acceso.fallarProximasAuditorias(2);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registrarConAna(documento, FOTO_PEDRO));

        assertInstanceOf(SQLException.class, error.getCause());
        assertEquals(1, error.getSuppressed().length);
        comprobarSinRegistro(documento);
        assertTrue(auditoriaDeRegistro(datos.ana().getId()).isEmpty());
        assertEquals(2, acceso.conexionesDeAuditoria().size());
    }

    private void iniciarSesion(Usuario usuario) {
        char[] clave = CONTRASENA_PRUEBA.toCharArray();
        try {
            new IniciarSesionCase(gestor, contrasenas).iniciar(contexto, usuario.getEmail(), clave);
        } finally {
            Arrays.fill(clave, '\0');
        }
    }

    private Persona registrarConAna(String documento, String foto) {
        return casos.registrarTrabajador(datos.ana().getId(), "Pedro prueba", documento, foto);
    }

    private void comprobarSinRegistro(String documento) {
        assertTrue(gestor.ejecutar(r -> r.crearPersonaRepository().buscarPorDocumento(documento)).isEmpty());
    }

    private List<BitacoraAuditoria> auditoriaDeRegistro(Integer usuarioId) {
        return gestor.ejecutar(r -> r.crearBitacoraAuditoriaRepository().listarPorUsuario(usuarioId))
                .stream().filter(a -> a.getAccionRealizada().startsWith("REGISTRO_TRABAJADOR")).toList();
    }

    private String documentoNuevo() {
        return "PR" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

}
