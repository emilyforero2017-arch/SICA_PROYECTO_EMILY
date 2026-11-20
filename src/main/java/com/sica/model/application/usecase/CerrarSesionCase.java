package com.sica.model.application.usecase;

import java.util.Objects;

// Termina la sesión de esta aplicación y detiene sus avisos, conservando activa la cuenta.
public final class CerrarSesionCase {

    private final ContextoSesion contexto;

    public CerrarSesionCase(ContextoSesion contexto) {
        this.contexto = Objects.requireNonNull(contexto);
    }

    // Cualquier usuario puede cerrar su propia sesión, aunque su cuenta o su empresa hayan sido desactivadas.
    public void cerrar() {
        contexto.cerrar();
    }
}
