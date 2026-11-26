package com.sica.presentacion.componentes.dialogs;

import com.sica.presentacion.animaciones.Motion;
import javafx.animation.*;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import static com.sica.presentacion.componentes.ElementosUi.*;

public final class PanelLateral {
    private final StackPane root;
    private StackPane overlay;
    private boolean closing;

    public PanelLateral(StackPane root) { this.root = root; }

    public void open(String title, Node content) {
        Node origin = overlay == null ? root.getScene().getFocusOwner() : (Node) overlay.getProperties().get("origin");
        if (overlay != null) root.getChildren().remove(overlay);
        closing = false;
        Region veil = new Region();
        veil.getStyleClass().add("drawer-veil");
        var close = iconButton("Cerrar panel", "close", this::close);
        var scroll = new ScrollPane(content);
        scroll.getStyleClass().add("drawer-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        VBox body = new VBox(16, heading(title, close), rule(), scroll);
        body.getStyleClass().add("sica-drawer");
        body.setMaxWidth(405);
        overlay = new StackPane(veil, body);
        overlay.setAlignment(Pos.CENTER_RIGHT);
        overlay.getProperties().put("origin", origin);
        overlay.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                close();
                event.consume();
            }
        });
        veil.setOnMouseClicked(event -> close());
        root.getChildren().add(overlay);
        close.requestFocus();
        Motion.reveal(veil, 0, 0);
        TranslateTransition slide = new TranslateTransition(Duration.millis(245), body);
        slide.setFromX(410);
        slide.setToX(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        slide.play();
    }

    public void close() {
        if (overlay == null || closing) return;
        closing = true;
        StackPane current = overlay;
        Node body = current.getChildren().getLast();
        TranslateTransition slide = new TranslateTransition(Duration.millis(205), body);
        slide.setToX(410);
        FadeTransition fade = new FadeTransition(Duration.millis(190), current.getChildren().getFirst());
        fade.setToValue(0);
        ParallelTransition animation = new ParallelTransition(slide, fade);
        animation.setOnFinished(event -> {
            root.getChildren().remove(current);
            if (overlay == current) {
                overlay = null;
                closing = false;
                if (current.getProperties().get("origin") instanceof Node origin && origin.getScene() != null) {
                    origin.requestFocus();
                }
            }
        });
        animation.play();
    }
}
