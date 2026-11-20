package com.sica.config;

import com.sica.model.application.usecase.VencimientoVisitasCase;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

// Configura una revisión periódica para que las autorizaciones venzan sin intervención del guarda.
public final class ProgramadorVencimientoVisitas implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ProgramadorVencimientoVisitas.class.getName());
    private final VencimientoVisitasCase vencimiento;
    private final ScheduledExecutorService ejecutor;
    private ScheduledFuture<?> tarea;

    public ProgramadorVencimientoVisitas(VencimientoVisitasCase vencimiento) {
        this.vencimiento = Objects.requireNonNull(vencimiento);
        this.ejecutor = Executors.newSingleThreadScheduledExecutor(trabajo -> {
            Thread hilo = new Thread(trabajo, "sica-vencimiento-visitas");
            hilo.setDaemon(true);
            return hilo;
        });
    }

    // Se iniciará al arrancar SICA, cuando esté conectado el gestor real de transacciones.
    public synchronized void iniciar() {
        if (ejecutor.isShutdown()) {
            throw new IllegalStateException("El programador de vencimientos ya está cerrado.");
        }
        if (tarea == null) {
            tarea = ejecutor.scheduleWithFixedDelay(this::revisar, 0, 1, TimeUnit.SECONDS);
        }
    }

    private void revisar() {
        try {
            vencimiento.expirarPendientes();
        } catch (RuntimeException error) {
            // Un error no debe detener para siempre las revisiones de los siguientes segundos.
            LOG.log(Level.WARNING, "Falló una revisión de vencimientos; se volverá a intentar.", error);
        }
    }

    @Override
    public synchronized void close() {
        ejecutor.shutdownNow();
    }
}
