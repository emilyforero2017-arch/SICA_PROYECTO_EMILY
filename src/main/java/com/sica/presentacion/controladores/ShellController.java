package com.sica.presentacion.controladores;

import com.sica.presentacion.animaciones.Motion;
import com.sica.presentacion.inicio.AplicacionEscritorio;
import com.sica.presentacion.integracion.ConexionInterfaz;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import javafx.concurrent.Task;
import com.sica.presentacion.componentes.branding.Icons;
import com.sica.presentacion.componentes.branding.Portrait;
import com.sica.presentacion.componentes.cards.Tarjeta;
import com.sica.presentacion.componentes.cards.TarjetaPerfil;
import com.sica.presentacion.componentes.dialogs.Dialogo;
import com.sica.presentacion.componentes.dialogs.PanelLateral;
import com.sica.presentacion.componentes.dialogs.Aviso;
import com.sica.presentacion.componentes.navigation.Cabecera;
import com.sica.presentacion.componentes.navigation.MenuLateral;
import com.sica.presentacion.integracion.SicaData;
import com.sica.presentacion.integracion.SicaData.Profile;
import com.sica.presentacion.integracion.SicaData.Route;

import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

import static com.sica.presentacion.componentes.ElementosUi.*;

public final class ShellController {
    private static final DateTimeFormatter LONG_DATE =
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SicaData.SPANISH);
    @FXML private StackPane root;
    @FXML private StackPane sidebarSlot;
    @FXML private StackPane headerSlot;
    @FXML private ScrollPane scroll;
    @FXML private VBox page;

    private Profile profile = Profile.GUARD;
    private Route route = Route.HOME;
    private MenuLateral sidebar;
    private Cabecera header;
    private PanelLateral drawer;
    private Aviso toast;
    private ModuleController modules;
    private DashboardController dashboards;

    final SicaData data = new SicaData();
    ConexionInterfaz conexion;
    private ExecutorService tareas;
    private boolean abierta;
    private Runnable detenerPantalla = () -> {};
    private int retrato;
    private boolean notificationsEnabled = true;
    private Runnable onSignOut;

    @FXML private void initialize() {
        drawer = new PanelLateral(root);
        toast = new Aviso(root);
        sidebar = new MenuLateral(this::navigate, this::settings, this::signOut, this::account);
        header = new Cabecera(this::notifications, this::account);
        modules = new ModuleController(this);
        dashboards = new DashboardController(this);
        sidebarSlot.getChildren().add(sidebar);
        headerSlot.getChildren().add(header);
        sidebar.prefHeightProperty().bind(sidebarSlot.heightProperty());
        root.widthProperty().addListener((obs, previous, now) -> {
            boolean compact = now.doubleValue() < 1080;
            sidebar.setCompactMode(compact);
            root.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("compact"), compact);
        });
    }

    Profile profile() { return profile; }
    Window window() { return root.getScene().getWindow(); }
    void notify(String title, String message) { toast.show(title, message); }
    void drawer(String title, Node content) { drawer.open(title, content); }
    void cerrarDetalle() { drawer.close(); }
    void newRequest(Runnable onCreated) { modules.newRequest(onCreated); }

    public void setOnSignOut(Runnable action) { onSignOut = action; }

    public void enter(ConexionInterfaz conexion, ExecutorService tareas) {
        this.conexion = conexion;
        this.tareas = tareas;
        abierta = true;
        profile = conexion.perfil;
        data.nombreActual = conexion.sesion.getNombre();
        data.empresaActual = java.util.Objects.toString(conexion.sesion.getNombreEmpresa(), "Zona ACME");
        data.fotoActual = conexion.sesion.getFotoPerfil();
        retrato = switch (conexion.sesion.getEmail().toLowerCase(java.util.Locale.ROOT)) {
            case "guarda@sica.local", "supervisor@sica.local", "admin@sica.local", "ana@sol.sica.local" -> profile.portrait;
            default -> -1;
        };
        sidebar.setProfile(profile, data.nombreActual, retrato, data.fotoActual);
        header.setProfile(data.nombreActual, conexion.sesion.getNombreRol(), retrato, data.fotoActual);
        navigate(Route.HOME);
    }

    TarjetaPerfil perfil(String titulo) {
        return new TarjetaPerfil(titulo, data.nombreActual, conexion.sesion.getNombreRol(), retrato, data.fotoActual);
    }

    void alDejarPantalla(Runnable action) { detenerPantalla = action; }

    void navigate(Route target) {
        if (!abierta || !profile.routes.contains(target)) return;
        detenerPantalla.run();
        detenerPantalla = () -> {};
        route = target;
        drawer.close();
        sidebar.select(target);
        render();
        scroll.setVvalue(0);
    }

    <T> void consultar(Node control, Supplier<T> consulta, Consumer<T> exito) {
        consultar(control, consulta, exito, mensaje -> notify("No se pudo completar", mensaje));
    }

    <T> void consultar(Node control, Supplier<T> consulta, Consumer<T> exito, Consumer<String> error) {
        if (!abierta) return;
        if (control != null) control.setDisable(true);
        Task<T> tarea = new Task<>() {
            @Override protected T call() { return consulta.get(); }
        };
        tarea.setOnSucceeded(evento -> {
            if (control != null) control.setDisable(false);
            if (abierta) exito.accept(tarea.getValue());
        });
        tarea.setOnFailed(evento -> {
            if (control != null) control.setDisable(false);
            if (abierta) error.accept(AplicacionEscritorio.mensaje(tarea.getException()));
        });
        tareas.execute(tarea);
    }

    <T> void formulario(String titulo, String descripcion, Node contenido, String confirmar,
            Supplier<Supplier<T>> preparar, Consumer<T> guardado) {
        Dialogo.openAsync(window(), titulo, descripcion, contenido, confirmar, (panel, finalizar, error) -> {
            try {
                consultar(panel, preparar.get(), resultado -> {
                    finalizar.run();
                    guardado.accept(resultado);
                }, error);
            } catch (IllegalArgumentException invalido) {
                error.accept(AplicacionEscritorio.mensaje(invalido));
            }
        });
    }

    private void cargarPortada(Node anterior) {
        consultar(null, () -> conexion.portada(data.today), portada -> {
            data.visits.setAll(portada.visitas());
            data.movements.setAll(portada.movimientos());
            data.audits.setAll(portada.auditoria());
            data.incidents.setAll(portada.incidentes());
            data.entradas = portada.entradas();
            data.salidas = portada.salidas();
            data.pendientes = portada.pendientes();
            if (portada.semana() != null) {
                data.entradasSemana = portada.semana().stream().mapToInt(r -> Math.toIntExact(r.entradas())).toArray();
                data.salidasSemana = portada.semana().stream().mapToInt(r -> Math.toIntExact(r.salidas())).toArray();
                data.entradasHoy = portada.hoy().stream().mapToInt(r -> Math.toIntExact(r.entradas())).toArray();
                data.salidasHoy = portada.hoy().stream().mapToInt(r -> Math.toIntExact(r.salidas())).toArray();
            }
            int indice = page.getChildren().indexOf(anterior);
            if (route == Route.HOME && indice >= 0) page.getChildren().set(indice, dashboards.build(profile));
        });
    }

    private void render() {
        boolean home = route == Route.HOME;
        String title = home ? "Hola, " + data.nombreActual + "." : route.title;
        String description = home ? switch (profile) {
            case GUARD -> "Registra accesos y consulta las próximas llegadas.";
            case SUPERVISOR -> "Da seguimiento a accesos, visitas e incidentes.";
            case ADMIN -> "Gestiona las empresas y cuentas de Zona ACME.";
            case EMPLOYEE -> "Organiza las visitas y autorizaciones de " + data.empresaActual + ".";
        } : route.description;
        var intro = new VBox(4, label(title, "page-title"), label(description, "page-description"));
        var date = row(8, Icons.icon("calendar", 16), label(capitalize(data.today.format(LONG_DATE)), "date-text"));
        date.getStyleClass().add("date-pill");
        date.setMaxHeight(Region.USE_PREF_SIZE);
        HBox pageHeader = row(12, intro, spacer(), date);
        pageHeader.getStyleClass().add("page-header");
        if (!home) pageHeader.getStyleClass().add("module-header");
        Node screen = home ? dashboards.build(profile) : modules.build(route);
        page.getChildren().setAll(pageHeader, screen);
        Motion.reveal(pageHeader, 3, 0);
        Motion.reveal(screen, 4, 30);
        if (home) cargarPortada(screen);
    }

    private static String capitalize(String value) {
        return value.substring(0, 1).toUpperCase(SicaData.SPANISH) + value.substring(1);
    }

    private void settings() {
        CheckBox enabled = new CheckBox("Mostrar avisos de actividad");
        enabled.setSelected(notificationsEnabled);
        drawer("Configuración", new VBox(14, label("Notificaciones de actividad", "row-title"), enabled,
                button("Guardar preferencias", "check", "primary-button", () -> {
                    notificationsEnabled = enabled.isSelected();
                    drawer.close();
                    notify("Preferencias guardadas", "Los cambios se aplicaron a esta sesión.");
                })));
    }

    private void signOut() {
        if (!abierta) return;
        Dialogo.open(window(), "Cerrar sesión", "Volverás a la pantalla de inicio de sesión.",
                new Region(), "Cerrar sesión", () -> {
                    if (onSignOut != null) javafx.application.Platform.runLater(onSignOut);
                    return true;
                });
    }

    private void account() {
        drawer("Información del usuario", new Tarjeta("soft",
                row(12, new Portrait(data.nombreActual, retrato, 52, data.fotoActual),
                        textBlock(data.nombreActual, conexion.sesion.getNombreRol())),
                rule(), label(conexion.sesion.getEmail(), "muted"), label(data.empresaActual, "row-title")));
    }

    private void notifications() {
        VBox events = new VBox(11);
        drawer("Notificaciones", events);
        if (!notificationsEnabled) {
            events.getChildren().add(label("Los avisos están desactivados.", "muted"));
            return;
        }
        switch (profile) {
            case GUARD -> consultar(events, () -> conexion.app.getAccesoPorSolicitud().listarRespuestas(conexion.usuario),
                    filas -> avisos(events, filas.stream().filter(f -> !f.estado().startsWith("Pendiente"))
                            .limit(5).map(f -> textBlock(f.persona(), f.estado() + " · " + f.empresa())).toList()));
            case EMPLOYEE -> consultar(events, () -> conexion.app.getSolicitudes().listarPendientes(conexion.usuario),
                    filas -> avisos(events, filas.stream().limit(5).map(f -> textBlock(f.getPersona().getNombre(),
                            ConexionInterfaz.tipo(f.getSolicitud().getTipoIngreso()))).toList()));
            case ADMIN, SUPERVISOR -> consultar(events, () -> conexion.auditoria(data.today.minusDays(1), data.today, false),
                    filas -> avisos(events, filas.stream().limit(5).map(f ->
                            textBlock(f.action(), f.actor())).toList()));
        }
    }

    private void avisos(VBox contenedor, List<? extends Node> avisos) {
        contenedor.getChildren().setAll(avisos.stream().map(nodo -> new Tarjeta("soft", nodo)).toList());
        if (avisos.isEmpty()) contenedor.getChildren().add(label("No hay avisos.", "muted"));
    }

    public void close() {
        abierta = false;
        detenerPantalla.run();
        drawer.close();
    }
}
