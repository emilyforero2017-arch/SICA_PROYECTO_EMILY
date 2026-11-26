package com.sica.presentacion.componentes.cards;

import com.sica.presentacion.animaciones.Motion;
import com.sica.presentacion.componentes.branding.Icons;
import com.sica.presentacion.componentes.branding.LogoSica;
import com.sica.presentacion.integracion.SicaData;
import com.sica.presentacion.integracion.TemporaryPass;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.util.Locale;

import static com.sica.presentacion.componentes.ElementosUi.*;

public final class TicketPase extends VBox {
    private final Section stub;
    private final StackPane stamp;

    public TicketPase(TemporaryPass pass, LocalDateTime now) {
        getStyleClass().add("pass-ticket");
        setFillWidth(true);
        setMinWidth(275);
        setPrefWidth(345);
        setMaxWidth(Double.MAX_VALUE);
        setAccessibleText("Comprobante " + pass.code() + " de " + pass.person().name());

        var identity = row(8, new LogoSica(32), label("SICA", "ticket-wordmark"));
        var folio = label(pass.code(), "ticket-folio");
        var masthead = row(10, identity, spacer(), folio);
        var visitor = label(pass.person().name(), "ticket-person");
        visitor.setWrapText(true);
        stamp = new StackPane(Icons.icon("check", 19));
        stamp.getStyleClass().add("ticket-stamp");
        var info = new VBox(3, label("Pase temporal", "ticket-caption"), visitor,
                label(pass.person().company() + "  ·  Doc. " + pass.person().document(), "muted"));
        HBox.setHgrow(info, Priority.ALWAYS);
        String state = switch (pass.status()) {
            case INSIDE, CLOSED, LOGGED -> "Utilizado";
            case EXPIRED -> "Vencido";
            default -> now.isAfter(pass.end()) ? "Vencido" : now.isBefore(pass.start()) ? "Próximo" : "Vigente";
        };
        Label stateBadge = label(state, "ticket-state");
        stateBadge.getStyleClass().add(state.equals("Vigente") ? "ticket-active" : "ticket-inactive");
        VBox upperContent = new VBox(14, masthead, row(10, info, stamp), row(10, stateBadge));
        upperContent.getStyleClass().add("ticket-upper-content");
        Section upper = new Section(true, upperContent);
        upper.getStyleClass().add("ticket-upper");

        HBox range = row(8, timeBlock("Válido desde", pass.start()), spacer(), timeBlock("Válido hasta", pass.end()));
        VBox stubContent = new VBox(10, range, label("Aprobó " + pass.reviewer(), "ticket-reviewer"));
        stubContent.getStyleClass().add("ticket-stub-content");
        stub = new Section(false, stubContent);
        stub.getStyleClass().add("ticket-stub");
        var perforation = new Line();
        perforation.setStroke(Color.web("#8BA790"));
        perforation.getStrokeDashArray().addAll(3.0, 4.5);
        perforation.endXProperty().bind(stub.widthProperty().subtract(30));
        perforation.setMouseTransparent(true);
        StackPane.setAlignment(perforation, Pos.TOP_CENTER);
        stub.getChildren().add(perforation);
        getChildren().addAll(upper, stub);
    }

    public void animateTear(Runnable onDetached) {
        var reveal = new PauseTransition(Duration.millis(245));
        reveal.setOnFinished(event -> Motion.confirm(stamp));
        var pull = new TranslateTransition(Duration.millis(175), stub);
        pull.setToY(17);
        pull.setInterpolator(Interpolator.EASE_IN);
        var settle = new TranslateTransition(Duration.millis(160), stub);
        settle.setToY(7);
        settle.setInterpolator(Interpolator.EASE_OUT);
        var tilt = new RotateTransition(Duration.millis(155), stub);
        tilt.setToAngle(-1.8);
        var straighten = new RotateTransition(Duration.millis(160), stub);
        straighten.setToAngle(0);
        var tear = new SequentialTransition(reveal, new ParallelTransition(pull, tilt),
                new ParallelTransition(settle, straighten));
        tear.setOnFinished(event -> onDetached.run());
        tear.play();
    }

    private VBox timeBlock(String caption, LocalDateTime value) {
        return new VBox(1, label(caption, "ticket-caption"),
                label(value.toLocalDate().format(SicaData.DATE), "ticket-date"),
                label(value.toLocalTime().format(SicaData.TIME), "ticket-hour"));
    }

    private static final class Section extends StackPane {
        private final SVGPath paper = new SVGPath();
        private final boolean upper;

        Section(boolean upper, Node content) {
            super(content);
            this.upper = upper;
            setClip(paper);
            widthProperty().addListener((obs, old, width) -> shape());
            heightProperty().addListener((obs, old, height) -> shape());
        }

        private void shape() {
            double w = getWidth();
            double h = getHeight();
            if (w < 42 || h < 38) return;
            String path = upper
                    ? "M 0 18 Q 0 0 18 0 H %.2f Q %.2f 0 %.2f 18 V %.2f A 8 8 0 0 0 %.2f %.2f H 8 A 8 8 0 0 0 0 %.2f Z"
                    : "M 0 8 A 8 8 0 0 0 8 0 H %.2f A 8 8 0 0 0 %.2f 8 V %.2f Q %.2f %.2f %.2f %.2f H 18 Q 0 %.2f 0 %.2f Z";
            paper.setContent(upper
                    ? String.format(Locale.ROOT, path, w - 18, w, w, h - 8, w - 8, h, h - 8)
                    : String.format(Locale.ROOT, path, w - 8, w, h - 18, w, h, w - 18, h, h, h - 18));
        }
    }
}
