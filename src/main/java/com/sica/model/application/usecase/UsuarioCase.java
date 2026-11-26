package com.sica.model.application.usecase;

import com.sica.model.domain.policy.ValidadorCorreo;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.application.port.ServicioContrasenas;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Rol;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.policy.PoliticaActividadEmpresa;
import com.sica.model.domain.repository.UsuarioRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// Gestiona las cuentas que utilizan el login de SICA, no los invitados ni los trabajadores.
public class UsuarioCase {

    private final EjecutorOperaciones ejecutor;
    private final ServicioContrasenas servicioContrasenas;
    private final PoliticaActividadEmpresa politicaEmpresa = new PoliticaActividadEmpresa();

    public UsuarioCase(
            EjecutorOperaciones ejecutor,
            ServicioContrasenas servicioContrasenas) {

        this.ejecutor = Objects.requireNonNull(
                ejecutor, "El ejecutor de operaciones es obligatorio.");
        this.servicioContrasenas = Objects.requireNonNull(
                servicioContrasenas, "El servicio de contraseñas es obligatorio.");
    }

    // responsableId identifica a quien crea la cuenta; los demás datos son de la cuenta nueva.
    public Usuario registrar(
            Integer responsableId,
            String nombre,
            String email,
            char[] contrasena,
            Integer rolId,
            Integer empresaId) {
        return registrar(responsableId, nombre, email, contrasena, rolId, empresaId, null);
    }

    public Usuario registrar(Integer responsableId, String nombre, String email, char[] contrasena,
            Integer rolId, Integer empresaId, byte[] fotoPerfil) {
        IntentoGuardado intento = new IntentoGuardado(
                "CREACION_USUARIO_FALLIDA", "usuarios", null);

        return ejecutor.ejecutarGuardado(responsableId, "crear_usuario", intento, (repositorios, responsable) -> {
            validarDatosPersonales(nombre, email);
            Rol rol = validarRolYEmpresa(repositorios, rolId, empresaId);

            UsuarioRepository repositorio = repositorios.crearUsuarioRepository();
            if (repositorio.buscarPorEmail(email).isPresent()) {
                throw new IllegalArgumentException("Ya existe una cuenta con ese correo.");
            }

            // El servicio devuelve el hash; el repositorio guardará ese resultado con la cuenta.
            String hash = servicioContrasenas.generarHash(contrasena);
            Usuario nuevo = new Usuario(null, nombre, email, hash, rolId, empresaId, true, fotoPerfil);

            // El constructor de Usuario deja activa la cuenta nueva.
            intento.iniciar();
            Usuario registrado = repositorio.guardar(nuevo);

            guardarAuditoria(
                    repositorios, responsable, registrado.getId(), "CREACION_USUARIO",
                    "Se creó la cuenta: " + registrado.getNombre()
                    + "; correo: " + registrado.getEmail()
                    + "; rol: " + rol.getNombreRol()
                    + "; empresa: " + Objects.toString(registrado.getEmpresaId(), "sin empresa")
                    + "; estado: activo" + (fotoPerfil != null ? "; foto de perfil cargada" : ""));

            return registrado;
        });
    }

    // Devuelve solo las cuentas activas para que el superusuario elija cuál administrar.
    public List<Usuario> listar(Integer responsableId) {
        // El ejecutor comprueba la cuenta y el permiso antes de consultar los datos.
        return ejecutor.ejecutar(responsableId, "consultar_usuarios", (repositorios, responsable) ->
                repositorios.crearUsuarioRepository().listarActivos());
    }

    // Cuentas usa estas tres listas juntas para mostrar roles y empresas por nombre.
    public DatosCuentas consultarDirectorio(Integer responsableId) {
        return ejecutor.ejecutarConPermisos(responsableId,
                List.of("consultar_usuarios", "consultar_roles_permisos", "consultar_empresas"),
                (repositorios, responsable) -> new DatosCuentas(
                        repositorios.crearUsuarioRepository().listarActivos(),
                        repositorios.crearRolRepository().listar(),
                        repositorios.crearEmpresaRepository().listar()));
    }

    public record DatosCuentas(List<Usuario> usuarios, List<Rol> roles, List<Empresa> empresas) { }

    // Actualiza los datos de identificación sin volver a activar una cuenta desactivada.
    public Usuario actualizar(
            Integer responsableId,
            Integer usuarioId,
            String nombre,
            String email) {
        return actualizar(responsableId, usuarioId, nombre, email, null);
    }

