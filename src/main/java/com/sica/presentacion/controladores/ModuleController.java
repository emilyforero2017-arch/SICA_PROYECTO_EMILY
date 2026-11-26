package com.sica.presentacion.controladores;

import com.sica.presentacion.componentes.branding.Portrait;
import com.sica.presentacion.componentes.cards.IndicadorEstado;
import com.sica.presentacion.componentes.forms.CampoBusqueda;
import com.sica.presentacion.componentes.forms.Tabla;
import com.sica.presentacion.integracion.SicaData;
import com.sica.presentacion.integracion.SicaData.*;
import javafx.collections.FXCollections;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static com.sica.presentacion.componentes.ElementosUi.*;

final class ModuleController {
    private final GuardModules guard;
    private final SupervisorModules supervisor;
    private final AdministratorModules administrator;
    private final EmployeeModules employee;
    private final PassModules passes;

    ModuleController(ShellController shell) {
        guard = new GuardModules(shell);
        supervisor = new SupervisorModules(shell);
        administrator = new AdministratorModules(shell);
        employee = new EmployeeModules(shell);
        passes = new PassModules(shell);
    }

    Parent build(Route route) {
        return switch (route) {
            case RECORDS -> guard.records();
            case REQUESTS -> guard.requests();
            case PASSES -> passes.build();
            case VISITS -> supervisor.visits();
            case RESTRICTIONS -> supervisor.restrictions();
            case INCIDENTS -> supervisor.incidents();
            case AUDIT -> supervisor.audit();
            case COMPANIES -> administrator.companies();
            case ACCOUNTS -> administrator.accounts();
            case ROLES -> administrator.roles();
            case CALENDAR -> employee.calendar();
            case APPROVALS -> employee.approvals();
            case HISTORY -> employee.history();
            case WORKERS -> employee.workers();
            case ACCESSES -> supervisor.accesses();
            case HOME -> throw new IllegalArgumentException("Inicio usa su propio tablero");
        };
    }

    void newRequest(Runnable onCreated) { guard.newRequest(onCreated); }

    static TextField field(String name, String value) {
        TextField field = new TextField(value);
        field.setAccessibleText(name);
        field.setMaxWidth(Double.MAX_VALUE);
        return field;
    }

    static <T> ComboBox<T> select(String name, List<T> values, T value) {
        ComboBox<T> combo = new ComboBox<>(FXCollections.observableArrayList(values));
        combo.setValue(value);
        combo.setAccessibleText(name);
        return combo;
    }

    static <T> ComboBox<T> select(String name, List<T> values, T value,
            java.util.function.Function<T, String> caption) {
        var combo = select(name, values, value);
        combo.setPromptText(name);
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : caption.apply(value); }
            @Override public T fromString(String value) { return null; }
        });
        return combo;
    }

    static <T> T require(T value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
        return value;
    }

    static java.time.LocalDateTime momento(DatePicker date, TextField time) {
        LocalDate dia = require(date.getValue(), "Selecciona una fecha.");
        try { return dia.atTime(java.time.LocalTime.parse(time.getText().strip(), SicaData.TIME)); }
        catch (java.time.format.DateTimeParseException error) {
            throw new IllegalArgumentException("Escribe una hora válida en formato HH:mm.");
        }
    }

    static VBox fieldGroup(String name, Region field) {
        var label = label(name, "field-label");
        label.setLabelFor(field);
        return new VBox(6, label, field);
    }

    static HBox filterRow(Region... fields) {
        HBox filters = row(12, fields);
        filters.getStyleClass().add("filter-row");
        return filters;
    }

    static boolean contains(String source, String search) {
        return source.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }

    static HBox split(Region left, Region right, double rightSize) {
        left.setMinWidth(0);
        left.setMaxWidth(Double.MAX_VALUE);
        right.setMinWidth(0);
        right.setPrefWidth(rightSize);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox panels = new HBox(18, left, right);
        panels.setFillHeight(true);
        return panels;
    }

    static CampoBusqueda documentSearch(Consumer<String> query, HBox toolbar) {
        CampoBusqueda search = new CampoBusqueda("Número de documento", ignored -> {});
        HBox.setHgrow(search, Priority.ALWAYS);
        Runnable submit = () -> query.accept(search.query());
        search.onSubmit(submit);
        toolbar.getChildren().addAll(search, button("Buscar", null, "primary-button", submit));
        return search;
    }

    static Tabla<Visit> visitTable() {
        var table = new Tabla<Visit>();
        table.cells("Persona", 234, visit -> row(10,
                new Portrait(visit.person().name(), visit.person().portrait(), 32),
                textBlock(visit.person().name(), visit.person().document())));
        table.text("Empresa", 131, visit -> visit.person().company());
        table.text("Motivo", 224, Visit::reason);
        table.text("Fecha y hora", 167, SicaData::fecha);
        table.cells("Estado", 117, visit -> new IndicadorEstado(visit.status()));
        return table;
    }

    static boolean inRange(LocalDate day, DatePicker from, DatePicker to) {
        if (day == null) return from.getValue() == null && to.getValue() == null;
        return (from.getValue() == null || !day.isBefore(from.getValue()))
                && (to.getValue() == null || !day.isAfter(to.getValue()));
    }
}
