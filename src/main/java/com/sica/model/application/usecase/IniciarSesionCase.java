package com.sica.model.application.usecase;

import com.sica.model.domain.policy.ValidadorCorreo;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.application.port.GestorTransacciones;
import com.sica.model.application.port.ServicioContrasenas;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Rol;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.policy.PoliticaActividadEmpresa;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

// Comprueba las credenciales y registra el resultado antes de permitir el uso de SICA.
public class IniciarSesionCase {

    private final GestorTransacciones gestor;
    private final ServicioContrasenas contrasenas;
    private final Clock reloj;
    private final PoliticaActividadEmpresa politicaEmpresa = new PoliticaActividadEmpresa();

    public IniciarSesionCase(GestorTransacciones gestor, ServicioContrasenas contrasenas) {
        this(gestor, contrasenas, Clock.system(ZoneId.of("America/Bogota")));
    }

    public IniciarSesionCase(GestorTransacciones gestor, ServicioContrasenas contrasenas, Clock reloj) {
        this.gestor = Objects.requireNonNull(gestor);
        this.contrasenas = Objects.requireNonNull(contrasenas);
        this.reloj = Objects.requireNonNull(reloj);
    }

    // La aplicación comparte este contexto con el ejecutor, los avisos y el cierre de sesión.
    public SesionUsuario iniciar(ContextoSesion contexto, String email, char[] contrasena) {
        return Objects.requireNonNull(contexto, "El contexto de sesión es obligatorio.")
                .iniciar(() -> iniciar(email, contrasena));
    }

    public SesionUsuario iniciar(String email, char[] contrasena) {
        ValidadorCorreo.validar(email);
        if (contrasena == null || contrasena.length == 0) {
            throw new IllegalArgumentException("La contraseña es obligatoria.");
        }

        // Trabajamos con una copia que podremos limpiar sin modificar lo que recibió la pantalla.
        char[] copia = Arrays.copyOf(contrasena, contrasena.length);
        Optional<SesionUsuario> resultado;
        try {
            // Todavía no hay una sesión: primero debemos identificar y comprobar la cuenta.
            resultado = gestor.ejecutar(repositorios -> autenticar(repositorios, email, copia));
        } catch (RuntimeException error) {
            // Un error de guardado no puede producir una sesión sin su auditoría confirmada.
            registrarFalloTecnico(email, error);
            throw error;
        } finally {
            Arrays.fill(copia, '\0');
        }

        // El mensaje se comunica después de confirmar la auditoría del intento fallido.
        return resultado.orElseThrow(() -> new SecurityException(
                "No fue posible iniciar sesión. Verifica tus datos y que tu cuenta esté habilitada."));
    }

    private Optional<SesionUsuario> autenticar(FabricarRepositorios repositorios, String email, char[] contrasena) {
        Optional<Usuario> encontrada = repositorios.crearUsuarioRepository().buscarPorEmail(email);
        if (encontrada.isEmpty()) {
            return denegar(repositorios, null, email, "No existe una cuenta para el correo presentado.");
        }
        Usuario referencia = encontrada.get();

        // Protegemos primero la empresa y después la cuenta, igual que al desactivarlas.
        Empresa empresa = referencia.getEmpresaId() == null ? null : repositorios.crearEmpresaRepository()
                .buscarPorIdParaActualizar(referencia.getEmpresaId()).orElse(null);
        Usuario usuario = repositorios.crearUsuarioRepository().buscarPorIdParaActualizar(referencia.getId()).orElse(null);
        if (usuario == null) {
            return denegar(repositorios, null, email, "La cuenta ya no está disponible.");
        }
        if (!Objects.equals(usuario.getEmail(), referencia.getEmail())
                || !Objects.equals(usuario.getEmpresaId(), referencia.getEmpresaId())) {
            return denegar(repositorios, usuario, email, "Los datos de la cuenta cambiaron durante el intento.");
        }
        if (!usuario.isEstaActivo()) {
            return denegar(repositorios, usuario, email, "La cuenta está desactivada.");
        }

        // El servicio compara la contraseña escrita con el hash de esa cuenta.
        if (!contrasenas.verificar(contrasena, usuario.getPasswordHash())) {
            return denegar(repositorios, usuario, email, "La contraseña no corresponde a la cuenta.");
        }
        Rol rol = repositorios.crearRolRepository().buscarPorId(usuario.getRolId()).orElse(null);
        if (rol == null) {
            return denegar(repositorios, usuario, email, "La cuenta no tiene un rol disponible.");
        }
        if ("Funcionario de Empresa".equals(rol.getNombreRol())) {
            if (empresa == null) {
                return denegar(repositorios, usuario, email, "El funcionario no tiene una empresa disponible.");
            }
            try {
                politicaEmpresa.validarOperativa(empresa);
            } catch (IllegalStateException error) {
                return denegar(repositorios, usuario, email, "La empresa del funcionario está desactivada.");
            }
        } else if (usuario.getEmpresaId() != null) {
            return denegar(repositorios, usuario, email, "La empresa asignada no corresponde al rol de la cuenta.");
        }

        LocalDateTime ahora = LocalDateTime.now(reloj);
        byte[] fotoPerfil = repositorios.crearUsuarioRepository().buscarFotoPerfil(usuario.getId()).orElse(null);
        SesionUsuario sesion = new SesionUsuario(usuario, rol, ahora, fotoPerfil, empresa == null ? null : empresa.getNombre());
        auditar(repositorios, usuario, email, "LOGIN_EXITOSO", "Cuenta y credenciales comprobadas.", ahora);
        return Optional.of(sesion);
    }

    private Optional<SesionUsuario> denegar(
            FabricarRepositorios repositorios, Usuario usuario, String email, String motivo) {
        auditar(repositorios, usuario, email, "LOGIN_FALLIDO", motivo, LocalDateTime.now(reloj));
        return Optional.empty();
    }

    private void auditar(FabricarRepositorios repositorios, Usuario usuario, String email,
            String accion, String motivo, LocalDateTime fecha) {
        Integer usuarioId = usuario == null ? null : usuario.getId();
        // Se conserva el correo presentado y el motivo, nunca la contraseña ni su hash.
        repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                usuarioId, fecha, accion, "usuarios", usuarioId,
                "Correo presentado: " + email + "; motivo: " + motivo));
    }

    private void registrarFalloTecnico(String email, RuntimeException original) {
        try {
            gestor.ejecutar(repositorios -> {
                auditar(repositorios, null, email, "LOGIN_FALLIDO",
                        "No se pudo completar el intento por un error al procesarlo o guardar su auditoría.",
                        LocalDateTime.now(reloj));
                return null;
            });
        } catch (RuntimeException errorAuditoria) {
            if (errorAuditoria != original) {
                original.addSuppressed(errorAuditoria);
            }
        }
    }
}
