package com.sica;

import com.sica.presentacion.inicio.AplicacionEscritorio;
import javafx.application.Application;

public final class Main {

    private Main() { }

    public static void main(String[] args) {
        // JavaFX abre la ventana; su configuración conserva el arranque y el cierre de SICA.
        Application.launch(AplicacionEscritorio.class, args);
    }
}
