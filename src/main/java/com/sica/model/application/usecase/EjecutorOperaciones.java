package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.application.port.GestorTransacciones;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.policy.PoliticaActividadEmpresa;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

// Comprueba quién realiza una acción y su permiso antes de ejecutar el trabajo.
public class EjecutorOperaciones {

    private final GestorTransacciones gestorTransacciones;
    private final ContextoSesion contextoSesion;
    private final PoliticaActividadEmpresa politicaEmpresa = new PoliticaActividadEmpresa();

    // Permite comprobar casos de uso por identificador; la aplicación compartirá su contexto de sesión.
    public EjecutorOperaciones(GestorTransacciones gestorTransacciones) {
        this.gestorTransacciones = Objects.requireNonNull(
                gestorTransacciones,
                "El gestor de transacciones es obligatorio.");
        this.contextoSesion = null;
    }

    public EjecutorOperaciones(GestorTransacciones gestorTransacciones, ContextoSesion contextoSesion) {
        this.gestorTransacciones = Objects.requireNonNull(gestorTransacciones, "El gestor de transacciones es obligatorio.");
        this.contextoSesion = Objects.requireNonNull(contextoSesion, "El contexto de sesión es obligatorio.");
    }

    // Resultado representa lo que devolverá el trabajo: una empresa, una lista u otro dato.
    // BiFunction recibe dos cosas (la fábrica y el usuario) y devuelve ese resultado.
    public <Resultado> Resultado ejecutar(
            Integer usuarioId,
            String permisoRequerido,
            BiFunction<FabricarRepositorios, Usuario, Resultado> trabajo) {

        if (permisoRequerido == null || permisoRequerido.isBlank()) {
            throw new IllegalArgumentException("El permiso requerido es obligatorio.");
        }
        return ejecutarConPermisos(usuarioId, List.of(permisoRequerido), trabajo);
    }

    // Agrupa lecturas relacionadas sin abrir otra conexión para cada permiso.
    <Resultado> Resultado ejecutarConPermisos(Integer usuarioId, List<String> permisos,
            BiFunction<FabricarRepositorios, Usuario, Resultado> trabajo) {
        if (usuarioId == null) {
            throw new IllegalArgumentException("El usuario que realiza la operación es obligatorio.");
        }
        if (permisos.isEmpty() || permisos.stream().anyMatch(permiso -> permiso == null || permiso.isBlank())) {
            throw new IllegalArgumentException("El permiso requerido es obligatorio.");
        }
        Objects.requireNonNull(trabajo, "El trabajo a realizar es obligatorio.");
        return conSesion(usuarioId, () -> ejecutarTrabajo(usuarioId, permisos, trabajo));
    }

    private <Resultado> Resultado ejecutarTrabajo(Integer usuarioId, List<String> permisos,
            BiFunction<FabricarRepositorios, Usuario, Resultado> trabajo) {
        // El gestor recibe la operación completa para confirmar o deshacer sus cambios.
        return gestorTransacciones.ejecutar(repositorios -> {
            Usuario usuario = repositorios.crearUsuarioRepository()
                    .buscarPorId(usuarioId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "El usuario no existe."));

            if (!usuario.isEstaActivo()) {
                throw new IllegalStateException(
                        "La cuenta del usuario está inactiva.");
            }

            // Se revisa en cada operación, aunque el usuario ya hubiera iniciado sesión.
            // Una cuenta asociada a una empresa retirada no puede seguir operando.
            if (usuario.getEmpresaId() != null) {
                Empresa empresa = repositorios.crearEmpresaRepository()
                        .buscarPorIdParaActualizar(usuario.getEmpresaId())
                        .orElseThrow(() -> new IllegalStateException(
                                "La empresa asociada al usuario no existe."));
                politicaEmpresa.validarOperativa(empresa);
            }

            // El permiso se consulta en la base de datos a través del repositorio.
            var permisosDelRol = repositorios.crearPermisoRepository();
            for (String permiso : permisos) {
                if (!permisosDelRol.tienePermiso(usuario.getRolId(), permiso)) {
                    throw new SecurityException("El usuario no tiene permiso para esta operación.");
                }
            }

            // apply ejecuta el trabajo con los repositorios y el usuario comprobado.
            // Si ocurre un error, se propaga al gestor para que pueda deshacer los cambios.
            return trabajo.apply(repositorios, usuario);
        });
    }

    // Los avisos también comprueban la sesión justo antes de entregar un mensaje.
    <Resultado> Resultado conSesion(Integer usuarioId, Supplier<Resultado> trabajo) {
        return contextoSesion == null ? trabajo.get() : contextoSesion.ejecutarComo(usuarioId, trabajo);
    }

    AutoCloseable vincularSeguimiento(Integer usuarioId, AutoCloseable seguimiento) {
        return contextoSesion == null ? seguimiento : contextoSesion.registrarSeguimiento(usuarioId, seguimiento);
    }

    // Ejecuta un guardado y, si falla después de empezar, intenta dejar constancia aparte.
    public <Resultado> Resultado ejecutarGuardado(
            Integer usuarioId,
            String permisoRequerido,
            IntentoGuardado intento,
            BiFunction<FabricarRepositorios, Usuario, Resultado> trabajo) {

        Objects.requireNonNull(intento, "El intento de guardado es obligatorio.");

        try {
            return ejecutar(usuarioId, permisoRequerido, trabajo);
        } catch (RuntimeException error) {
            // Al llegar aquí, el gestor debe haber deshecho los cambios de la operación.
            // Si el guardado no empezó, era una validación o un rechazo y no lo auditamos aquí.
            if (intento.estaIniciado()) {
                try {
                    guardarFallo(usuarioId, intento, error);
                } catch (RuntimeException errorAuditoria) {
                    // Conservamos el segundo error sin ocultar el que causó el fallo original.
                    if (errorAuditoria != error) {
                        error.addSuppressed(errorAuditoria);
                    }
                }
            }

            // Aunque se haya guardado su auditoría, la operación sigue siendo fallida.
            throw error;
        }
    }

    private void guardarFallo(
            Integer usuarioId,
            IntentoGuardado intento,
            RuntimeException error) {

        String motivo = error.getMessage();
        if (motivo == null || motivo.isBlank()) {
            motivo = error.getClass().getSimpleName();
        }

        String detalles = "La operación de guardado falló. Motivo: " + motivo;
        if (intento.getDetallesIntento() != null && !intento.getDetallesIntento().isBlank()) {
            detalles += "; intento: " + intento.getDetallesIntento();
        }

        BitacoraAuditoria auditoria = new BitacoraAuditoria(
                usuarioId,
                LocalDateTime.now(),
                intento.getAccionFallida(),
                intento.getTablaAfectada(),
                intento.getRegistroIdAfectado(),
                detalles);

        // Esta llamada inicia otra transacción, después de que terminó la que falló.
        // Usamos directamente el gestor para no volver a auditar un fallo de esta auditoría.
        gestorTransacciones.ejecutar(repositorios -> {
            repositorios.crearBitacoraAuditoriaRepository().guardar(auditoria);
            return null;
        });
    }
}
