package com.sica.presentacion.componentes.navigation;

import com.sica.presentacion.componentes.branding.Portrait;
import com.sica.presentacion.componentes.branding.LogoSica;
import com.sica.presentacion.integracion.SicaData.Profile;
import com.sica.presentacion.integracion.SicaData.Route;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

import static com.sica.presentacion.componentes.ElementosUi.*;

public final class MenuLateral extends VBox {
    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current");
    private static final double NARROW = 66;
    private static final double WIDE = 208;
    private static final double STEP = 50;
    private final VBox menu = new VBox(6);
    private final Region selection = new Region();
    private final Button settings;
    private final Button exit;
    private final Button account;
    private final Button expand;
    private final Map<Route, Button> buttons = new EnumMap<>(Route.class);
    private final Consumer<Route> navigate;
    private Timeline resize;
    private Timeline slide;
    private boolean expanded;

    public MenuLateral(Consumer<Route> navigate, Runnable onSettings, Runnable onExit, Runnable onProfile) {
        super(12);
        this.navigate = navigate;
        getStyleClass().add("sica-sidebar");
        setAlignment(Pos.TOP_CENTER);
        setMinWidth(USE_PREF_SIZE);
        setPrefWidth(NARROW);
        setMaxWidth(USE_PREF_SIZE);

        Button brand = new Button("", new LogoSica(42));
        brand.getStyleClass().add("brand-button");
        brand.setAccessibleText("SICA · Inicio");
        brand.setOnAction(event -> navigate.accept(Route.HOME));

        expand = iconButton("Expandir o contraer navegación", "panel", () -> setExpanded(!expanded));
        expand.getStyleClass().add("menu-expander");
        selection.getStyleClass().add("nav-selection");
        selection.setMouseTransparent(true);
        selection.setMaxHeight(44);
        StackPane nav = new StackPane(selection, menu);
        nav.setAlignment(Pos.TOP_LEFT);
        StackPane.setAlignment(selection, Pos.TOP_LEFT);

        settings = navButton("Configuración", "settings", onSettings);
        exit = navButton("Cerrar sesión", "logout", onExit);
        account = new Button();
        account.getStyleClass().add("nav-button");
        account.setAccessibleText("Información del usuario");
        tooltip(account, "Información del usuario");
        account.setOnAction(event -> onProfile.run());
        account.setMaxWidth(Double.MAX_VALUE);
        getChildren().addAll(brand, expand, rule(), nav, spacer(), rule(), settings, exit, account);
    }

    public void setProfile(Profile profile, String nombre, int retrato, byte[] foto) {
        menu.getChildren().clear();
        buttons.clear();
        for (Route route : profile.routes) {
            Button button = navButton(route.title, route.icon, () -> navigate.accept(route));
            buttons.put(route, button);
            menu.getChildren().add(button);
        }
        var portrait = new Portrait(nombre, retrato, 32, foto);
        account.setGraphic(portrait);
        account.setUserData(nombre);
        refreshLabels();
        selection.setTranslateY(0);
    }

    public void select(Route target) {
        int index = menu.getChildren().indexOf(buttons.get(target));
        if (index < 0) return;
        buttons.forEach((route, button) -> button.pseudoClassStateChanged(CURRENT, route == target));
        if (slide != null) slide.stop();
        slide = new Timeline(new KeyFrame(Duration.millis(200),
                new KeyValue(selection.translateYProperty(), index * STEP, Interpolator.EASE_BOTH)));
        slide.play();
    }

    public void setCompactMode(boolean compact) {
        expand.setDisable(compact);
        if (compact && expanded) setExpanded(false);
    }

    private Button navButton(String text, String icon, Runnable action) {
        Button button = button(expanded ? text : "", icon, "nav-button", action);
        button.setUserData(text);
        button.setAccessibleText(text);
        button.setMaxWidth(Double.MAX_VALUE);
        tooltip(button, text);
        return button;
    }

    private void setExpanded(boolean value) {
        expanded = value;
        refreshLabels();
        if (resize != null) resize.stop();
        resize = new Timeline(new KeyFrame(Duration.millis(235),
                new KeyValue(prefWidthProperty(), expanded ? WIDE : NARROW, Interpolator.EASE_BOTH)));
        resize.play();
    }

    private void refreshLabels() {
        buttons.values().forEach(this::refreshLabel);
        refreshLabel(settings);
        refreshLabel(exit);
        refreshLabel(account);
    }

    private void refreshLabel(Button button) {
        button.setAlignment(expanded ? Pos.CENTER_LEFT : Pos.CENTER);
        button.setText(expanded ? (String) button.getUserData() : "");
    }
}
