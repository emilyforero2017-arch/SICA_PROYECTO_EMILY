package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.application.port.GestorTransacciones;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Visita;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// Cierra ingresos que cumplen 24 horas sin salida, conservando desconocida la hora real de salida.
public class RegularizacionSalidasCase {

    private final GestorTransacciones gestor;
    private final Clock reloj;

    public RegularizacionSalidasCase(GestorTransacciones gestor) {
        this(gestor, Clock.system(ZoneId.of("America/Bogota")));
    }

    public RegularizacionSalidasCase(GestorTransacciones gestor, Clock reloj) {
        this.gestor = Objects.requireNonNull(gestor);
        this.reloj = Objects.requireNonNull(reloj);
    }

    // El sistema ejecutará esta revisión; no depende de que la persona vuelva ni de una sesión abierta.
    public int cerrarIngresosSinSalida() {
        LocalDateTime limite = LocalDateTime.now(reloj).minusHours(24);
        List<Integer> candidatas = gestor.ejecutar(repositorios -> {
            Integer dentroId = estado(repositorios, "Dentro");
            return repositorios.crearVisitaRepository().listarIngresosAbiertosHasta(limite).stream()
                    .filter(visita -> sigueAbierta(visita, dentroId, limite))
                    .map(Visita::getId).toList();
        });

        int cantidad = 0;
        List<RuntimeException> errores = new ArrayList<>();
        for (Integer visitaId : candidatas) {
            IntentoGuardado intento = new IntentoGuardado("CIERRE_SALIDA_NO_REGISTRADA_FALLIDO", "visitas", visitaId);
            try {
                // Cada ingreso se confirma por separado: un fallo no deshace los cierres de otras personas.
                if (gestor.ejecutar(repositorios -> cerrarSiCorresponde(repositorios, visitaId, intento))) {
                    cantidad++;
                }
            } catch (RuntimeException error) {
                if (intento.estaIniciado()) {
                    registrarFallo(visitaId, error);
                }
                errores.add(error);
            }
        }
        if (!errores.isEmpty()) {
            IllegalStateException error = new IllegalStateException("No fue posible revisar todos los ingresos sin salida.");
            errores.forEach(error::addSuppressed);
            throw error;
        }
        return cantidad;
    }

    private boolean cerrarSiCorresponde(
            FabricarRepositorios repositorios, Integer visitaId, IntentoGuardado intento) {
        Visita referencia = repositorios.crearVisitaRepository().buscarPorId(visitaId)
                .orElseThrow(() -> new IllegalStateException("El ingreso a revisar no existe."));
        // Se mantiene el mismo orden de protección que en las entradas y las salidas reales.
        Empresa empresa = repositorios.crearEmpresaRepository().buscarPorIdParaActualizar(referencia.getEmpresaDestinoId())
                .orElseThrow(() -> new IllegalStateException("La empresa del ingreso no existe."));
        Persona persona = repositorios.crearPersonaRepository().buscarPorIdParaActualizar(referencia.getPersonaId())
                .orElseThrow(() -> new IllegalStateException("La persona del ingreso no existe."));
        Visita visita = repositorios.crearVisitaRepository().buscarPorIdParaActualizar(visitaId)
                .orElseThrow(() -> new IllegalStateException("El ingreso a revisar ya no existe."));
        if (!empresa.getId().equals(visita.getEmpresaDestinoId()) || !persona.getId().equals(visita.getPersonaId())) {
            throw new IllegalStateException("Los datos del ingreso cambiaron; debe revisarse de nuevo.");
        }

        LocalDateTime ahora = LocalDateTime.now(reloj);
        // Mientras esperábamos, el guarda pudo registrar la salida o una nueva entrada pudo regularizarla.
        if (!sigueAbierta(visita, estado(repositorios, "Dentro"), ahora.minusHours(24))) {
            return false;
        }
        Integer cierreId = estado(repositorios, "Cerrada por Sistema - Salida no registrada");
        intento.iniciar();
        visita.actualizarMovimiento(cierreId, visita.getFechaEntrada(), null, ahora);
        if (!repositorios.crearVisitaRepository().actualizar(visita)) {
            throw new IllegalStateException("No fue posible cerrar el ingreso sin salida.");
        }
        repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                null, ahora, "CIERRE_SALIDA_NO_REGISTRADA", "visitas", visitaId,
                "Cierre automático por cumplir 24 horas sin salida registrada. Persona: " + persona.getId()
                + "; empresa: " + empresa.getId() + "; entrada: " + visita.getFechaEntrada()
                + "; límite de 24 horas: " + visita.getFechaEntrada().plusHours(24)
                + "; cierre del sistema: " + ahora + ". La hora real de salida se desconoce."));
        // El responsable es el sistema. El cierre y su auditoría se confirman juntos.
        return true;
    }

    private boolean sigueAbierta(Visita visita, Integer dentroId, LocalDateTime limite) {
        return dentroId.equals(visita.getEstadoVisitaId()) && visita.getFechaEntrada() != null
                && visita.getFechaSalida() == null && visita.getFechaCierreSistema() == null
                && !visita.getFechaEntrada().isAfter(limite);
    }

    private Integer estado(FabricarRepositorios repositorios, String nombre) {
        return repositorios.crearEstadoVisitaRepository().buscarPorNombre(nombre)
                .orElseThrow(() -> new IllegalStateException("No está configurado el estado: " + nombre)).getId();
    }

    private void registrarFallo(Integer visitaId, RuntimeException original) {
        // El ingreso fallido ya se deshizo. Su intento se registra en otra operación.
        try {
            gestor.ejecutar(repositorios -> {
                repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                        null, LocalDateTime.now(reloj), "CIERRE_SALIDA_NO_REGISTRADA_FALLIDO", "visitas", visitaId,
                        "No se pudo guardar el cierre por 24 horas sin salida. Motivo: "
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
