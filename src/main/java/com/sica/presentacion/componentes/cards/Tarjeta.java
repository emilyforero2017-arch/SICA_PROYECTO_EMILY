package com.sica.presentacion.componentes.cards;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

public class Tarjeta extends VBox {
    public Tarjeta(String tone, Node... children) {
        super(12, children);
        getStyleClass().addAll("sica-card", "surface-" + tone);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
    }
}
