package com.sica.model.domain.policy;

import java.time.LocalDateTime;
import java.util.Objects;

// Conserva el inicio de la autorización e incluye completo su último minuto permitido.
public final class HorarioIngreso {

    private final LocalDateTime desde;
    private final LocalDateTime hasta;

    public HorarioIngreso(LocalDateTime desde, LocalDateTime hasta) {
        this.desde = Objects.requireNonNull(desde, "El inicio del horario es obligatorio.");
        Objects.requireNonNull(hasta, "El fin del horario es obligatorio.");
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException("El fin del horario no puede ser anterior al inicio.");
        }
        // Un límite a las 14:25 permite todos los segundos de ese minuto, hasta antes de las 14:26.
        this.hasta = hasta.withSecond(59).withNano(999_999_999);
    }

    public LocalDateTime getDesde() {
        return desde;
    }

    public LocalDateTime getHasta() {
        return hasta;
    }

    // Se conserva el inicio exacto y se permite entrar durante todo el minuto final.
    public boolean contiene(LocalDateTime momento) {
        Objects.requireNonNull(momento, "El momento a comprobar es obligatorio.");
        return !momento.isBefore(desde) && !momento.isAfter(hasta);
    }
}
