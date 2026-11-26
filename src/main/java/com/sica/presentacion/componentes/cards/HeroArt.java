package com.sica.presentacion.componentes.cards;

import com.sica.presentacion.componentes.branding.LogoSica;
import javafx.scene.Group;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;

final class HeroArt extends Pane {
    HeroArt(String kind, boolean dark) {
        setMouseTransparent(true);
        setMinSize(0, 0);
        var clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        Color line = Color.web(dark ? "#77ABA1" : "#63944A");
        Group rings = new Group();
        for (int index = 0; index < 6; index++) {
            Circle ring = new Circle(176, 154, 41 + index * 19);
            ring.setFill(Color.TRANSPARENT);
            ring.setStroke(line.deriveColor(0, 1, 1, dark ? .26 : .44));
            ring.setStrokeWidth(1.05);
            rings.getChildren().add(ring);
        }
        Color foreground = Color.web(dark ? "#C3E89F" : "#265932");
        Color field = Color.web(dark ? "#166568" : "#D6F0BA");
        Group illustration = switch (kind) {
            case "scan" -> identity(field, foreground);
            case "file" -> document(field, foreground);
            default -> LogoSica.mark(115, foreground.deriveColor(0, 1, 1, .44));
        };
        if (!kind.equals("scan") && !kind.equals("file")) {
            illustration.setTranslateX(110);
            illustration.setTranslateY(26);
        }
        Group composition = new Group(rings, illustration);
        composition.setManaged(false);
        widthProperty().addListener((obs, before, now) -> position(composition));
        heightProperty().addListener((obs, before, now) -> position(composition));
        getChildren().add(composition);
    }

    private void position(Group content) {
        double scale = Math.min(1.17, Math.max(.69, getWidth() / 455));
        content.getTransforms().setAll(new javafx.scene.transform.Scale(scale, scale, 0, 0));
        content.setLayoutX(getWidth() - 224 * scale);
        content.setLayoutY(getHeight() - 159 * scale);
        content.setOpacity(Math.min(1, Math.max(.28, (getWidth() - 190) / 155)));
    }

    private static Group identity(Color field, Color ink) {
        Group badge = new Group(
                box(9, 12, 126, 77, 13, ink.deriveColor(0, 1, 1, .09), Color.TRANSPARENT),
                box(0, 0, 126, 77, 13, field, ink),
                box(13, 16, 39, 43, 7, Color.TRANSPARENT, ink),
                line("M 28 31 A 5 5 0 1 1 38 31 A 5 5 0 1 1 28 31 M 23 48 Q 33 38 43 48", ink),
                line("M 64 24 H 108 M 64 37 H 101 M 64 49 H 90", ink),
                line("M -12 13 V -3 Q -12 -11 -4 -11 H 12 M 113 -11 H 130 Q 138 -11 138 -3 V 13 M 138 62 V 81 Q 138 88 130 88 H 113 M 12 88 H -4 Q -12 88 -12 81 V 62", ink));
        badge.setRotate(-11);
        badge.setTranslateX(46);
        badge.setTranslateY(41);
        return badge;
    }

    private static Group document(Color field, Color ink) {
        Group papers = new Group(
                box(-11, -10, 83, 100, 10, Color.web("#115D5D"), ink.deriveColor(0, 1, 1, .55)),
                box(0, 0, 83, 100, 10, field, ink),
                line("M 16 24 H 63 M 16 37 H 56 M 16 50 H 63 M 16 63 H 44", ink));
        Circle stamp = new Circle(71, 84, 20, ink);
        papers.getChildren().addAll(stamp, line("M 61 84 L 68 91 L 81 77", Color.web("#115D5D")));
        papers.setRotate(13);
        papers.setTranslateX(85);
        papers.setTranslateY(24);
        return papers;
    }

    private static Rectangle box(double x, double y, double w, double h, double radius, Color fill, Color stroke) {
        Rectangle rectangle = new Rectangle(x, y, w, h);
        rectangle.setArcWidth(radius);
        rectangle.setArcHeight(radius);
        rectangle.setFill(fill);
        rectangle.setStroke(stroke);
        rectangle.setStrokeWidth(1.2);
        return rectangle;
    }

    private static SVGPath line(String content, Color ink) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        path.setFill(null);
        path.setStroke(ink);
        path.setStrokeWidth(1.45);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        return path;
    }
}
