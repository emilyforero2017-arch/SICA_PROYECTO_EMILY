package com.sica.presentacion.controladores;

import com.sica.presentacion.animaciones.Motion;
import com.sica.presentacion.componentes.branding.Icons;
import com.sica.presentacion.componentes.branding.Portrait;
import com.sica.presentacion.componentes.calendar.CalendarioVisitas;
import com.sica.presentacion.componentes.cards.*;
import com.sica.presentacion.componentes.charts.GraficoBarras;
import com.sica.presentacion.integracion.SicaData;
import com.sica.presentacion.integracion.SicaData.*;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import static com.sica.presentacion.componentes.ElementosUi.*;

final class DashboardController {
    private final ShellController shell;
    private final SicaData data;

    DashboardController(ShellController shell) {
        this.shell = shell;
        data = shell.data;
    }

    Parent build(Profile profile) {
        ScreenLayout layout = switch (profile) {
            case GUARD -> guard();
            case SUPERVISOR -> supervisor();
            case ADMIN -> administrator();
            case EMPLOYEE -> employee();
        };
        var items = new ArrayList<Node>();
        GridPane bento = (GridPane) layout.view;
        bento.getChildren().forEach(node -> {
            if (node instanceof VBox slot && !slot.getChildren().isEmpty()) items.addAll(slot.getChildren());
        });
        responsiveColumns(bento, profile);
        Motion.stagger(items);
        return layout.view;
    }

    private ScreenLayout guard() {
        var view = new ScreenLayout("guard/home");
        view.add("entrySlot", action("Registrar entrada", "Valida un documento y registra el acceso.", "scan",
                "lime", Route.RECORDS, 165));
        view.add("requestSlot", action("Nueva solicitud", "Solicita la autorización de una visita.", "file",
                "deep", () -> {
                    shell.navigate(Route.REQUESTS);
                    shell.newRequest(() -> shell.navigate(Route.REQUESTS));
                }, 165));
        view.add("chartSlot", accessChart(true));
        view.add("operationsSlot", summary(), arrivals());
        VBox.setVgrow(view.slot("operationsSlot").getChildren().getLast(), Priority.ALWAYS);
        view.add("movementsSlot", recentMovements());
        view.add("profileSlot", shell.perfil("Guarda de turno"),
                new Tarjeta("soft", moduleLink("Pases temporales", "Abrir la ticketera de acceso", Route.PASSES)));
        return view;
    }

    private void responsiveColumns(GridPane bento, Profile profile) {
        double[] compact = switch (profile) {
            case GUARD -> new double[] {56, 44};
            case SUPERVISOR -> new double[] {34, 30, 36};
            case ADMIN -> new double[] {35, 29, 36};
            case EMPLOYEE -> new double[] {50, 50};
        };
        var columns = bento.getColumnConstraints();
        double[] regular = columns.stream().mapToDouble(column -> column.getPercentWidth()).toArray();
        bento.widthProperty().addListener((obs, before, after) -> {
            boolean small = after.doubleValue() < 990;
            if (small == (before.doubleValue() < 990) && before.doubleValue() > 0) return;
            double[] widths = small ? compact : regular;
            for (int i = 0; i < widths.length; i++) columns.get(i).setPercentWidth(widths[i]);
        });
    }

    private ScreenLayout supervisor() {
        var view = new ScreenLayout("supervisor/home");
        view.add("accessSlot", new TarjetaIndicador("Accesos registrados",
                data.movementCount(Status.ENTRY) + data.movementCount(Status.EXIT), "Movimientos del día", "scan", "lime"));
        view.add("incidentsSlot", new TarjetaIndicador("Incidentes registrados", data.incidents.size(),
                "En los últimos 30 días", "warning", "aqua"));
        view.add("visitSlot", action("Consulta de visitas", "Busca por documento.", "search", "deep", Route.VISITS, 145));
        view.add("chartSlot", accessChart(false));
        view.add("toolsSlot", new Tarjeta("soft", heading("Herramientas", null),
                moduleLink("Restricciones", "Consultar un documento", Route.RESTRICTIONS),
                rule(), moduleLink("Historial de incidentes", "Revisar novedades", Route.INCIDENTS),
                rule(), moduleLink("Auditoría general", "Explorar el registro de acciones", Route.AUDIT)));
        view.add("activitySlot", auditPreview("Actividad reciente"));
        view.add("profileSlot", shell.perfil("Supervisor de turno"));
        return view;
    }

