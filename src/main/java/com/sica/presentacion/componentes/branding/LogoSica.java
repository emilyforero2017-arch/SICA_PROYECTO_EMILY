package com.sica.presentacion.componentes.branding;

import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;

public final class LogoSica extends StackPane {
    private static final String CLOVER = """
            M 49 46 C 38 47 23 44 18 34 C 16 30 17 26 18 23
            C 17 13 22 8 29 8 C 40 7 47 17 50 27
            C 53 17 60 7 71 8 C 78 8 83 13 82 23 C 84 28 84 31 82 35
            C 77 44 63 47 53 46 C 62 47 76 50 81 59 C 86 68 81 75 76 77
            C 72 83 66 84 60 81 C 56 79 53 75 51 70
            C 51 81 57 88 67 92 C 70 93 68 98 65 97
            C 53 92 47 85 46 74 C 40 86 27 84 23 77
            C 15 74 15 65 19 59 C 24 51 38 47 49 46 Z
            """;

    public LogoSica(double size) {
        getStyleClass().add("sica-logo");
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        getChildren().add(mark(size * .86, Color.web("#111711")));
        setAccessibleText("SICA");
    }

    public static Group mark(double size, Paint fill) {
        SVGPath path = new SVGPath();
        path.setContent(CLOVER);
        path.setFill(fill);
        Group scaled = new Group(path);
        scaled.setScaleX(size / 100);
        scaled.setScaleY(size / 100);
        return new Group(scaled);
    }
}
