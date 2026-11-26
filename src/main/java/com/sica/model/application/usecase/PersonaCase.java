package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.EstadoAccesoPersona;
import com.sica.model.domain.entity.FotoPersona;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.TipoPersona;
import com.sica.model.domain.repository.PersonaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

// Gestiona los registros de trabajadores e invitados para el acceso físico al conjunto.
public class PersonaCase {

    private final EjecutorOperaciones ejecutor;

    public PersonaCase(EjecutorOperaciones ejecutor) {
        this.ejecutor = Objects.requireNonNull(
                ejecutor, "El ejecutor de operaciones es obligatorio.");
    }

    // Consulta los trabajadores de la empresa del funcionario para administrar sus datos.
    public List<Persona> listarTrabajadores(Integer funcionarioId) {
        return ejecutor.ejecutar(funcionarioId, "consultar_trabajadores_empresa", (repositorios, funcionario) -> {
            // El ejecutor ya comprobó la cuenta, el permiso y que su empresa siga activa.
            if (funcionario.getEmpresaId() == null) {
                throw new IllegalStateException(
                        "El funcionario debe tener una empresa asignada para consultar sus trabajadores.");
            }

            // Tomamos la empresa de su cuenta para que no pueda consultar la de otra empresa.
            return repositorios.crearPersonaRepository()
                    .listarTrabajadoresPorEmpresa(funcionario.getEmpresaId());
        });
    }

    // La empresa se toma de la cuenta del funcionario; no se recibe otra empresa como parámetro.
    public Persona registrarTrabajador(
            Integer funcionarioId,
            String nombre,
            String documentoIdentidad,
            String urlFoto) {

        IntentoGuardado intento = new IntentoGuardado(
                "REGISTRO_TRABAJADOR_FALLIDO", "personas", null);

        return ejecutor.ejecutarGuardado(funcionarioId, "registrar_trabajador", intento, (repositorios, funcionario) -> {
            // El ejecutor ya revisó la cuenta, su permiso y la actividad de su empresa.
            if (funcionario.getEmpresaId() == null) {
                throw new IllegalStateException(
                        "El funcionario debe tener una empresa asignada para registrar trabajadores.");
            }

            // Consultamos el estado por su nombre, sin suponer que siempre tendrá el mismo número.
            EstadoAccesoPersona estado = repositorios.crearEstadoAccesoPersonaRepository()
                    .buscarPorNombre("Activo")
                    .orElseThrow(() -> new IllegalStateException(
                            "El estado de acceso Activo no está configurado."));

            // Persona comprueba los datos obligatorios, incluida la foto.
            Persona nuevo = new Persona(
                    nombre, documentoIdentidad, funcionario.getEmpresaId(),
                    TipoPersona.TRABAJADOR, estado.getId(), urlFoto, true);

            PersonaRepository repositorio = repositorios.crearPersonaRepository();
            if (repositorio.buscarPorDocumento(nuevo.getDocumentoIdentidad()).isPresent()) {
                // No duplicamos la identidad ni cambiamos el estado de una persona que ya existe.
                throw new IllegalArgumentException(
                        "Ya existe una persona con ese documento de identidad.");
            }

            // El trabajador y la constancia de su registro deben guardarse juntos.
            intento.iniciar();
            Persona registrado = repositorio.guardar(nuevo);

            BitacoraAuditoria auditoria = new BitacoraAuditoria(
                    funcionario.getId(),
                    LocalDateTime.now(),
                    "REGISTRO_TRABAJADOR",
                    "personas",
                    registrado.getId(),
                    "Se registró el trabajador: " + registrado.getNombre()
                    + "; documento: " + registrado.getDocumentoIdentidad()
                    + "; empresa: " + registrado.getEmpresaId()
                    + "; foto: " + FotoPersona.describir(registrado.getUrlFoto())
                    + "; estado de acceso: " + estado.getNombreEstado()
                    + "; vinculación laboral: activa");

            repositorios.crearBitacoraAuditoriaRepository().guardar(auditoria);
            return registrado;
        });
    }

