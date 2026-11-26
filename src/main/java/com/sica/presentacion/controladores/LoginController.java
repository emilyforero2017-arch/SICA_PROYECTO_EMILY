package com.sica.presentacion.controladores;

import com.sica.presentacion.animaciones.Motion;
import com.sica.presentacion.inicio.AplicacionEscritorio;
import com.sica.presentacion.componentes.branding.Icons;
import com.sica.presentacion.componentes.branding.LogoSica;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.effect.BoxBlur;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.util.function.BiConsumer;

import static com.sica.presentacion.componentes.ElementosUi.*;
import static com.sica.presentacion.controladores.ModuleController.*;

public final class LoginController {
    private static final PseudoClass COMPACT = PseudoClass.getPseudoClass("compact");
    @FXML private StackPane root;
    @FXML private ImageView forest;
    @FXML private HBox masthead;
    @FXML private HBox footer;
    @FXML private VBox editorial;
    @FXML private StackPane glass;
    @FXML private VBox form;

    private BiConsumer<String, char[]> onEnter;
    private javafx.scene.control.Button enter;
    private boolean ocupado;
    private TextField email;
    private PasswordField password;
    private ToggleButton eye;
    private Label error;

    @FXML private void initialize() {
        setBackdrop();
        var identity = row(11, new LogoSica(44), label("SICA", "login-wordmark"));
        var site = new StackPane(label("ZONA ACME", "login-site-label"));
        site.getStyleClass().add("login-site");
        site.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        addFrost(site, 34, 13, 0, "login-site-sheen");
        masthead.getChildren().setAll(identity, spacer(), site);
        var lead = label("Cada acceso,", "login-headline");
        var emphasis = label("en buenas manos.", "login-headline-accent");
        lead.setWrapText(true);
        emphasis.setWrapText(true);
        editorial.getChildren().setAll(label("CONTROL DE ACCESOS", "login-caption"), new VBox(0, lead, emphasis));
        footer.getChildren().setAll(label("SICA  ·  Sistema de control de accesos", "login-footer"));
        buildForm();
    }

    public void setOnEnter(BiConsumer<String, char[]> action) { onEnter = action; }

    public void show() {
        eye.setSelected(false);
        email.clear();
        password.clear();
        estado("", false);
        email.requestFocus();
        Motion.reveal(form, 9, 0);
    }

    public void estado(String mensaje, boolean procesando) {
        ocupado = procesando;
        error.setText(mensaje);
        enter.setDisable(procesando);
        email.setDisable(procesando);
        password.setDisable(procesando);
        eye.setDisable(procesando);
        if (!procesando && !mensaje.isEmpty()) password.requestFocus();
    }

    private void buildForm() {
        email = field("Correo", "");
        email.setPromptText("nombre@ejemplo.com");
        password = new PasswordField();
        password.setAccessibleText("Contraseña");
        password.setPromptText("Introduce tu contraseña");
        var shown = field("Contraseña visible", "");
        shown.setPromptText(password.getPromptText());
        shown.textProperty().bindBidirectional(password.textProperty());
        var reveal = new SimpleBooleanProperty();
        shown.visibleProperty().bind(reveal);
        shown.managedProperty().bind(reveal);
        password.visibleProperty().bind(reveal.not());
        password.managedProperty().bind(reveal.not());
        eye = new ToggleButton("", Icons.icon("eye", 18));
        eye.getStyleClass().add("login-reveal");
        eye.setAccessibleText("Mostrar contraseña");
        reveal.bind(eye.selectedProperty());
        eye.selectedProperty().addListener((obs, wasVisible, visible) ->
                eye.setAccessibleText(visible ? "Ocultar contraseña" : "Mostrar contraseña"));
        var passwordFields = new StackPane(password, shown);
        HBox.setHgrow(passwordFields, Priority.ALWAYS);
        var passwordRow = row(6, passwordFields, eye);
        passwordRow.getStyleClass().add("login-password");
        var focused = new SimpleBooleanProperty();
        focused.bind(password.focusedProperty().or(shown.focusedProperty()));
        var focusWithin = PseudoClass.getPseudoClass("focus-within");
        focused.addListener((obs, before, active) -> passwordRow.pseudoClassStateChanged(focusWithin, active));
        error = label("", "login-error");
        error.setWrapText(true);
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        error.visibleProperty().bind(error.managedProperty());
        enter = button("Ingresar a SICA", "arrow", "login-submit", this::submit);
        enter.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        enter.setMaxWidth(Double.MAX_VALUE);
        email.setOnAction(event -> submit());
        password.setOnAction(event -> submit());
        shown.setOnAction(event -> submit());
        var heading = new VBox(4, label("Iniciar sesión", "login-title"),
                label("Ingresa con tu cuenta de SICA.", "login-copy"));
        form.getChildren().setAll(heading, fieldGroup("Correo", email),
                new VBox(6, label("Contraseña", "field-label"), passwordRow), error, enter);
    }

