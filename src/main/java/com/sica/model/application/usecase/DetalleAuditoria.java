package com.sica.model.application.usecase;

import com.sica.model.domain.entity.BitacoraAuditoria;
import java.util.Objects;

// Prepara el registro y el nombre del responsable para consultar la bitácora.
public final class DetalleAuditoria {

    private final BitacoraAuditoria registro;
    private final String nombreResponsable;

    public DetalleAuditoria(BitacoraAuditoria registro, String nombreResponsable) {
        this.registro = Objects.requireNonNull(registro);
        this.nombreResponsable = Objects.requireNonNull(nombreResponsable);
    }

    public BitacoraAuditoria getRegistro() { return registro; }
    public String getNombreResponsable() { return nombreResponsable; }
}
