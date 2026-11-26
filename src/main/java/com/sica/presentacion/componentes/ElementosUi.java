package com.sica.presentacion.componentes;

import com.sica.presentacion.componentes.branding.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public final class ElementosUi {
    private ElementosUi() {}

    public static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    public static Button button(String text, String icon, String style, Runnable action) {
        Button button = new Button(text);
        if (icon != null) button.setGraphic(Icons.icon(icon, 17));
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.run());
        return button;
    }

    public static Button iconButton(String name, String icon, Runnable action) {
        Button button = button("", icon, "icon-button", action);
        button.setAccessibleText(name);
        tooltip(button, name);
        return button;
    }

    public static void tooltip(Button button, String text) {
        Tooltip tip = new Tooltip(text);
        tip.setShowDelay(Duration.millis(350));
        button.setTooltip(tip);
    }

    public static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    public static HBox row(double gap, Node... children) {
        HBox row = new HBox(gap, children);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    public static VBox textBlock(String title, String detail) {
        return new VBox(2, label(title, "row-title"), label(detail, "muted"));
    }

    public static HBox heading(String title, Node trailing) {
        HBox row = row(10, label(title, "card-title"));
        if (trailing != null) row.getChildren().addAll(spacer(), trailing);
        return row;
    }

    public static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("rule");
        return rule;
    }
}
