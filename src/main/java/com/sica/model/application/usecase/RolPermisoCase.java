package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Permiso;
import com.sica.model.domain.entity.Rol;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

// Administra las acciones permitidas a cada rol, conservando el rol de las cuentas existentes.
public class RolPermisoCase {

    private final EjecutorOperaciones ejecutor;
    private final Clock reloj;

    public RolPermisoCase(EjecutorOperaciones ejecutor) {
        this(ejecutor, Clock.system(ZoneId.of("America/Bogota")));
    }

    public RolPermisoCase(EjecutorOperaciones ejecutor, Clock reloj) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
        this.reloj = Objects.requireNonNull(reloj);
    }

    public List<Rol> listarRoles(Integer responsableId) {
        return ejecutor.ejecutar(responsableId, "consultar_roles_permisos", (repositorios, responsable) ->
                repositorios.crearRolRepository().listar());
    }

    // El catálogo contiene las acciones que ya existen en SICA.
    public List<Permiso> listarPermisos(Integer responsableId) {
        return ejecutor.ejecutar(responsableId, "consultar_roles_permisos", (repositorios, responsable) ->
                repositorios.crearPermisoRepository().listar());
    }

    public CatalogoRoles consultarCatalogo(Integer responsableId) {
        return ejecutor.ejecutar(responsableId, "consultar_roles_permisos",
                (repositorios, responsable) -> new CatalogoRoles(
                        repositorios.crearRolRepository().listar(),
                        repositorios.crearPermisoRepository().listar()));
    }

    public record CatalogoRoles(List<Rol> roles, List<Permiso> permisos) { }

    public List<Permiso> consultarPermisosDeRol(Integer responsableId, Integer rolId) {
        if (rolId == null) {
            throw new IllegalArgumentException("El rol a consultar es obligatorio.");
        }
        return ejecutor.ejecutar(responsableId, "consultar_roles_permisos", (repositorios, responsable) -> {
            buscarRol(repositorios, rolId);
            return repositorios.crearPermisoRepository().listarPorRol(rolId);
        });
    }

    // Devuelve true cuando se agregó la asignación; false si el rol ya tenía ese permiso.
    public boolean asignarPermiso(Integer responsableId, Integer rolId, Integer permisoId) {
        return cambiarAsignacion(responsableId, rolId, permisoId, true);
    }

    // Devuelve true cuando se retiró la asignación; false si no estaba asignada.
    public boolean retirarPermiso(Integer responsableId, Integer rolId, Integer permisoId) {
        return cambiarAsignacion(responsableId, rolId, permisoId, false);
    }

    // Ambas acciones comparten las comprobaciones y el guardado con su auditoría.
    private boolean cambiarAsignacion(Integer responsableId, Integer rolId, Integer permisoId, boolean asignar) {
        if (rolId == null || permisoId == null) {
            throw new IllegalArgumentException("El rol y el permiso son obligatorios.");
        }
        String permisoRequerido = asignar ? "asignar_permiso_rol" : "retirar_permiso_rol";
        String accion = asignar ? "ASIGNACION_PERMISO_ROL" : "RETIRO_PERMISO_ROL";
        String accionFallida = asignar ? "ASIGNACION_PERMISO_ROL_FALLIDA" : "RETIRO_PERMISO_ROL_FALLIDO";
        // La referencia es el rol; el detalle conserva los dos identificadores de la asignación.
        IntentoGuardado intento = new IntentoGuardado(accionFallida, "rol_permisos", rolId,
                "rol_id: " + rolId + "; permiso_id: " + permisoId);

        return ejecutor.ejecutarGuardado(responsableId, permisoRequerido, intento, (repositorios, responsable) -> {
            Rol rol = buscarRol(repositorios, rolId);
            Permiso permiso = repositorios.crearPermisoRepository().buscarPorId(permisoId)
                    .orElseThrow(() -> new IllegalArgumentException("El permiso elegido no existe."));

            intento.iniciar();
            boolean cambio = asignar
                    ? repositorios.crearPermisoRepository().asignarARol(rolId, permisoId)
                    : repositorios.crearPermisoRepository().retirarDeRol(rolId, permisoId);
            if (!cambio) {
                return false;
            }

            String detalles = "Rol: " + rol.getNombreRol() + "; rol_id: " + rolId
                    + "; permiso: " + permiso.getNombrePermiso() + "; permiso_id: " + permisoId
                    + "; estado anterior: " + (asignar ? "sin asignar" : "asignado")
                    + "; estado actual: " + (asignar ? "asignado" : "sin asignar");
            repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                    responsable.getId(), LocalDateTime.now(reloj), accion, "rol_permisos", rolId, detalles));
            return true;
        });
    }

    private Rol buscarRol(FabricarRepositorios repositorios, Integer rolId) {
        return repositorios.crearRolRepository().buscarPorId(rolId)
                .orElseThrow(() -> new IllegalArgumentException("El rol elegido no existe."));
    }
}
