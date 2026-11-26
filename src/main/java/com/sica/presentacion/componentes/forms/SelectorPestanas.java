package com.sica.presentacion.componentes.forms;

import javafx.animation.Interpolator;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.List;
import java.util.ArrayList;
import java.util.function.IntConsumer;

public final class SelectorPestanas extends StackPane {
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private final List<ToggleButton> options = new ArrayList<>();

    public SelectorPestanas(List<String> labels, IntConsumer onSelect) {
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("segmented");
        setPrefWidth(440);
        setMaxWidth(465);
        Region thumb = new Region();
        thumb.getStyleClass().add("segment-thumb");
        thumb.setMouseTransparent(true);
        thumb.prefWidthProperty().bind(javafx.beans.binding.Bindings.max(0, widthProperty().subtract(8)).divide(labels.size()));
        thumb.maxWidthProperty().bind(thumb.prefWidthProperty());
        var index = new SimpleDoubleProperty(0);
        thumb.translateXProperty().bind(thumb.prefWidthProperty().multiply(index));

        ToggleGroup group = new ToggleGroup();
        HBox buttons = new HBox();
        for (int i = 0; i < labels.size(); i++) {
            int current = i;
            ToggleButton button = new ToggleButton(labels.get(i));
            button.setToggleGroup(group);
            button.getStyleClass().add("segment-option");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setMinWidth(0);
            button.prefWidthProperty().bind(thumb.prefWidthProperty());
            button.setSelected(i == 0);
            button.pseudoClassStateChanged(ACTIVE, i == 0);
            button.setOnAction(event -> {
                group.selectToggle(button);
                buttons.getChildren().forEach(node -> node.pseudoClassStateChanged(ACTIVE, node == button));
                new Timeline(new KeyFrame(Duration.millis(190),
                        new KeyValue(index, current, Interpolator.EASE_BOTH))).play();
                onSelect.accept(current);
            });
            buttons.getChildren().add(button);
            options.add(button);
        }
        getChildren().addAll(thumb, buttons);
    }

    public void select(int index) { options.get(index).fire(); }
}
