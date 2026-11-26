package com.sica.model.domain.repository;

import java.time.LocalDateTime;

public record MovimientoPorteria(LocalDateTime hora, String persona, String empresa) {
    public enum Tipo { ENTRADA_INVITADO, INGRESO_LABORAL, SALIDA }
}
