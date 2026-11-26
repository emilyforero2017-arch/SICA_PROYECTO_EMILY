package com.sica.presentacion.inicio;

import com.sica.config.AplicacionSica;
import com.sica.config.ConfiguracionBasedatos;
import com.sica.presentacion.controladores.LoginController;
import com.sica.presentacion.controladores.ShellController;
import com.sica.presentacion.integracion.ConexionInterfaz;
import com.sica.presentacion.integracion.SicaData;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class AplicacionEscritorio extends Application {
    public static final String RESOURCES = "/com/sica/presentacion/";

    private final ExecutorService tareas = Executors.newFixedThreadPool(3, trabajo -> {
        Thread hilo = new Thread(trabajo, "sica-interfaz");
        hilo.setDaemon(true);
        return hilo;
    });
    private AplicacionSica app;
    private ShellController shell;

    @Override public void init() { app = new AplicacionSica(new ConfiguracionBasedatos()); }

    @Override public void start(Stage stage) throws IOException {
        Locale.setDefault(Locale.Category.FORMAT, SicaData.SPANISH);
        var loginLoader = new FXMLLoader(getClass().getResource(RESOURCES + "fxml/layouts/login.fxml"));
        Parent loginView = loginLoader.load();
        LoginController login = loginLoader.getController();
        Scene scene = new Scene(loginView, 1332, 820);
        for (String theme : List.of("theme", "components", "navigation", "controls", "dashboard", "login", "passes")) {
            scene.getStylesheets().add(getClass().getResource(RESOURCES + "estilos/" + theme + ".css").toExternalForm());
        }
        login.setOnEnter((correo, clave) -> {
            login.estado("Ingresando…", true);
            Task<ConexionInterfaz> autenticar = new Task<>() {
                @Override protected ConexionInterfaz call() {
                    try {
                        app.iniciar();
                        return new ConexionInterfaz(app, app.iniciarSesion(correo, clave));
                    } finally {
                        Arrays.fill(clave, '\0');
                    }
                }
            };
            autenticar.setOnFailed(evento -> login.estado(mensaje(autenticar.getException()), false));
            autenticar.setOnSucceeded(evento -> {
                try {
                    var shellLoader = new FXMLLoader(getClass().getResource(RESOURCES + "fxml/layouts/shell.fxml"));
                    Parent shellView = shellLoader.load();
                    shell = shellLoader.getController();
                    shell.setOnSignOut(() -> {
                        shell.close();
                        shell = null;
                        app.cerrarSesion();
                        scene.setRoot(loginView);
                        login.show();
                    });
                    scene.setRoot(shellView);
                    shell.enter(autenticar.getValue(), tareas);
                    shellView.requestFocus();
                } catch (IOException | RuntimeException error) {
                    if (shell != null) shell.close();
                    shell = null;
                    app.cerrarSesion();
                    scene.setRoot(loginView);
                    login.estado("No se pudo abrir el espacio de tu cuenta.", false);
                    error.printStackTrace();
                }
            });
            tareas.execute(autenticar);
        });
        stage.setTitle("SICA · Zona ACME");
        stage.setMinWidth(920);
        stage.setMinHeight(605);
        stage.setScene(scene);
        stage.show();
        login.show();
        tareas.execute(() -> {
            try { app.iniciar(); }
            catch (RuntimeException error) {
                System.getLogger(AplicacionEscritorio.class.getName()).log(System.Logger.Level.WARNING, "SICA no pudo preparar la conexión inicial.");
            }
        });
    }

    public static String mensaje(Throwable error) {
        if (error instanceof IllegalArgumentException || error instanceof SecurityException)
            return java.util.Objects.toString(error.getMessage(), "No se pudo completar la operación.");
        if (error instanceof IllegalStateException && error.getCause() == null)
            return java.util.Objects.toString(error.getMessage(), "No se pudo completar la operación.");
        return "SICA no pudo completar la operación. Inténtalo de nuevo.";
    }

    @Override public void stop() {
        if (shell != null) shell.close();
        tareas.shutdownNow();
        if (app != null) app.close();
    }
}
