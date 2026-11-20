package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Rol;
import com.sica.model.domain.entity.TipoPersona;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.repository.EmpresaRepository;
import com.sica.model.domain.repository.PersonaRepository;
import com.sica.model.domain.repository.UsuarioRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

// Reúne las operaciones de empresas y las reglas que necesita cada una.
public class EmpresaUseCase {

    private final EjecutorOperaciones ejecutor;

    public EmpresaUseCase(EjecutorOperaciones ejecutor) {
        this.ejecutor = Objects.requireNonNull(
                ejecutor,
                "El ejecutor de operaciones es obligatorio.");
    }

    // Recibe una empresa nueva y devuelve la empresa guardada con su identificador.
    public Empresa registrar(Integer usuarioId, Empresa empresa) {
        // En una creación fallida no hay un registro confirmado al que asignar un identificador.
        IntentoGuardado intento = new IntentoGuardado(
                "REGISTRO_EMPRESA_FALLIDO", "empresas", null);

        return ejecutor.ejecutarGuardado(usuarioId, "registrar_empresa", intento, (repositorios, usuario) -> {
            if (empresa == null) {
                throw new IllegalArgumentException(
                        "Los datos de la empresa son obligatorios.");
            }

            // El identificador de una empresa nueva lo asignará la base de datos.
            if (empresa.getId() != null) {
                throw new IllegalArgumentException(
                        "Para registrar una empresa nueva no debes indicar un identificador.");
            }

            if (!empresa.isEstaActiva()) {
                throw new IllegalArgumentException(
                        "Una empresa nueva debe registrarse activa.");
            }

            // Desde este punto, un error de guardado debe quedar en la auditoría de fallos.
            intento.iniciar();
            Empresa registrada = repositorios.crearEmpresaRepository()
                    .guardar(empresa);

            // La empresa y su auditoría se guardan dentro de la misma transacción.
            guardarAuditoria(
                    repositorios, usuario, "empresas", registrada.getId(),
                    "REGISTRO_EMPRESA",
                    "Se registró la empresa: " + registrada.getNombre());

            return registrada;
        });
    }

    // Consulta todas las empresas; si no hay registros, devuelve una lista vacía.
    public List<Empresa> listar(Integer usuarioId) {
        return ejecutor.ejecutar(usuarioId, "consultar_empresas", (repositorios, usuario) ->
                repositorios.crearEmpresaRepository().listar());
    }

    // Permite elegir empresas disponibles sin ocultar el historial de las retiradas en listar.
    public List<Empresa> listarActivas(Integer usuarioId) {
        return ejecutor.ejecutar(usuarioId, "consultar_empresas", (repositorios, usuario) ->
                repositorios.crearEmpresaRepository().listarActivas());
    }

