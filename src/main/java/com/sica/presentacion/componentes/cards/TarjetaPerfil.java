package com.sica.presentacion.componentes.cards;

import com.sica.presentacion.componentes.branding.Portrait;

import static com.sica.presentacion.componentes.ElementosUi.*;

public final class TarjetaPerfil extends Tarjeta {
    public TarjetaPerfil(String title, String nombre, String rol, int retrato, byte[] foto) {
        super("white");
        getStyleClass().add("compact-profile");
        getChildren().addAll(label(title, "card-kicker"),
                row(12, new Portrait(nombre, retrato, 42, foto), textBlock(nombre, rol)));
    }
}
