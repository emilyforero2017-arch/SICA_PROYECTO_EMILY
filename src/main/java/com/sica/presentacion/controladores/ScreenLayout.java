package com.sica.presentacion.controladores;

import com.sica.presentacion.inicio.AplicacionEscritorio;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;

import java.io.IOException;

final class ScreenLayout {
    private final FXMLLoader loader;
    final Parent view;

    ScreenLayout(String name) {
        loader = new FXMLLoader(AplicacionEscritorio.class.getResource(AplicacionEscritorio.RESOURCES + "fxml/" + name + ".fxml"));
        try {
            view = loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("No fue posible cargar " + name, e);
        }
    }

    VBox slot(String name) { return (VBox) loader.getNamespace().get(name); }
    void add(String name, Node... nodes) { slot(name).getChildren().addAll(nodes); }
}
