package com.sica.presentacion.animaciones;

import javafx.animation.*;
import javafx.scene.Node;
import javafx.util.Duration;

import java.util.List;

public final class Motion {
    private Motion() {}

    public static ParallelTransition reveal(Node node, double offset, double delay) {
        node.setOpacity(0);
        node.setTranslateY(offset);
        FadeTransition fade = new FadeTransition(Duration.millis(210), node);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(250), node);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        ParallelTransition animation = new ParallelTransition(fade, slide);
        animation.setDelay(Duration.millis(delay));
        animation.play();
        return animation;
    }

    public static void stagger(List<? extends Node> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            reveal(nodes.get(i), 8, Math.min(i * 35, 170));
        }
    }

    public static void hover(Node node) {
        ScaleTransition hover = new ScaleTransition(Duration.millis(160), node);
        hover.setInterpolator(Interpolator.EASE_BOTH);
        node.hoverProperty().addListener((observable, previous, active) -> {
            hover.stop();
            hover.setToX(active ? 1.012 : 1);
            hover.setToY(active ? 1.012 : 1);
            hover.playFromStart();
        });
    }

    public static void confirm(Node node) {
        ScaleTransition scale = new ScaleTransition(Duration.millis(175), node);
        scale.setFromX(.78);
        scale.setFromY(.78);
        scale.setToX(1);
        scale.setToY(1);
        FadeTransition fade = new FadeTransition(Duration.millis(160), node);
        fade.setFromValue(.35);
        fade.setToValue(1);
        new SequentialTransition(new ParallelTransition(scale, fade)).play();
    }
}
