package com.sica.presentacion.componentes.navigation;

import com.sica.presentacion.componentes.branding.Portrait;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import static com.sica.presentacion.componentes.ElementosUi.*;

public final class Cabecera extends HBox {
    private final Button account;
    private final Label role = label("", "demo-label");

    public Cabecera(Runnable onNotifications, Runnable onAccount) {
        super(14);
        setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        getStyleClass().add("sica-header");

        HBox identity = row(10, label("SICA", "wordmark"));
        Region divider = new Region();
        divider.getStyleClass().add("header-divider");
        Label site = label("Zona ACME", "site-name");
        Button notifications = iconButton("Notificaciones", "bell", onNotifications);
        notifications.getStyleClass().add("header-icon");
        account = button("", null, "header-account", onAccount);
        account.setAccessibleText("Información del usuario");
        tooltip(account, "Información del usuario");
        getChildren().addAll(identity, divider, site, spacer(), role, notifications, account);
    }

    public void setProfile(String nombre, String titulo, int retrato, byte[] foto) {
        role.setText(titulo);
        account.setGraphic(new Portrait(nombre, retrato, 30, foto));
    }
}
