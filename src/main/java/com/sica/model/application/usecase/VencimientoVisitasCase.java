package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.application.port.GestorTransacciones;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.policy.HorarioIngreso;
import com.sica.model.domain.policy.HorarioCarnetOlvidado;
import com.sica.model.domain.policy.HorarioVisitaNoAnunciada;
import com.sica.model.domain.policy.HorarioVisitaProgramada;
import com.sica.model.domain.policy.PoliticaHorarioIngreso;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Marca como expiradas las autorizaciones aprobadas cuyo tiempo terminó sin una entrada.
public class VencimientoVisitasCase {

    private static final Map<TipoIngreso, PoliticaHorarioIngreso> HORARIOS = Map.of(
            TipoIngreso.INVITADO_PREREGISTRADO, new HorarioVisitaProgramada(),
            TipoIngreso.INVITADO_NO_ANUNCIADO, new HorarioVisitaNoAnunciada(),
            TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO, new HorarioCarnetOlvidado());

    private final GestorTransacciones gestor;
    private final Clock reloj;

    public VencimientoVisitasCase(GestorTransacciones gestor) {
        this(gestor, Clock.system(ZoneId.of("America/Bogota")));
    }

    public VencimientoVisitasCase(GestorTransacciones gestor, Clock reloj) {
        this.gestor = Objects.requireNonNull(gestor);
        this.reloj = Objects.requireNonNull(reloj);
    }

    // Lo ejecuta la tarea automática; no necesita una cuenta ni una sesión abierta.
    public int expirarPendientes() {
        List<Integer> candidatas = gestor.ejecutar(repositorios -> {
            Integer aprobado = estado(repositorios, "Aprobado");
            return repositorios.crearVisitaRepository().listarPorEstado(aprobado).stream()
                    .filter(visita -> HORARIOS.containsKey(visita.getTipoIngreso()))
                    .filter(visita -> visita.getFechaEntrada() == null)
                    .map(Visita::getId).toList();
        });

        int cantidad = 0;
        List<RuntimeException> errores = new ArrayList<>();
        for (Integer visitaId : candidatas) {
            IntentoGuardado intento = new IntentoGuardado("EXPIRACION_VISITA_FALLIDA", "visitas", visitaId);
            try {
                boolean cambio = gestor.ejecutar(repositorios -> {
                    Visita visita = repositorios.crearVisitaRepository().buscarPorIdParaActualizar(visitaId)
                            .orElseThrow(() -> new IllegalStateException("La visita a revisar no existe."));
                    return expirarSiCorresponde(repositorios, visita, LocalDateTime.now(reloj), intento);
                });
                if (cambio) {
                    cantidad++;
                }
            } catch (RuntimeException error) {
                // El fallo de una visita no impide revisar las demás.
                if (intento.estaIniciado()) {
                    registrarFallo(visitaId, error);
                }
                errores.add(error);
            }
        }
        if (!errores.isEmpty()) {
            IllegalStateException error = new IllegalStateException("No fue posible revisar todas las autorizaciones.");
            errores.forEach(error::addSuppressed);
            throw error;
        }
        return cantidad;
    }

    // Portería utiliza la misma regla dentro de su transacción, con la visita protegida para actualizar.
    static boolean expirarSiCorresponde(
            FabricarRepositorios repositorios, Visita visita, LocalDateTime ahora, IntentoGuardado intento) {
        PoliticaHorarioIngreso politica = HORARIOS.get(visita.getTipoIngreso());
        if (politica == null || visita.getFechaEntrada() != null
                || visita.getFechaSalida() != null || visita.getFechaCierreSistema() != null
                || !estado(repositorios, "Aprobado").equals(visita.getEstadoVisitaId())) {
            return false;
        }
        HorarioIngreso horario = politica.calcular(visita);
        // El horario incluye completo el minuto final; solo expira al comenzar el minuto siguiente.
        if (!ahora.isAfter(horario.getHasta())) {
            return false;
        }
        Integer expirado = estado(repositorios, "Expirado");
        intento.iniciar();
        visita.actualizarMovimiento(expirado, null, null, null);
        if (!repositorios.crearVisitaRepository().actualizar(visita)) {
            throw new IllegalStateException("No fue posible marcar la autorización como expirada.");
        }
        // El responsable es el sistema; no se inventa una entrada, salida ni cierre de una permanencia.
        repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                null, ahora, "EXPIRACION_VISITA", "visitas", visita.getId(),
                "Estado anterior: Aprobado; estado actual: Expirado; no se registró una entrada."
                + " Tipo: " + visita.getTipoIngreso() + "; límite de ingreso: " + horario.getHasta()));
        return true;
    }

    private static Integer estado(FabricarRepositorios repositorios, String nombre) {
        return repositorios.crearEstadoVisitaRepository().buscarPorNombre(nombre)
                .orElseThrow(() -> new IllegalStateException("No está configurado el estado: " + nombre)).getId();
    }

    private void registrarFallo(Integer visitaId, RuntimeException original) {
        // Se guarda después de deshacer la expiración fallida y sin volver a auditar esta auditoría.
        try {
            gestor.ejecutar(repositorios -> {
                repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                        null, LocalDateTime.now(reloj), "EXPIRACION_VISITA_FALLIDA", "visitas", visitaId,
                        "No se pudo guardar el vencimiento de la autorización. Motivo: "
                        + Objects.toString(original.getMessage(), original.getClass().getSimpleName())));
                return null;
            });
        } catch (RuntimeException errorAuditoria) {
            if (errorAuditoria != original) {
                original.addSuppressed(errorAuditoria);
            }
        }
    }
}
