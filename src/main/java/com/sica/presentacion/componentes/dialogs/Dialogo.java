package com.sica.presentacion.componentes.dialogs;

import com.sica.presentacion.animaciones.Motion;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.sica.presentacion.componentes.ElementosUi.*;

public final class Dialogo {
    private Dialogo() {}

    @FunctionalInterface
    public interface Submit {
        void accept(DialogPane pane, Runnable done, Consumer<String> error);
    }

    public static void openAsync(Window owner, String title, String description, Node content,
            String confirmLabel, Submit action) {
        show(owner, title, description, content, confirmLabel, action);
    }

    public static void open(Window owner, String title, String description, Node content,
            String confirmLabel, BooleanSupplier confirm) {
        show(owner, title, description, content, confirmLabel, (pane, done, error) -> {
            if (confirm.getAsBoolean()) done.run();
        });
    }

    private static void show(Window owner, String title, String description, Node content,
            String confirmLabel, Submit action) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.setTitle(title);
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("sica-dialog");
        pane.getStylesheets().addAll(owner.getScene().getStylesheets());
        pane.setPrefWidth(490);
        Label copy = label(description, "muted");
        copy.setWrapText(true);
        Label error = label("", "form-error");
        error.setWrapText(true);
        error.visibleProperty().bind(error.textProperty().isNotEmpty());
        error.managedProperty().bind(error.visibleProperty());
        VBox body = new VBox(14, label(title, "dialog-title"), copy, content, error);
        pane.setContent(body);
        ButtonType accept = new ButtonType(confirmLabel, ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(ButtonType.CANCEL, accept);
        Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
        cancel.setText("Cancelar");
        cancel.getStyleClass().add("secondary-button");
        Button submit = (Button) pane.lookupButton(accept);
        submit.getStyleClass().add("primary-button");
        submit.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            error.setText("");
            action.accept(pane, () -> {
                dialog.setResult(accept);
                dialog.close();
            }, error::setText);
        });
        dialog.setOnShown(event -> {
            pane.getScene().setFill(Color.TRANSPARENT);
            Motion.reveal(body, 6, 0);
        });
        dialog.show();
    }
}
