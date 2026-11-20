package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Incidente;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.entity.Visita;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

// Registra lo observado o comunicado al supervisor y permite consultar los incidentes anteriores.
public class IncidenteCase {

    private final EjecutorOperaciones ejecutor;
    private final Clock reloj;

    public IncidenteCase(EjecutorOperaciones ejecutor) {
        this(ejecutor, Clock.system(ZoneId.of("America/Bogota")));
    }

    public IncidenteCase(EjecutorOperaciones ejecutor, Clock reloj) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
        this.reloj = Objects.requireNonNull(reloj);
    }

    public Incidente registrar(
            Integer supervisorId, Integer visitaId, LocalDateTime fechaIncidente, String descripcion) {
        IntentoGuardado intento = new IntentoGuardado("REGISTRO_INCIDENTE_FALLIDO", "incidentes", null,
                "Visita: " + visitaId + "; fecha del incidente: " + fechaIncidente);
        return ejecutor.ejecutarGuardado(supervisorId, "registrar_incidente", intento, (repositorios, supervisor) -> {
            // La entidad comprueba que estén definidos la visita, el responsable, la fecha y la descripción.
            Incidente nuevo = new Incidente(visitaId, supervisor.getId(), fechaIncidente, descripcion);
            Visita visita = repositorios.crearVisitaRepository().buscarPorId(visitaId)
                    .orElseThrow(() -> new IllegalArgumentException("La visita relacionada con el incidente no existe."));
            LocalDateTime registradoEn = LocalDateTime.now(reloj);

            // Puede registrarse después de que terminó la visita: conservamos la fecha del hecho por separado.
            intento.iniciar();
            Incidente guardado = repositorios.crearIncidenteRepository().guardar(nuevo);
            if (guardado == null || guardado.getId() == null) {
                throw new IllegalStateException("No fue posible obtener el incidente guardado.");
            }
            repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                    supervisor.getId(), registradoEn, "REGISTRO_INCIDENTE", "incidentes", guardado.getId(),
                    "Visita: " + visitaId + "; persona: " + visita.getPersonaId()
                    + "; empresa: " + visita.getEmpresaDestinoId() + "; fecha del incidente: " + fechaIncidente
                    + "; descripción: " + descripcion));
            // El incidente y su auditoría se guardan juntos. Las restricciones de ingreso se gestionan aparte.
            return guardado;
        });
    }

    public List<DetalleIncidente> consultarPorVisita(Integer supervisorId, Integer visitaId) {
        if (visitaId == null) {
            throw new IllegalArgumentException("La visita a consultar es obligatoria.");
        }
        return ejecutor.ejecutar(supervisorId, "consultar_historial_incidentes", (repositorios, supervisor) -> {
            repositorios.crearVisitaRepository().buscarPorId(visitaId)
                    .orElseThrow(() -> new IllegalArgumentException("La visita a consultar no existe."));
            // Stream selecciona los incidentes de esta visita y prepara sus datos para el historial.
            return repositorios.crearIncidenteRepository().listarPorVisita(visitaId).stream()
                    .filter(incidente -> visitaId.equals(incidente.getVisitaId()))
                    .sorted(ordenHistorial())
                    .map(incidente -> detalle(repositorios, incidente))
                    .toList();
        });
    }

    // Incluye los hechos desde la fecha inicial y anteriores a la fecha final.
    public List<DetalleIncidente> consultarHistorial(
            Integer supervisorId, LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null || !hasta.isAfter(desde)) {
            throw new IllegalArgumentException("Debes indicar un período con fecha final posterior a la inicial.");
        }
        return ejecutor.ejecutar(supervisorId, "consultar_historial_incidentes", (repositorios, supervisor) ->
                repositorios.crearIncidenteRepository().listarPorPeriodo(desde, hasta).stream()
                        .filter(incidente -> !incidente.getFechaIncidente().isBefore(desde)
                                && incidente.getFechaIncidente().isBefore(hasta))
                        .sorted(ordenHistorial())
                        .map(incidente -> detalle(repositorios, incidente))
                        .toList());
    }

    // Primero aparece el hecho más reciente; si coinciden las fechas, aparece el identificador mayor.
    private Comparator<Incidente> ordenHistorial() {
        return Comparator.comparing(Incidente::getFechaIncidente, Comparator.reverseOrder())
                .thenComparing(Incidente::getId, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private DetalleIncidente detalle(FabricarRepositorios repositorios, Incidente incidente) {
        Visita visita = repositorios.crearVisitaRepository().buscarPorId(incidente.getVisitaId())
                .orElseThrow(() -> new IllegalStateException("La visita del incidente no existe."));
        Persona persona = repositorios.crearPersonaRepository().buscarPorId(visita.getPersonaId())
                .orElseThrow(() -> new IllegalStateException("La persona relacionada con el incidente no existe."));
        Empresa empresa = repositorios.crearEmpresaRepository().buscarPorId(visita.getEmpresaDestinoId())
                .orElseThrow(() -> new IllegalStateException("La empresa relacionada con el incidente no existe."));
        Usuario supervisor = repositorios.crearUsuarioRepository().buscarPorId(incidente.getRegistradoPorId())
                .orElseThrow(() -> new IllegalStateException("La cuenta que registró el incidente no existe."));
        // El historial conserva información de empresas retiradas y de supervisores cuyas cuentas estén inactivas.
        return new DetalleIncidente(incidente, visita, persona, empresa.getNombre(), supervisor.getNombre());
    }
}
