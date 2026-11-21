package com.sica.config;

import com.sica.model.application.usecase.RegularizacionSalidasCase;
import com.sica.model.application.usecase.VencimientoVisitasCase;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

// Ejecuta las revisiones del sistema aunque nadie haya iniciado sesión o esté consultando portería.
public final class ProgramadorRevisionesAutomaticas implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ProgramadorRevisionesAutomaticas.class.getName());
    private final VencimientoVisitasCase vencimientos;
    private final RegularizacionSalidasCase salidas;
    private final ScheduledExecutorService ejecutor;
    private boolean iniciado;

    public ProgramadorRevisionesAutomaticas(VencimientoVisitasCase vencimientos, RegularizacionSalidasCase salidas) {
        this.vencimientos = Objects.requireNonNull(vencimientos);
        this.salidas = Objects.requireNonNull(salidas);
        AtomicInteger numero = new AtomicInteger();
        // Cada revisión tiene su propio hilo; una no debe retrasar a la otra.
        this.ejecutor = Executors.newScheduledThreadPool(2, trabajo -> {
            Thread hilo = new Thread(trabajo, "sica-revision-" + numero.incrementAndGet());
            hilo.setDaemon(true);
            return hilo;
        });
    }

    public synchronized void iniciar() {
        if (ejecutor.isShutdown()) throw new IllegalStateException("Las revisiones automáticas ya están cerradas.");
        if (iniciado) return;
        try {
            // Se revisa al comenzar y se espera un segundo después de terminar cada revisión.
            ejecutor.scheduleWithFixedDelay(() -> revisar("autorizaciones vencidas", vencimientos::expirarPendientes),
                    0, 1, TimeUnit.SECONDS);
            ejecutor.scheduleWithFixedDelay(() -> revisar("ingresos sin salida", salidas::cerrarIngresosSinSalida),
                    0, 1, TimeUnit.SECONDS);
            iniciado = true;
        } catch (RuntimeException error) {
            ejecutor.shutdownNow();
            throw error;
        }
    }

    private void revisar(String nombre, Runnable operacion) {
        try { operacion.run(); }
        catch (RuntimeException error) {
            // Un fallo se informa, pero no cancela las revisiones siguientes.
            LOG.log(Level.WARNING, "Falló la revisión de " + nombre + "; se volverá a intentar.", error);
        }
    }

    @Override
    public void close() {
        // No iniciamos otra revisión y damos tiempo a confirmar o deshacer la que estaba en curso.
        ejecutor.shutdown();
        try {
            if (!ejecutor.awaitTermination(25, TimeUnit.SECONDS)) {
                ejecutor.shutdownNow();
                if (!ejecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.warning("Una revisión no terminó dentro del tiempo de cierre.");
                }
            }
        } catch (InterruptedException error) {
            ejecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
