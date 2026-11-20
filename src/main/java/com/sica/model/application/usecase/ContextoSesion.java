package com.sica.model.application.usecase;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

// Cada instancia de SICA conserva aquí su sesión actual y los seguimientos que le pertenecen.
public final class ContextoSesion {

    private SesionUsuario actual;
    private final Set<AutoCloseable> seguimientos = new LinkedHashSet<>();

    public synchronized Optional<SesionUsuario> consultarActual() {
        return Optional.ofNullable(actual);
    }

    public synchronized Integer requerirUsuarioId() {
        if (actual == null) {
            throw new SecurityException("Debes iniciar sesión para utilizar SICA.");
        }
        return actual.getUsuarioId();
    }

    // Solo se publica la sesión si la comprobación de credenciales y su auditoría terminaron correctamente.
    synchronized SesionUsuario iniciar(Supplier<SesionUsuario> autenticar) {
        if (actual != null) {
            throw new IllegalStateException("Debes cerrar la sesión actual antes de iniciar otra.");
        }
        SesionUsuario nueva = Objects.requireNonNull(autenticar.get(), "La sesión autenticada es obligatoria.");
        actual = nueva;
        return nueva;
    }

    // Una operación que ya empezó termina antes del cierre; las siguientes necesitan una sesión vigente.
    synchronized <Resultado> Resultado ejecutarComo(Integer usuarioId, Supplier<Resultado> trabajo) {
        if (!requerirUsuarioId().equals(usuarioId)) {
            throw new SecurityException("La operación no corresponde al usuario de la sesión actual.");
        }
        return trabajo.get();
    }

    public AutoCloseable registrarSeguimiento(Integer usuarioId, AutoCloseable seguimiento) {
        Objects.requireNonNull(seguimiento, "El seguimiento es obligatorio.");
        synchronized (this) {
            if (actual != null && actual.getUsuarioId().equals(usuarioId)) {
                seguimientos.add(seguimiento);
                return () -> {
                    boolean cerrar;
                    synchronized (ContextoSesion.this) {
                        cerrar = seguimientos.remove(seguimiento);
                    }
                    if (cerrar) {
                        seguimiento.close();
                    }
                };
            }
        }
        SecurityException error = new SecurityException("Debes iniciar sesión para recibir estos avisos.");
        try {
            seguimiento.close();
        } catch (Exception falloCierre) {
            error.addSuppressed(falloCierre);
        }
        throw error;
    }

    void cerrar() {
        List<AutoCloseable> pendientes;
        synchronized (this) {
            // La identificación se retira primero, incluso si después falla el cierre de algún seguimiento.
            actual = null;
            pendientes = new ArrayList<>(seguimientos);
            seguimientos.clear();
        }
        IllegalStateException error = null;
        for (AutoCloseable seguimiento : pendientes) {
            try {
                seguimiento.close();
            } catch (Exception fallo) {
                if (fallo instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (error == null) {
                    error = new IllegalStateException("La sesión se cerró, pero no fue posible detener todos sus seguimientos.");
                }
                error.addSuppressed(fallo);
            }
        }
        if (error != null) {
            throw error;
        }
    }
}
