package com.sica.presentacion.componentes.cards;

import com.sica.presentacion.animaciones.Motion;
import com.sica.presentacion.componentes.branding.LogoSica;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import static com.sica.presentacion.componentes.ElementosUi.label;

public final class EstadoVacio extends VBox {
    public EstadoVacio(String title, String detail) {
        super(8);
        getStyleClass().add("empty-state");
        setAlignment(Pos.CENTER);
        var mark = LogoSica.mark(43, Color.web("#94AE8B"));
        Label copy = label(detail, "muted");
        copy.setWrapText(true);
        getChildren().addAll(mark, label(title, "row-title"), copy);
        Motion.confirm(mark);
    }
}
