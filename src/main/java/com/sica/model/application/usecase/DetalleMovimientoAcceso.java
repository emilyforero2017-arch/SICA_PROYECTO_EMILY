package com.sica.model.application.usecase;

import java.time.LocalDateTime;
import java.util.Objects;

// Representa un movimiento ocurrido en el período, junto con los datos del acceso relacionado.
public final class DetalleMovimientoAcceso {

    private final DetalleAccesoSeguridad acceso;
    private final TipoMovimientoAcceso tipo;
    private final LocalDateTime fechaMovimiento;

    public DetalleMovimientoAcceso(DetalleAccesoSeguridad acceso, TipoMovimientoAcceso tipo,
            LocalDateTime fechaMovimiento) {
        this.acceso = Objects.requireNonNull(acceso);
        this.tipo = Objects.requireNonNull(tipo);
        this.fechaMovimiento = Objects.requireNonNull(fechaMovimiento);
    }

    public DetalleAccesoSeguridad getAcceso() { return acceso; }
    public TipoMovimientoAcceso getTipo() { return tipo; }
    public LocalDateTime getFechaMovimiento() { return fechaMovimiento; }
}