    public Usuario actualizar(Integer responsableId, Integer usuarioId, String nombre, String email, byte[] fotoPerfil) {
        if (usuarioId == null) {
            throw new IllegalArgumentException("El identificador del usuario es obligatorio.");
        }

        IntentoGuardado intento = new IntentoGuardado(
                "ACTUALIZACION_USUARIO_FALLIDA", "usuarios", usuarioId);

        return ejecutor.ejecutarGuardado(responsableId, "actualizar_usuario", intento, (repositorios, responsable) -> {
            validarDatosPersonales(nombre, email);
            UsuarioRepository repositorio = repositorios.crearUsuarioRepository();
            Usuario anterior = repositorio.buscarPorIdParaActualizar(usuarioId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "La cuenta que deseas actualizar no existe."));

            // El mismo usuario puede conservar su correo; otra cuenta no puede utilizarlo.
            Optional<Usuario> correoExistente = repositorio.buscarPorEmail(email);
            if (correoExistente.isPresent()
                    && !Objects.equals(correoExistente.get().getId(), usuarioId)) {
                throw new IllegalArgumentException("Ya existe otra cuenta con ese correo.");
            }

            // Actualizar los datos conserva el rol que se eligió al registrar la cuenta.
            Usuario actualizado = new Usuario(
                    anterior.getId(), nombre, email, anterior.getPasswordHash(),
                    anterior.getRolId(), anterior.getEmpresaId(), anterior.isEstaActivo(), fotoPerfil);

            // Conservamos el antes y el después para explicar el cambio en la auditoría.
            String detalles = "Nombre anterior: " + anterior.getNombre()
                    + "; nombre actualizado: " + nombre
                    + "; correo anterior: " + anterior.getEmail()
                    + "; correo actualizado: " + email
                    + (fotoPerfil != null ? "; foto de perfil actualizada" : "");

            intento.iniciar();
            if (!repositorio.actualizar(actualizado)) {
                throw new IllegalStateException("No fue posible actualizar la cuenta.");
            }

            guardarAuditoria(
                    repositorios, responsable, usuarioId, "ACTUALIZACION_USUARIO", detalles);

            return actualizado;
        });
    }

    // Retira una cuenta del uso de SICA y conserva sus datos, su historial y el motivo del retiro.
    public Usuario desactivar(Integer responsableId, Integer usuarioId, String motivo) {
        if (usuarioId == null) {
            throw new IllegalArgumentException("El identificador del usuario es obligatorio.");
        }
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo de la desactivación es obligatorio.");
        }

        IntentoGuardado intento = new IntentoGuardado(
                "DESACTIVACION_USUARIO_FALLIDA", "usuarios", usuarioId);

        return ejecutor.ejecutarGuardado(responsableId, "desactivar_usuario", intento, (repositorios, responsable) -> {
            if (Objects.equals(responsable.getId(), usuarioId)) {
                throw new IllegalStateException("No puedes desactivar tu propia cuenta.");
            }

            UsuarioRepository repositorio = repositorios.crearUsuarioRepository();
            Usuario cuenta = buscarParaDesactivar(repositorios, usuarioId);
            Rol rol = repositorios.crearRolRepository().buscarPorId(cuenta.getRolId())
                    .orElseThrow(() -> new IllegalStateException("El rol de la cuenta no existe."));

            // Las cuentas de superusuario permanecen habilitadas para administrar el sistema.
            if ("Superusuario".equals(rol.getNombreRol())) {
                throw new IllegalStateException("Las cuentas de superusuario no se pueden desactivar.");
            }
            if (!cuenta.isEstaActivo()) {
                throw new IllegalStateException("La cuenta ya está desactivada.");
            }

            intento.iniciar();
            if (!repositorio.desactivar(usuarioId)) {
                throw new IllegalStateException("No fue posible desactivar la cuenta.");
            }
            cuenta.setEstaActivo(false);

            guardarAuditoria(
                    repositorios, responsable, usuarioId, "DESACTIVACION_USUARIO",
                    "Se desactivó la cuenta: " + cuenta.getNombre()
                    + "; correo: " + cuenta.getEmail()
                    + "; rol: " + rol.getNombreRol()
                    + "; estado anterior: activo; estado actual: inactivo; motivo: " + motivo);

            return cuenta;
        });
    }

    private Rol validarRolYEmpresa(
            FabricarRepositorios repositorios, Integer rolId, Integer empresaId) {

        if (rolId == null) {
            throw new IllegalArgumentException("El rol del usuario es obligatorio.");
        }
        Rol rol = repositorios.crearRolRepository().buscarPorId(rolId)
                .orElseThrow(() -> new IllegalArgumentException("El rol elegido no existe."));

        // Los funcionarios pertenecen a una empresa activa; los demás roles atienden al conjunto.
        if ("Funcionario de Empresa".equals(rol.getNombreRol())) {
            if (empresaId == null) {
                throw new IllegalArgumentException("El funcionario debe tener una empresa asignada.");
            }
            Empresa empresa = repositorios.crearEmpresaRepository().buscarPorIdParaActualizar(empresaId)
                    .orElseThrow(() -> new IllegalArgumentException("La empresa elegida no existe."));
            politicaEmpresa.validarOperativa(empresa);
        } else if (empresaId != null) {
            throw new IllegalArgumentException("Solo las cuentas de funcionarios tienen una empresa asignada.");
        }
        return rol;
    }

    private Usuario buscarParaDesactivar(FabricarRepositorios repositorios, Integer usuarioId) {
        UsuarioRepository repositorio = repositorios.crearUsuarioRepository();
        Usuario cuenta = repositorio.buscarPorId(usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("La cuenta que deseas desactivar no existe."));

        // Primero protegemos la empresa, igual que al retirarla, para coordinar ambos procesos.
        if (cuenta.getEmpresaId() != null) {
            repositorios.crearEmpresaRepository().buscarPorIdParaActualizar(cuenta.getEmpresaId())
                    .orElseThrow(() -> new IllegalStateException("La empresa de la cuenta no existe."));
        }
        return repositorio.buscarPorIdParaActualizar(usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("La cuenta que deseas desactivar no existe."));
    }

    private void validarDatosPersonales(String nombre, String email) {
        if (nombre == null || nombre.isBlank() || nombre.length() > 100) {
            throw new IllegalArgumentException("El nombre es obligatorio y admite hasta 100 caracteres.");
        }
        ValidadorCorreo.validar(email);
    }

    private void guardarAuditoria(
            FabricarRepositorios repositorios,
            Usuario responsable,
            Integer usuarioId,
            String accion,
            String detalles) {

        // La contraseña y su hash nunca se incluyen en la descripción de la auditoría.
        BitacoraAuditoria auditoria = new BitacoraAuditoria(
                responsable.getId(), LocalDateTime.now(), accion, "usuarios", usuarioId, detalles);
        repositorios.crearBitacoraAuditoriaRepository().guardar(auditoria);
    }
}
