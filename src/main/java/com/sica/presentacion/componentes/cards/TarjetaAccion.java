package com.sica.presentacion.componentes.cards;

import com.sica.presentacion.animaciones.Motion;
import com.sica.presentacion.componentes.branding.Icons;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import static com.sica.presentacion.componentes.ElementosUi.*;

public final class TarjetaAccion extends Button {
    public TarjetaAccion(String title, String description, String icon, String tone, Runnable action) {
        getStyleClass().addAll("action-card", "surface-" + tone);
        getStyleClass().add(tone.equals("deep") ? "action-deep" : "action-light");
        setMaxWidth(Double.MAX_VALUE);
        setMinWidth(0);
        setAccessibleText(title + ". " + description);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        StackPane iconBox = new StackPane(Icons.icon(icon, 19));
        iconBox.getStyleClass().add("action-icon");
        StackPane arrow = new StackPane(Icons.icon("up-right", 17));
        arrow.getStyleClass().add("action-arrow");
        Label copy = label(description, "action-description");
        copy.setWrapText(true);
        copy.setMaxWidth(305);
        copy.setMinHeight(USE_PREF_SIZE);
        VBox front = new VBox(4, row(8, iconBox, spacer(), arrow), spacer(),
                label(title, "action-title"), copy);
        front.getStyleClass().add("action-content");
        front.setAlignment(Pos.TOP_LEFT);
        var content = new StackPane(new HeroArt(icon, tone.equals("deep")), front);
        content.setMouseTransparent(true);
        content.prefWidthProperty().bind(widthProperty());
        content.prefHeightProperty().bind(heightProperty());
        setGraphic(content);
        setOnAction(event -> action.run());
        Motion.hover(this);
    }
}
