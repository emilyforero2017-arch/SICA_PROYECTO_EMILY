package com.sica.presentacion.componentes.charts;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.List;

import static com.sica.presentacion.componentes.ElementosUi.label;

public final class GraficoBarras extends GridPane {
    public GraficoBarras(List<String> labels, int[] upper, int[] lower) {
        setHgap(5);
        setVgap(7);
        getStyleClass().add("sica-chart");
        int max = 1;
        for (int i = 0; i < labels.size(); i++) max = Math.max(max, upper[i] + lower[i]);
        for (int index = 0; index < labels.size(); index++) {
            Bar bar = new Bar(upper[index], lower[index], max);
            StackPane barCell = new StackPane(bar);
            barCell.setMinWidth(0);
            GridPane.setHgrow(barCell, Priority.ALWAYS);
            StackPane caption = new StackPane(label(labels.get(index), "chart-label"));
            caption.setAlignment(Pos.CENTER);
            add(barCell, index, 0);
            add(caption, index, 1);
            Tooltip.install(barCell, new Tooltip(labels.get(index) + " · "
                    + upper[index] + " entradas · " + lower[index] + " salidas"));
        }
    }

    private static final class Bar extends Region {
        private static final Color TRACK = Color.web("#EDF3E9");
        private static final Color ENTRY = Color.web("#B6DD91");
        private static final Color EXIT = Color.web("#115D5D");
        private final Canvas canvas = new Canvas();
        private final SimpleDoubleProperty progress = new SimpleDoubleProperty(0);
        private final int entry;
        private final int exit;
        private final int max;

        Bar(int entry, int exit, int max) {
            this.entry = entry;
            this.exit = exit;
            this.max = max;
            setMinWidth(12);
            setPrefWidth(23);
            setMaxWidth(26);
            setMinHeight(102);
            setPrefHeight(114);
            canvas.setManaged(false);
            getChildren().add(canvas);
            progress.addListener((obs, previous, value) -> draw());
            new Timeline(new KeyFrame(Duration.millis(340),
                    new KeyValue(progress, 1, Interpolator.EASE_OUT))).play();
        }

        @Override protected void layoutChildren() {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            draw();
        }

        private void draw() {
            var gc = canvas.getGraphicsContext2D();
            double width = canvas.getWidth();
            double height = canvas.getHeight();
            double combined = height * (entry + exit) / max * progress.get();
            double dark = height * exit / max * progress.get();
            gc.clearRect(0, 0, width, height);
            gc.setFill(TRACK);
            gc.fillRoundRect(0, 0, width, height, width, width);
            gc.setFill(ENTRY);
            gc.fillRoundRect(0, height - combined, width, combined, width, width);
            gc.setFill(EXIT);
            gc.fillRoundRect(0, height - dark, width, dark, width, width);
        }
    }
}