    private ScreenLayout administrator() {
        var view = new ScreenLayout("administrator/home");
        int active = data.entradas;
        var now = data.today.atTime(data.currentTime());
        long recent = data.audits.stream().map(event -> event.date().atTime(event.time()))
                .filter(time -> !time.isAfter(now) && time.isAfter(now.minusHours(24))).count();
        view.add("accountsSlot", new TarjetaIndicador("Usuarios activos", active, "Cuentas habilitadas",
                "users", "lime"));
        view.add("auditSlot", new TarjetaIndicador("Acciones de auditoría", (int) recent,
                "Durante las últimas 24 horas", "audit", "aqua"));
        view.add("companiesSlot", action("Empresas del conjunto",
                "Consultar el directorio", "building", "deep", Route.COMPANIES, 145));
        view.add("activitySlot", auditPreview("Actividad reciente"));
        view.add("peopleSlot", shell.perfil("Administración"));
        return view;
    }

    private ScreenLayout employee() {
        var view = new ScreenLayout("employee/home");
        view.add("companySlot", action(data.empresaActual, data.nombreActual + " · Funcionario de empresa",
                "building", "deep", Route.APPROVALS, 145));
        int pending = data.pendientes;
        int upcoming = (int) scheduledCompany().stream().filter(v -> !v.date().isBefore(data.today)).count();
        view.add("metricsSlot", equalRow(new TarjetaIndicador("Autorizaciones pendientes", pending,
                        "Por revisar", "file", "lime"),
                new TarjetaIndicador("Visitas programadas", upcoming, "En agenda", "calendar", "white")));
        var details = new VBox(9);
        var calendar = new CalendarioVisitas(data.today, this::scheduledCompany, selected -> dayAgenda(details, selected, this::scheduledCompany));
        dayAgenda(details, data.today, this::scheduledCompany);
        view.add("calendarSlot", new Tarjeta("white",
                heading("Calendario de visitas", button("Abrir agenda", "arrow", "text-button", () -> shell.navigate(Route.CALENDAR))),
                calendar));
        view.add("agendaSlot", new Tarjeta("aqua", heading("Actividad del día", Icons.icon("calendar", 18)), details));
        VBox latest = new VBox(0);
        data.companyVisits().stream().filter(v -> v.status() == Status.PENDING || v.status() == Status.APPROVED
                || v.status() == Status.REJECTED).limit(4).forEach(v -> latest.getChildren().add(visitRow(v)));
        view.add("requestsSlot", new Tarjeta("white", heading("Solicitudes recientes",
                button("Ver historial", "arrow", "text-button", () -> shell.navigate(Route.HISTORY))), latest));
        return view;
    }

    List<Visit> scheduledCompany() {
        return data.companyVisits().stream()
                .filter(visit -> visit.date() != null && visit.status() == Status.SCHEDULED)
                .sorted(Comparator.comparing(Visit::date).thenComparing(Visit::time)).toList();
    }

    void dayAgenda(VBox target, LocalDate selected, Supplier<List<Visit>> source) {
        List<Visit> matches = source.get().stream().filter(visit -> selected.equals(visit.date())).toList();
        target.getChildren().setAll(label(selected.format(SicaData.DATE), "date-text"));
        if (matches.isEmpty()) target.getChildren().add(new EstadoVacio("Día sin visitas", "Selecciona otro día para consultar la agenda."));
        else matches.forEach(visit -> target.getChildren().add(visitRow(visit)));
    }

    private TarjetaAccion action(String title, String text, String icon, String tone, Route target, int height) {
        return action(title, text, icon, tone, () -> shell.navigate(target), height);
    }

    private TarjetaAccion action(String title, String text, String icon, String tone, Runnable handle, int height) {
        var card = new TarjetaAccion(title, text, icon, tone, handle);
        card.setPrefHeight(height);
        card.setMinHeight(height);
        card.setMaxHeight(height);
        return card;
    }

    private Tarjeta summary() {
        int pending = data.pendientes;
        var card = new Tarjeta("white", heading("Resumen del día", Icons.icon("chart", 17)),
                equalRow(summaryItem(data.movementCount(Status.ENTRY), "Entradas", "enter"),
                        summaryItem(data.movementCount(Status.EXIT), "Salidas", "logout"),
                        summaryItem(pending, "Pendientes", "clock")));
        card.getStyleClass().add("summary-card");
        return card;
    }

    private VBox summaryItem(int value, String caption, String icon) {
        VBox item = new VBox(1, row(5, label(String.format("%02d", value), "summary-number"), Icons.icon(icon, 15)),
                label(caption, "muted"));
        item.getStyleClass().add("summary-item");
        return item;
    }

    private HBox equalRow(javafx.scene.layout.Region... children) {
        var row = row(12, children);
        for (var child : children) {
            child.setMinWidth(0);
            child.setPrefWidth(0);
            child.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(child, javafx.scene.layout.Priority.ALWAYS);
        }
        return row;
    }

