package com.sica.presentacion.componentes.forms;

import com.sica.presentacion.componentes.branding.Icons;
import javafx.scene.control.TextField;

import java.util.function.Consumer;

public final class CampoBusqueda extends javafx.scene.layout.HBox {
    private final TextField field = new TextField();

    public CampoBusqueda(String prompt, Consumer<String> listener) {
        setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        setSpacing(7);
        getStyleClass().add("search-field");
        field.setPromptText(prompt);
        field.setAccessibleText(prompt);
        var focusWithin = javafx.css.PseudoClass.getPseudoClass("focus-within");
        field.focusedProperty().addListener((obs, previous, focused) -> pseudoClassStateChanged(focusWithin, focused));
        field.textProperty().addListener((obs, previous, text) -> listener.accept(text.strip()));
        field.setMaxWidth(Double.MAX_VALUE);
        setHgrow(field, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(Icons.icon("search", 17), field);
        setMaxWidth(360);
    }

    public void onSubmit(Runnable action) { field.setOnAction(event -> action.run()); }
    public javafx.beans.property.ReadOnlyStringProperty queryProperty() { return field.textProperty(); }
    public String query() { return field.getText().strip(); }
    public void clear() { field.clear(); }
}