    // usuarioId identifica a quien consulta; empresaId, a la empresa que desea consultar.
    public Empresa buscarPorId(Integer usuarioId, Integer empresaId) {
        if (empresaId == null) {
            throw new IllegalArgumentException(
                    "El identificador de la empresa es obligatorio.");
        }

        return ejecutor.ejecutar(usuarioId, "consultar_empresas", (repositorios, usuario) ->
                repositorios.crearEmpresaRepository()
                        .buscarPorId(empresaId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "La empresa que deseas consultar no existe.")));
    }

    // Recibe los nuevos datos y devuelve la empresa después de guardar el cambio y su auditoría.
    public Empresa actualizar(Integer usuarioId, Empresa empresa) {
        Integer empresaId = empresa == null ? null : empresa.getId();
        IntentoGuardado intento = new IntentoGuardado(
                "ACTUALIZACION_EMPRESA_FALLIDA", "empresas", empresaId);

        return ejecutor.ejecutarGuardado(usuarioId, "actualizar_empresa", intento, (repositorios, usuario) -> {
            if (empresa == null) {
                throw new IllegalArgumentException(
                        "Los datos de la empresa son obligatorios.");
            }

            if (empresa.getId() == null) {
                throw new IllegalArgumentException(
                        "El identificador de la empresa es obligatorio.");
            }

            EmpresaRepository repositorio = repositorios.crearEmpresaRepository();

            // Consultamos cómo estaba la empresa antes de pedir que se actualice.
            Empresa anterior = repositorio.buscarPorIdParaActualizar(empresa.getId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "La empresa que deseas actualizar no existe."));

            // Cambiar el nombre o contacto no vuelve a activar una empresa retirada.
            Empresa actualizada = new Empresa(
                    anterior.getId(), empresa.getNombre(), empresa.getContactoPrincipal(),
                    anterior.isEstaActiva());

            // Preparamos el texto antes de guardar para conservar los valores anteriores y nuevos.
            String detalles =
                    "Nombre anterior: " + anterior.getNombre()
                    + "; nombre actualizado: " + empresa.getNombre()
                    + "; contacto principal anterior: "
                    + Objects.toString(anterior.getContactoPrincipal(), "sin asignar")
                    + "; contacto principal actualizado: "
                    + Objects.toString(empresa.getContactoPrincipal(), "sin asignar");

            // Las validaciones y la búsqueda ya terminaron; ahora empieza el guardado.
            intento.iniciar();
            if (!repositorio.actualizar(actualizada)) {
                throw new IllegalStateException(
                        "No fue posible actualizar la empresa.");
            }

            // La actualización y su constancia forman parte de la misma transacción.
            guardarAuditoria(
                    repositorios, usuario, "empresas", empresa.getId(),
                    "ACTUALIZACION_EMPRESA", detalles);

            return actualizada;
        });
    }

    // Retira la empresa, deshabilita sus cuentas y finaliza sus vínculos laborales sin borrar datos.
    public Empresa desactivar(Integer usuarioId, Integer empresaId, String motivo) {
        if (empresaId == null) {
            throw new IllegalArgumentException(
                    "El identificador de la empresa es obligatorio.");
        }

        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException(
                    "El motivo de la desactivación es obligatorio.");
        }

        IntentoGuardado intento = new IntentoGuardado(
                "DESACTIVACION_EMPRESA_FALLIDA", "empresas", empresaId);

        return ejecutor.ejecutarGuardado(usuarioId, "desactivar_empresa", intento, (repositorios, usuario) -> {
            EmpresaRepository repositorio = repositorios.crearEmpresaRepository();
            Empresa empresa = repositorio.buscarPorIdParaActualizar(empresaId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "La empresa que deseas desactivar no existe."));

            if (!empresa.isEstaActiva()) {
                throw new IllegalStateException("La empresa ya está desactivada.");
            }

            List<Usuario> cuentasEmpresa = repositorios.crearUsuarioRepository().listarPorEmpresa(empresaId);
            // Una asignación incorrecta tampoco debe permitir retirar una cuenta de superusuario.
            for (Usuario cuenta : cuentasEmpresa) {
                if (cuenta.isEstaActivo()) {
                    Rol rol = repositorios.crearRolRepository().buscarPorId(cuenta.getRolId())
                            .orElseThrow(() -> new IllegalStateException("El rol de una cuenta no existe."));
                    if ("Superusuario".equals(rol.getNombreRol())) {
                        throw new IllegalStateException(
                                "La empresa tiene una cuenta de superusuario asociada. Revisa su asignación antes de desactivarla.");
                    }
                }
            }

            // Todo lo que se escribe desde aquí debe confirmarse o deshacerse junto.
            intento.iniciar();
            if (!repositorio.desactivar(empresaId)) {
                throw new IllegalStateException("No fue posible desactivar la empresa.");
            }
            empresa.desactivar();

            int cuentas = desactivarCuentas(repositorios, usuario, empresaId, motivo, cuentasEmpresa);
            int trabajadores = finalizarVinculaciones(repositorios, usuario, empresaId, motivo);

            guardarAuditoria(
                    repositorios, usuario, "empresas", empresaId,
                    "DESACTIVACION_EMPRESA",
                    "Se desactivó la empresa: " + empresa.getNombre()
                    + "; contacto principal: " + Objects.toString(empresa.getContactoPrincipal(), "sin asignar")
                    + "; estado anterior: activa; estado actual: desactivada"
                    + "; cuentas desactivadas: " + cuentas
                    + "; vinculaciones finalizadas: " + trabajadores
                    + "; motivo: " + motivo);

            // Las visitas e incidentes permanecen intactos. Las nuevas entradas deben revisar la empresa.
            return empresa;
        });
    }

    private int desactivarCuentas(
            FabricarRepositorios repositorios, Usuario responsable, Integer empresaId,
            String motivo, List<Usuario> cuentas) {

        UsuarioRepository repositorio = repositorios.crearUsuarioRepository();
        int cantidad = 0;

        for (Usuario cuenta : cuentas) {
            // Las cuentas que ya estaban inactivas se conservan en ese estado.
            if (cuenta.isEstaActivo()) {
                if (!repositorio.desactivar(cuenta.getId())) {
                    throw new IllegalStateException(
                            "No fue posible desactivar una cuenta de la empresa.");
                }

                guardarAuditoria(
                        repositorios, responsable, "usuarios", cuenta.getId(),
                        "DESACTIVACION_USUARIO_POR_CIERRE_EMPRESA",
                        "Empresa: " + empresaId
                        + "; estado anterior: activo; estado actual: inactivo; motivo: " + motivo);
                cantidad++;
            }
        }
        return cantidad;
    }

    private int finalizarVinculaciones(
            FabricarRepositorios repositorios, Usuario responsable, Integer empresaId, String motivo) {

        PersonaRepository repositorio = repositorios.crearPersonaRepository();
        int cantidad = 0;

        for (Persona referencia : repositorio.listarTrabajadoresPorEmpresa(empresaId)) {
            // Volvemos a leer el registro protegido para conservar cualquier cambio de seguridad reciente.
            Persona trabajador = repositorio.buscarPorIdParaActualizar(referencia.getId())
                    .orElseThrow(() -> new IllegalStateException("Un trabajador de la empresa ya no está disponible."));
            if (trabajador.getTipoPersona() != TipoPersona.TRABAJADOR
                    || !Objects.equals(empresaId, trabajador.getEmpresaId())) {
                throw new IllegalStateException("La vinculación de un trabajador cambió. Vuelve a revisar la empresa.");
            }
            if (Boolean.TRUE.equals(trabajador.getVinculacionLaboralActiva())) {
                // Conservamos su empresa y su identidad; no se impone una prohibición personal.
                trabajador.setVinculacionLaboralActiva(false);
                if (!repositorio.actualizar(trabajador)) {
                    throw new IllegalStateException(
                            "No fue posible finalizar una vinculación laboral de la empresa.");
                }

                guardarAuditoria(
                        repositorios, responsable, "personas", trabajador.getId(),
                        "BAJA_TRABAJADOR_POR_CIERRE_EMPRESA",
                        "Empresa: " + empresaId
                        + "; vinculación anterior: activa; vinculación actual: inactiva; motivo: " + motivo);
                cantidad++;
            }
        }
        return cantidad;
    }

    // Prepara y guarda la constancia usando los repositorios de la operación en curso.
    private void guardarAuditoria(
            FabricarRepositorios repositorios,
            Usuario usuario,
            String tablaAfectada,
            Integer registroId,
            String accion,
            String detalles) {

        BitacoraAuditoria auditoria = new BitacoraAuditoria(
                usuario.getId(),
                LocalDateTime.now(),
                accion,
                tablaAfectada,
                registroId,
                detalles);

        // Un error al guardar se propaga al gestor, igual que un error al guardar la empresa.
        repositorios.crearBitacoraAuditoriaRepository().guardar(auditoria);
    }
}