    // Corrige el nombre o renueva la foto del trabajador de la propia empresa.
    public Persona actualizarTrabajador(
            Integer funcionarioId,
            Integer trabajadorId,
            String nombre,
            String urlFoto) {

        validarTrabajadorId(trabajadorId);
        IntentoGuardado intento = new IntentoGuardado(
                "ACTUALIZACION_TRABAJADOR_FALLIDA", "personas", trabajadorId);

        return ejecutor.ejecutarGuardado(funcionarioId, "actualizar_trabajador", intento, (repositorios, funcionario) -> {
            PersonaRepository repositorio = repositorios.crearPersonaRepository();
            Persona anterior = buscarTrabajadorPropio(repositorio, trabajadorId, funcionario.getEmpresaId());

            // Preparar otra copia permite conservar el antes para la auditoría.
            // El documento, la empresa, las restricciones y la vinculación se mantienen.
            Persona actualizado = new Persona(
                    anterior.getId(), nombre, anterior.getDocumentoIdentidad(), anterior.getEmpresaId(),
                    anterior.getTipoPersona(), anterior.getEstadoAccesoId(),
                    urlFoto == null ? anterior.getUrlFoto() : urlFoto,
                    anterior.getVinculacionLaboralActiva());

            String detalles = "Trabajador: " + trabajadorId
                    + "; documento: " + anterior.getDocumentoIdentidad()
                    + "; empresa: " + anterior.getEmpresaId()
                    + "; nombre anterior: " + anterior.getNombre()
                    + "; nombre actualizado: " + actualizado.getNombre()
                    + "; foto anterior: " + FotoPersona.describir(anterior.getUrlFoto())
                    + "; foto actualizada: " + FotoPersona.describir(actualizado.getUrlFoto());

            intento.iniciar();
            if (!repositorio.actualizar(actualizado)) {
                throw new IllegalStateException("No fue posible actualizar los datos del trabajador.");
            }

            repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                    funcionario.getId(), LocalDateTime.now(), "ACTUALIZACION_TRABAJADOR",
                    "personas", trabajadorId, detalles));
            return actualizado;
        });
    }

    // Finaliza la relación laboral y conserva la identidad, la empresa y el historial.
    public Persona finalizarVinculacionLaboral(
            Integer funcionarioId,
            Integer trabajadorId,
            String motivo) {

        validarTrabajadorId(trabajadorId);
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo de la finalización es obligatorio.");
        }
        IntentoGuardado intento = new IntentoGuardado(
                "BAJA_TRABAJADOR_FALLIDA", "personas", trabajadorId);

        return ejecutor.ejecutarGuardado(funcionarioId, "dar_baja_trabajador", intento, (repositorios, funcionario) -> {
            PersonaRepository repositorio = repositorios.crearPersonaRepository();
            Persona anterior = buscarTrabajadorPropio(repositorio, trabajadorId, funcionario.getEmpresaId());
            if (!Boolean.TRUE.equals(anterior.getVinculacionLaboralActiva())) {
                throw new IllegalStateException("La vinculación laboral del trabajador ya está finalizada.");
            }

            // Solo cambia la vinculación laboral; no se impone ni se retira una prohibición de ingreso.
            Persona retirado = new Persona(
                    anterior.getId(), anterior.getNombre(), anterior.getDocumentoIdentidad(),
                    anterior.getEmpresaId(), anterior.getTipoPersona(), anterior.getEstadoAccesoId(),
                    anterior.getUrlFoto(), false);

            intento.iniciar();
            if (!repositorio.actualizar(retirado)) {
                throw new IllegalStateException("No fue posible finalizar la vinculación laboral.");
            }

            repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                    funcionario.getId(), LocalDateTime.now(), "BAJA_TRABAJADOR", "personas", trabajadorId,
                    "Trabajador: " + anterior.getNombre()
                    + "; documento: " + anterior.getDocumentoIdentidad()
                    + "; empresa: " + anterior.getEmpresaId()
                    + "; vinculación anterior: activa; vinculación actual: finalizada; motivo: " + motivo));
            return retirado;
        });
    }

    // Ambos cambios deben comprobar que la persona sea trabajadora de la empresa del funcionario.
    private Persona buscarTrabajadorPropio(
            PersonaRepository repositorio, Integer trabajadorId, Integer empresaId) {

        if (empresaId == null) {
            throw new IllegalStateException("El funcionario debe tener una empresa asignada.");
        }
        Persona trabajador = repositorio.buscarPorIdParaActualizar(trabajadorId)
                .orElseThrow(() -> new IllegalArgumentException("El trabajador no existe."));
        if (trabajador.getTipoPersona() != TipoPersona.TRABAJADOR
                || !empresaId.equals(trabajador.getEmpresaId())) {
            throw new IllegalArgumentException("Solo puedes gestionar trabajadores de tu propia empresa.");
        }
        return trabajador;
    }

    private void validarTrabajadorId(Integer trabajadorId) {
        if (trabajadorId == null) {
            throw new IllegalArgumentException("El identificador del trabajador es obligatorio.");
        }
    }

    // Registra a un invitado nuevo. No crea una cuenta de login ni autoriza su entrada.
    public Persona registrarInvitado(
            Integer responsableId,
            String nombre,
            String documentoIdentidad,
            String urlFoto) {

        IntentoGuardado intento = new IntentoGuardado(
                "REGISTRO_INVITADO_FALLIDO", "personas", null);

        return ejecutor.ejecutarGuardado(responsableId, "registrar_invitado", intento, (repositorios, responsable) ->
                registrarInvitadoEnOperacion(repositorios, responsable.getId(), nombre, documentoIdentidad,
                        urlFoto, intento, LocalDateTime.now()));
    }

    // Reutiliza los repositorios de una operación que ya comprobó los permisos; no inicia otra transacción.
    static Persona registrarInvitadoEnOperacion(
            FabricarRepositorios repositorios, Integer responsableId,
            String nombre, String documentoIdentidad, String urlFoto, IntentoGuardado intento, LocalDateTime fecha) {
        EstadoAccesoPersona estado = repositorios.crearEstadoAccesoPersonaRepository()
                .buscarPorNombre("Activo")
                .orElseThrow(() -> new IllegalStateException("El estado de acceso Activo no está configurado."));

        // La empresa que recibirá al invitado pertenece a la visita, no a su identidad.
        Persona nuevo = new Persona(
                nombre, documentoIdentidad, null, TipoPersona.INVITADO, estado.getId(), urlFoto, null);
        PersonaRepository repositorio = repositorios.crearPersonaRepository();
        if (repositorio.buscarPorDocumento(nuevo.getDocumentoIdentidad()).isPresent()) {
            throw new IllegalArgumentException("Ya existe una persona con ese documento de identidad.");
        }

        intento.iniciar();
        Persona registrado = repositorio.guardar(nuevo);
        repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                responsableId, fecha, "REGISTRO_INVITADO", "personas", registrado.getId(),
                "Se registró el invitado: " + registrado.getNombre()
                + "; documento: " + registrado.getDocumentoIdentidad()
                + "; foto: " + FotoPersona.describir(registrado.getUrlFoto())
                + "; estado de acceso: " + estado.getNombreEstado()));
        return registrado;
    }
}
