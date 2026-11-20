package com.sica.model.application.usecase;

// Distingue los hechos del historial; un cierre del sistema no es una salida real.
public enum TipoMovimientoAcceso {
    ENTRADA,
    SALIDA,
    CIERRE_SIN_SALIDA_REGISTRADA
}
