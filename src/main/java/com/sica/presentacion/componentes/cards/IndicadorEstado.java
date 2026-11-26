package com.sica.presentacion.componentes.cards;

import com.sica.presentacion.integracion.SicaData.Status;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;

public final class IndicadorEstado extends Label {
    public IndicadorEstado(Status status) {
        super(status.label);
        Region dot = new Region();
        dot.getStyleClass().add("badge-dot");
        setGraphic(dot);
        setGraphicTextGap(6);
        getStyleClass().addAll("status-badge", "status-" + status.tone);
    }
}
