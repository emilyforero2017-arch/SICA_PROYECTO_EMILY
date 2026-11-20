package com.sica.model.application.usecase;

import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Visita;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

// Consulta el ingreso abierto y registra la salida real de invitados y trabajadores.
public class ControlSalidaCase {

    private final EjecutorOperaciones ejecutor;
    private final Clock reloj;

    public ControlSalidaCase(EjecutorOperaciones ejecutor) {
        this(ejecutor, Clock.system(ZoneId.of("America/Bogota")));
    }

    public ControlSalidaCase(EjecutorOperaciones ejecutor, Clock reloj) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
        this.reloj = Objects.requireNonNull(reloj);
    }

    public Optional<Visita> consultarIngresoAbierto(Integer guardaId, String documento) {
        validarDocumento(documento);
        return ejecutor.ejecutar(guardaId, "consultar_persona_porteria", (repositorios, guarda) -> {
            if (!repositorios.crearPermisoRepository().tienePermiso(guarda.getRolId(), "consultar_visita_porteria")) {
                throw new SecurityException("La cuenta no tiene permiso para consultar ingresos en portería.");
            }
            Optional<Persona> persona = repositorios.crearPersonaRepository().buscarPorDocumento(documento);
            if (persona.isEmpty()) {
                return Optional.empty();
            }
            // Esta consulta permite encontrar el ingreso; al confirmar se comprobará otra vez.
            return repositorios.crearVisitaRepository().buscarIngresoAbiertoPorPersona(persona.get().getId());
        });
    }

    public Visita registrarSalida(Integer guardaId, String documento, Integer visitaId) {
        validarDocumento(documento);
        if (visitaId == null) {
            throw new IllegalArgumentException("El ingreso al que corresponde la salida es obligatorio.");
        }
        IntentoGuardado intento = new IntentoGuardado("REGISTRO_SALIDA_FALLIDO", "visitas", visitaId,
                "Documento presentado: " + documento);
        return ejecutor.ejecutarGuardado(guardaId, "registrar_salida", intento, (repositorios, guarda) -> {
            Visita referencia = repositorios.crearVisitaRepository().buscarPorId(visitaId)
                    .orElseThrow(() -> new IllegalArgumentException("El ingreso no existe."));

            // Protegemos empresa, persona y visita en el mismo orden que al registrar una entrada.
            // Una empresa desactivada conserva sus registros y permite registrar las salidas pendientes.
            Empresa empresa = repositorios.crearEmpresaRepository()
                    .buscarPorIdParaActualizar(referencia.getEmpresaDestinoId())
                    .orElseThrow(() -> new IllegalStateException("La empresa del ingreso no existe."));
            Persona persona = repositorios.crearPersonaRepository().buscarPorDocumentoParaActualizar(documento)
                    .orElseThrow(() -> new IllegalArgumentException("La persona no está registrada con ese documento."));
            Visita visita = repositorios.crearVisitaRepository().buscarPorIdParaActualizar(visitaId)
                    .orElseThrow(() -> new IllegalArgumentException("El ingreso ya no existe."));
            if (!empresa.getId().equals(visita.getEmpresaDestinoId())
                    || !persona.getId().equals(visita.getPersonaId())) {
                throw new IllegalArgumentException("El ingreso no corresponde a la persona o sus datos cambiaron.");
            }

            Integer dentroId = repositorios.crearEstadoVisitaRepository().buscarPorNombre("Dentro")
                    .orElseThrow(() -> new IllegalStateException("No está configurado el estado Dentro.")).getId();
            if (!dentroId.equals(visita.getEstadoVisitaId()) || visita.getFechaEntrada() == null
                    || visita.getFechaSalida() != null || visita.getFechaCierreSistema() != null) {
                throw new IllegalStateException("La visita no tiene un ingreso abierto para registrar su salida.");
            }
            Integer fueraId = repositorios.crearEstadoVisitaRepository().buscarPorNombre("Fuera")
                    .orElseThrow(() -> new IllegalStateException("No está configurado el estado Fuera.")).getId();
            LocalDateTime ahora = LocalDateTime.now(reloj);
            if (ahora.isBefore(visita.getFechaEntrada())) {
                throw new IllegalStateException("La hora de salida no puede ser anterior a la entrada.");
            }

            // La prohibición y la vinculación laboral regulan nuevas entradas; la salida real debe quedar registrada.
            visita.actualizarMovimiento(fueraId, visita.getFechaEntrada(), ahora, null);
            intento.iniciar();
            if (!repositorios.crearVisitaRepository().actualizar(visita)) {
                throw new IllegalStateException("No fue posible guardar la salida.");
            }
            repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                    guarda.getId(), ahora, "REGISTRO_SALIDA", "visitas", visita.getId(),
                    "Persona: " + persona.getId() + "; empresa: " + empresa.getId()
                    + "; entrada: " + visita.getFechaEntrada() + "; salida real: " + ahora));
            // La salida y su auditoría se confirman juntas; un fallo deshace ambos cambios.
            return visita;
        });
    }

    private void validarDocumento(String documento) {
        if (documento == null || documento.isBlank() || documento.length() > 20) {
            throw new IllegalArgumentException("El documento es obligatorio y admite hasta 20 caracteres.");
        }
    }
}