    private void submit() {
        if (ocupado || onEnter == null) return;
        if (email.getText().isBlank() || password.getText().isBlank()) {
            estado("Escribe tu correo y tu contraseña.", false);
            return;
        }
        String correo = email.getText().strip();
        char[] clave = password.getText().toCharArray();
        password.clear();
        eye.setSelected(false);
        onEnter.accept(correo, clave);
    }

    private void setBackdrop() {
        Image background = new Image(AplicacionEscritorio.class.getResource(AplicacionEscritorio.RESOURCES + "imagenes/bosque.png").toExternalForm());
        forest.setImage(background);
        forest.fitWidthProperty().bind(root.widthProperty());
        forest.fitHeightProperty().bind(root.heightProperty());
        addFrost(glass, 58, 35, -110, "login-glass-sheen");
        Runnable resize = () -> {
            double width = root.getWidth();
            double height = root.getHeight();
            if (width <= 0 || height <= 0) return;
            double scale = Math.max(width / background.getWidth(), height / background.getHeight());
            double cropWidth = width / scale;
            double cropHeight = height / scale;
            forest.setViewport(new Rectangle2D((background.getWidth() - cropWidth) / 2,
                    (background.getHeight() - cropHeight) / 2, cropWidth, cropHeight));
            HBox.setMargin(glass, new Insets(0, 26 + Math.max(0, width - 1050) * .26, 0, 0));
            root.pseudoClassStateChanged(COMPACT, width < 1100 || height < 660);
        };
        root.widthProperty().addListener((obs, old, width) -> resize.run());
        root.heightProperty().addListener((obs, old, height) -> resize.run());
    }

    private void addFrost(StackPane surface, double cornerDiameter, double blur, double refraction, String sheenStyle) {
        Image background = forest.getImage();
        double bleed = blur * 1.7;
        var image = new ImageView(background);
        image.setManaged(false);
        image.setLayoutX(-bleed);
        image.setLayoutY(-bleed);
        image.fitWidthProperty().bind(surface.widthProperty().add(2 * bleed));
        image.fitHeightProperty().bind(surface.heightProperty().add(2 * bleed));

        var color = new ColorAdjust(.015, .19, .105, .05);
        var diffusion = new BoxBlur(blur, blur, 3);
        diffusion.setInput(color);
        image.setEffect(diffusion);

        var sheen = new Region();
        sheen.getStyleClass().add(sheenStyle);
        var inset = new StackPane(image, sheen);
        inset.setManaged(false);
        inset.setMouseTransparent(true);
        var clip = new Rectangle();
        clip.widthProperty().bind(surface.widthProperty());
        clip.heightProperty().bind(surface.heightProperty());
        clip.setArcWidth(cornerDiameter);
        clip.setArcHeight(cornerDiameter);
        inset.setClip(clip);
        surface.getChildren().addFirst(inset);

        Runnable sample = () -> {
            var viewport = forest.getViewport();
            if (viewport == null || forest.getFitWidth() <= 0 || surface.getWidth() <= 0 || surface.getHeight() <= 0) return;
            inset.resize(surface.getWidth(), surface.getHeight());
            var origin = forest.sceneToLocal(surface.localToScene(0, 0));
            double scale = viewport.getWidth() / forest.getFitWidth();
            double sampleWidth = Math.min(background.getWidth(), image.getFitWidth() * scale);
            double sampleHeight = Math.min(background.getHeight(), image.getFitHeight() * scale);
            double x = Math.clamp(viewport.getMinX() + (origin.getX() - bleed + refraction) * scale,
                    0, background.getWidth() - sampleWidth);
            double y = Math.clamp(viewport.getMinY() + (origin.getY() - bleed) * scale,
                    0, background.getHeight() - sampleHeight);
            image.setViewport(new Rectangle2D(x, y, sampleWidth, sampleHeight));
        };
        surface.layoutBoundsProperty().addListener((obs, before, after) -> sample.run());
        surface.localToSceneTransformProperty().addListener((obs, before, after) -> sample.run());
        forest.viewportProperty().addListener((obs, before, after) -> sample.run());
    }
}
