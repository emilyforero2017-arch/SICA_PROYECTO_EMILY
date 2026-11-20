package com.sica.model.application.usecase;

import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.EstadoAccesoPersona;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.repository.EstadoAccesoPersonaRepository;
import com.sica.model.domain.repository.PersonaRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

// Reúne las decisiones de seguridad sobre el ingreso físico de trabajadores e invitados.
public class RestriccionAccesoCase {

    private final EjecutorOperaciones ejecutor;

    public RestriccionAccesoCase(EjecutorOperaciones ejecutor) {
        this.ejecutor = Objects.requireNonNull(ejecutor, "El ejecutor de operaciones es obligatorio.");
    }

    // Permite revisar una persona y los motivos registrados antes de tomar otra decisión de seguridad.
    public Optional<ConsultaRestriccionAcceso> consultarSituacion(Integer supervisorId, String documento) {
        if (documento == null || documento.isBlank() || documento.length() > 20) {
            throw new IllegalArgumentException("El documento es obligatorio y admite hasta 20 caracteres.");
        }

        return ejecutor.ejecutar(supervisorId, "consultar_personas_seguridad", (repositorios, supervisor) -> {
            // La consulta incluye auditoría, por eso se comprueba también ese permiso.
            if (!repositorios.crearPermisoRepository().tienePermiso(supervisor.getRolId(), "consultar_auditoria_general")) {
                throw new SecurityException("La cuenta no tiene permiso para revisar el historial de restricciones.");
            }
            Optional<Persona> encontrada = repositorios.crearPersonaRepository().buscarPorDocumento(documento);
            if (encontrada.isEmpty()) {
                return Optional.empty();
            }
            Persona persona = encontrada.get();
            EstadoAccesoPersona estado = repositorios.crearEstadoAccesoPersonaRepository()
                    .buscarPorId(persona.getEstadoAccesoId())
                    .orElseThrow(() -> new IllegalStateException("El estado de acceso de la persona no está configurado."));

            // Solo reunimos prohibiciones y retiros confirmados de esta persona, del más reciente al más antiguo.
            List<BitacoraAuditoria> historial = repositorios.crearBitacoraAuditoriaRepository()
                    .listarPorRegistro("personas", persona.getId()).stream()
                    .filter(registro -> "personas".equals(registro.getTablaAfectada())
                            && persona.getId().equals(registro.getRegistroIdAfectado()))
                    .filter(registro -> "PROHIBICION_INGRESO".equals(registro.getAccionRealizada())
                            || "RETIRO_PROHIBICION_INGRESO".equals(registro.getAccionRealizada()))
                    .sorted(Comparator.comparing(BitacoraAuditoria::getFechaHora, Comparator.reverseOrder())
                            .thenComparing(BitacoraAuditoria::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();

            Map<Integer, String> nombres = new HashMap<>();
            for (BitacoraAuditoria registro : historial) {
                Integer responsableId = registro.getUsuarioId();
                if (responsableId != null) {
                    // Reutilizamos el nombre si el mismo supervisor aparece varias veces en el historial.
                    nombres.computeIfAbsent(responsableId, id -> repositorios.crearUsuarioRepository()
                            .buscarPorId(id).map(Usuario::getNombre).orElse("Cuenta no disponible"));
                }
            }
            return Optional.of(new ConsultaRestriccionAcceso(persona, estado, historial, nombres));
        });
    }

    // El supervisor decide imponer la prohibición y deja escrito el motivo de seguridad.
    public Persona imponerProhibicion(Integer supervisorId, Integer personaId, String motivo) {
        if (personaId == null) {
            throw new IllegalArgumentException("La persona a revisar es obligatoria.");
        }
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo para imponer la prohibición es obligatorio.");
        }

        IntentoGuardado intento = new IntentoGuardado(
                "PROHIBICION_INGRESO_FALLIDA", "personas", personaId);

        return ejecutor.ejecutarGuardado(supervisorId, "imponer_prohibicion_ingreso", intento, (repositorios, supervisor) -> {
            PersonaRepository personas = repositorios.crearPersonaRepository();
            Persona anterior = personas.buscarPorIdParaActualizar(personaId)
                    .orElseThrow(() -> new IllegalArgumentException("La persona no está registrada."));

            EstadoAccesoPersonaRepository estados = repositorios.crearEstadoAccesoPersonaRepository();
            EstadoAccesoPersona activo = estados.buscarPorNombre("Activo")
                    .orElseThrow(() -> new IllegalStateException("El estado Activo no está configurado."));
            EstadoAccesoPersona prohibido = estados.buscarPorNombre("Con Prohibición de Ingreso")
                    .orElseThrow(() -> new IllegalStateException("El estado de prohibición no está configurado."));

            if (Objects.equals(anterior.getEstadoAccesoId(), prohibido.getId())) {
                throw new IllegalStateException("La persona ya tiene una prohibición de ingreso.");
            }
            if (!Objects.equals(anterior.getEstadoAccesoId(), activo.getId())) {
                throw new IllegalStateException("El estado de acceso de la persona no es válido para esta operación.");
            }

            // Solo cambia la restricción de ingreso; no se alteran sus datos ni su vínculo laboral.
            Persona restringida = new Persona(
                    anterior.getId(), anterior.getNombre(), anterior.getDocumentoIdentidad(),
                    anterior.getEmpresaId(), anterior.getTipoPersona(), prohibido.getId(),
                    anterior.getUrlFoto(), anterior.getVinculacionLaboralActiva());

            intento.iniciar();
            if (!personas.actualizar(restringida)) {
                throw new IllegalStateException("No fue posible imponer la prohibición de ingreso.");
            }

            // La prohibición y su auditoría se confirman juntas; las visitas conservan su historial.
            repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                    supervisor.getId(), LocalDateTime.now(), "PROHIBICION_INGRESO", "personas", personaId,
                    "Persona: " + anterior.getNombre()
                    + "; documento: " + anterior.getDocumentoIdentidad()
                    + "; estado anterior: " + activo.getNombreEstado()
                    + "; estado actual: " + prohibido.getNombreEstado()
                    + "; motivo de la prohibición: " + motivo));
            return restringida;
        });
    }

    // El supervisor indica el motivo después de revisar por qué ya puede retirar la prohibición.
    public Persona retirarProhibicion(Integer supervisorId, Integer personaId, String motivo) {
        if (personaId == null) {
            throw new IllegalArgumentException("La persona a revisar es obligatoria.");
        }
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo para retirar la prohibición es obligatorio.");
        }

        IntentoGuardado intento = new IntentoGuardado(
                "RETIRO_PROHIBICION_INGRESO_FALLIDO", "personas", personaId);

        return ejecutor.ejecutarGuardado(supervisorId, "retirar_prohibicion_ingreso", intento, (repositorios, supervisor) -> {
            // El ejecutor ya comprobó la cuenta y el permiso asignado a su rol.
            PersonaRepository personas = repositorios.crearPersonaRepository();
            Persona anterior = personas.buscarPorIdParaActualizar(personaId)
                    .orElseThrow(() -> new IllegalArgumentException("La persona no está registrada."));

            EstadoAccesoPersonaRepository estados = repositorios.crearEstadoAccesoPersonaRepository();
            EstadoAccesoPersona prohibido = estados.buscarPorNombre("Con Prohibición de Ingreso")
                    .orElseThrow(() -> new IllegalStateException("El estado de prohibición no está configurado."));
            EstadoAccesoPersona activo = estados.buscarPorNombre("Activo")
                    .orElseThrow(() -> new IllegalStateException("El estado Activo no está configurado."));

            if (!Objects.equals(anterior.getEstadoAccesoId(), prohibido.getId())) {
                throw new IllegalStateException("La persona no tiene una prohibición de ingreso que retirar.");
            }

            // Activo significa que ya no hay una prohibición; no es una autorización de entrada.
            // La identidad y la vinculación laboral se conservan exactamente como estaban.
            Persona actualizada = new Persona(
                    anterior.getId(), anterior.getNombre(), anterior.getDocumentoIdentidad(),
                    anterior.getEmpresaId(), anterior.getTipoPersona(), activo.getId(),
                    anterior.getUrlFoto(), anterior.getVinculacionLaboralActiva());

            String detalles = "Persona: " + anterior.getNombre()
                    + "; documento: " + anterior.getDocumentoIdentidad()
                    + "; estado anterior: " + prohibido.getNombreEstado()
                    + "; estado actual: " + activo.getNombreEstado()
                    + "; motivo del retiro: " + motivo;

            intento.iniciar();
            if (!personas.actualizar(actualizada)) {
                throw new IllegalStateException("No fue posible retirar la prohibición de ingreso.");
            }

            // El cambio y su motivo se guardan juntos. La auditoría anterior permanece intacta.
            repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                    supervisor.getId(), LocalDateTime.now(), "RETIRO_PROHIBICION_INGRESO",
                    "personas", personaId, detalles));
            return actualizada;
        });
    }
}
