package com.sica.presentacion.componentes.cards;

import com.sica.presentacion.componentes.branding.Icons;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import static com.sica.presentacion.componentes.ElementosUi.*;

public final class TarjetaIndicador extends Tarjeta {
    public TarjetaIndicador(String title, int value, String detail, String icon, String tone) {
        super(tone);
        getStyleClass().add("stat-card");
        StackPane iconBox = new StackPane(Icons.icon(icon, 18));
        iconBox.getStyleClass().add("small-icon-tile");
        Label number = label(Integer.toString(value), "stat-number");
        getChildren().addAll(row(6, label(title, "card-kicker"), spacer(), iconBox), number, label(detail, "muted"));
        var counter = new SimpleDoubleProperty(value * .73);
        counter.addListener((obs, old, current) -> number.setText(Integer.toString((int) Math.round(current.doubleValue()))));
        new Timeline(new KeyFrame(Duration.millis(340), new KeyValue(counter, value))).play();
    }
}
