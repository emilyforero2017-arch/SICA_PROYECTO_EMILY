package com.sica.presentacion.componentes.branding;

import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

public final class Icons {
    private Icons() {}

    public static StackPane icon(String name, double size) {
        SVGPath path = new SVGPath();
        path.setContent(switch (name) {
            case "home" -> "M3 10L12 3L21 10V20H15V14H9V20H3Z";
            case "scan" -> "M8 3H5Q3 3 3 5V8M16 3H19Q21 3 21 5V8M21 16V19Q21 21 19 21H16M8 21H5Q3 21 3 19V16M7 12H17";
            case "file" -> "M6 3H14L19 8V20Q19 21 18 21H6Q5 21 5 20V4Q5 3 6 3ZM14 3V8H19M8 12H16M8 16H14";
            case "calendar" -> "M4 5H20Q21 5 21 6V20Q21 21 20 21H4Q3 21 3 20V6Q3 5 4 5ZM3 10H21M7 3V7M17 3V7M7 14H8M12 14H13M17 14H18M7 17H8M12 17H13";
            case "settings" -> "M9 3H15L16 6L19 7L21 10L19 12L21 14L19 17L16 18L15 21H9L8 18L5 17L3 14L5 12L3 10L5 7L8 6Z M15 12A3 3 0 1 0 9 12A3 3 0 1 0 15 12";
            case "bell" -> "M5 17H19L17 14V9A5 5 0 0 0 7 9V14ZM10 20Q12 22 14 20";
            case "user" -> "M16 8A4 4 0 1 0 8 8A4 4 0 1 0 16 8M4 21V19Q4 15 12 15Q20 15 20 19V21";
            case "users" -> "M13 8A4 4 0 1 0 5 8A4 4 0 1 0 13 8M2 21V19Q2 15 9 15Q16 15 16 19V21M16 4Q22 5 18 11M18 15Q22 16 22 19V21";
            case "building" -> "M4 21H21M6 21V3H17V21M17 9H20V21M9 7H10M13 7H14M9 11H10M13 11H14M9 15H10M13 15H14";
            case "search" -> "M15 10A5 5 0 1 0 5 10A5 5 0 1 0 15 10M14 14L20 20";
            case "shield" -> "M12 3L20 6V12Q20 18 12 21Q4 18 4 12V6ZM8 12L11 15L16 9";
            case "warning" -> "M11 4Q12 2 13 4L22 19Q23 21 20 21H4Q1 21 2 19ZM12 9V14M12 17V17.2";
            case "audit" -> "M4 5H20V20H4ZM8 3V7M16 3V7M8 11H16M8 15H14";
            case "key" -> "M12 8A5 5 0 1 0 7 13H11L19 21L22 18L19 15L17 17M7 8V8.2";
            case "check-circle" -> "M21 12A9 9 0 1 0 3 12A9 9 0 1 0 21 12M8 12L11 15L16 9";
            case "check" -> "M5 12L10 17L19 7";
            case "clock" -> "M21 12A9 9 0 1 0 3 12A9 9 0 1 0 21 12M12 7V12L15 14";
            case "logout" -> "M10 4H4V20H10M9 12H21M17 8L21 12L17 16";
            case "enter" -> "M14 4H20V20H14M3 12H15M11 8L15 12L11 16";
            case "arrow" -> "M4 12H20M14 6L20 12L14 18";
            case "up-right" -> "M6 18L18 6M9 6H18V15";
            case "right" -> "M9 5L16 12L9 19";
            case "left" -> "M15 5L8 12L15 19";
            case "down" -> "M5 9L12 16L19 9";
            case "plus" -> "M12 5V19M5 12H19";
            case "close" -> "M6 6L18 18M18 6L6 18";
            case "panel" -> "M4 5H20V19H4ZM9 5V19";
            case "chart" -> "M4 4V20H21M8 15V11M13 15V7M18 15V4";
            case "id" -> "M3 5H21V19H3ZM8 10A2 2 0 1 0 12 10A2 2 0 1 0 8 10M6 16Q10 12 14 16M16 9H19M16 13H19";
            case "ticket" -> "M5 5H19Q21 5 21 7V9A3 3 0 0 0 21 15V17Q21 19 19 19H5Q3 19 3 17V15A3 3 0 0 0 3 9V7Q3 5 5 5ZM15 7V8M15 11V13M15 16V17";
            case "eye" -> "M2 12Q6 5 12 5Q18 5 22 12Q18 19 12 19Q6 19 2 12ZM15 12A3 3 0 1 0 9 12A3 3 0 1 0 15 12";
            case "sun" -> "M17 12A5 5 0 1 0 7 12A5 5 0 1 0 17 12M12 2V4M12 20V22M2 12H4M20 12H22M5 5L7 7M17 17L19 19M5 19L7 17M17 7L19 5";
            case "moon" -> "M20 16A9 9 0 0 1 8 4A9 9 0 1 0 20 16Z";
            case "dots" -> "M5 12V12.1M12 12V12.1M19 12V12.1";
            default -> throw new IllegalArgumentException("Icono desconocido: " + name);
        });
        path.getStyleClass().add("icon-line");
        Group inner = new Group(path);
        inner.setScaleX(size / 24);
        inner.setScaleY(size / 24);
        StackPane box = new StackPane(new Group(inner));
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }
}
