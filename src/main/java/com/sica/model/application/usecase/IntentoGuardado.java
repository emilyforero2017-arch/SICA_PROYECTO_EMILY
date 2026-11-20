package com.sica.model.application.usecase;

import java.util.Objects;

// Identifica un guardado y permite saber si llegó a empezar después de las validaciones.
public class IntentoGuardado {

    private final String accionFallida;
    private final String tablaAfectada;
    private final Integer registroIdAfectado;
    private final String detallesIntento;
    private boolean iniciado;

    public IntentoGuardado(
            String accionFallida,
            String tablaAfectada,
            Integer registroIdAfectado) {

        this(accionFallida, tablaAfectada, registroIdAfectado, null);
    }

    // Permite identificar el intento cuando intervienen varios datos, como un rol y un permiso.
    public IntentoGuardado(
            String accionFallida,
            String tablaAfectada,
            Integer registroIdAfectado,
            String detallesIntento) {

        this.accionFallida = Objects.requireNonNull(
                accionFallida, "La acción fallida es obligatoria.");
        this.tablaAfectada = Objects.requireNonNull(
                tablaAfectada, "La tabla afectada es obligatoria.");
        this.registroIdAfectado = registroIdAfectado;
        this.detallesIntento = detallesIntento;
    }

    // Se llama justo antes de intentar escribir los cambios en la base de datos.
    public void iniciar() {
        iniciado = true;
    }

    public boolean estaIniciado() {
        return iniciado;
    }

    public String getAccionFallida() {
        return accionFallida;
    }

    public String getTablaAfectada() {
        return tablaAfectada;
    }

    // Puede ser null al registrar algo nuevo que todavía no tiene un identificador válido.
    public Integer getRegistroIdAfectado() {
        return registroIdAfectado;
    }

    public String getDetallesIntento() {
        return detallesIntento;
    }
}