    private Tarjeta accessChart(boolean includeTotal) {
        var mode = new ComboBox<>(FXCollections.observableArrayList("Esta semana", "Hoy"));
        mode.getSelectionModel().selectFirst();
        mode.setAccessibleText("Intervalo de la gráfica");
        mode.getStyleClass().add("chart-period");
        VBox area = new VBox();
        Runnable chart = () -> {
            boolean week = mode.getSelectionModel().getSelectedIndex() == 0;
            area.getChildren().setAll(week
                ? new GraficoBarras(List.of("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"),
                        data.entradasSemana, data.salidasSemana)
                : new GraficoBarras(List.of("00–04", "04–08", "08–12", "12–16", "16–20", "20–24"),
                        data.entradasHoy, data.salidasHoy));
        };
        Label title = label("Actividad semanal", "card-title");
        mode.setOnAction(event -> {
            title.setText(mode.getSelectionModel().getSelectedIndex() == 0 ? "Actividad semanal" : "Actividad de hoy");
            chart.run();
        });
        chart.run();
        var legend = row(14, legendItem("Entradas", "entry"), legendItem("Salidas", "exit"));
        var total = row(10, label(Integer.toString(data.movementCount(Status.ENTRY)
                + data.movementCount(Status.EXIT)), "stat-number"), label("movimientos hoy", "muted"));
        Tarjeta card = new Tarjeta("white", row(10, title, spacer(), mode),
                includeTotal ? total : label("Distribución de entradas y salidas", "muted"), area, legend);
        card.getStyleClass().add("chart-card");
        return card;
    }

    private Node legendItem(String text, String tone) {
        var dot = new javafx.scene.layout.Region();
        dot.getStyleClass().addAll("legend-dot", "legend-" + tone);
        return row(6, dot, label(text, "muted"));
    }

    private Tarjeta arrivals() {
        VBox rows = new VBox(0);
        var now = data.today.atTime(data.currentTime());
        data.visits.stream().filter(v -> (v.status() == Status.SCHEDULED || v.status() == Status.APPROVED)
                        && data.today.equals(v.date()))
                .sorted(Comparator.comparing(Visit::date).thenComparing(Visit::time)).limit(2).forEach(visit -> rows.getChildren().add(
                        row(10, label(visit.time().format(SicaData.TIME), "time-pill"),
                                textBlock(visit.person().name(), visit.person().company() + (visit.date().equals(data.today)
                                        ? "" : " · " + visit.date().format(SicaData.DATE))))));
        if (rows.getChildren().isEmpty()) rows.getChildren().add(label("Sin visitas próximas en la agenda.", "muted"));
        rows.getChildren().forEach(node -> node.getStyleClass().add("arrival-row"));
        Tarjeta card = new Tarjeta("aqua", heading("Próximas llegadas", Icons.icon("calendar", 18)), rows);
        card.getStyleClass().add("arrivals-card");
        return card;
    }

    private Tarjeta recentMovements() {
        VBox rows = new VBox(0);
        data.movements.stream().limit(4).forEach(movement -> rows.getChildren().add(movementRow(movement)));
        if (rows.getChildren().isEmpty()) rows.getChildren().add(label("Sin movimientos registrados hoy.", "muted"));
        return new Tarjeta("white", heading("Movimientos recientes",
                button("Ver registros", "arrow", "text-button", () -> shell.navigate(Route.RECORDS))), rows);
    }

    private Tarjeta auditPreview(String title) {
        VBox rows = new VBox(0);
        data.audits.stream().limit(5).forEach(item -> {
            var line = row(11, Icons.icon("audit", 17), textBlock(item.action(), item.actor() + " · " + item.detail()),
                    spacer(), label(item.time().format(SicaData.TIME), "muted"));
            line.getStyleClass().add("list-row");
            rows.getChildren().add(line);
        });
        if (rows.getChildren().isEmpty()) rows.getChildren().add(label("Sin actividad reciente.", "muted"));
        return new Tarjeta("white", heading(title,
                button("Ver auditoría", "arrow", "text-button", () -> shell.navigate(Route.AUDIT))), rows);
    }

    private Node moduleLink(String title, String description, Route route) {
        var line = row(10, Icons.icon(route.icon, 19), textBlock(title, description), spacer(), Icons.icon("right", 15));
        var button = new javafx.scene.control.Button("", line);
        button.setAccessibleText(title);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("module-link");
        line.prefWidthProperty().bind(button.widthProperty().subtract(16));
        button.setOnAction(event -> shell.navigate(route));
        return button;
    }

    static HBox movementRow(Movement movement) {
        Person person = movement.person();
        var line = row(11, new Portrait(person.name(), person.portrait(), 32), textBlock(person.name(), person.company()),
                spacer(), label(movement.time().format(SicaData.TIME), "muted"), new IndicadorEstado(movement.type()));
        line.getStyleClass().add("list-row");
        return line;
    }

    static HBox visitRow(Visit visit) {
        var line = row(9, label(visit.time() == null ? "—" : visit.time().format(SicaData.TIME), "time-pill"),
                textBlock(visit.person().name(), visit.reason()), spacer(), new IndicadorEstado(visit.status()));
        line.getStyleClass().add("list-row");
        return line;
    }
}
