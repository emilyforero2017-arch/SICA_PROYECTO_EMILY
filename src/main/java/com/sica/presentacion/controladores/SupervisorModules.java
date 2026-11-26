package com.sica.presentacion.controladores;

import com.sica.model.application.usecase.DetalleAccesoSeguridad;
import com.sica.model.application.usecase.DetalleMovimientoAcceso;
import com.sica.presentacion.animaciones.Motion;
import com.sica.presentacion.componentes.branding.Icons;
import com.sica.presentacion.componentes.branding.Portrait;
import com.sica.presentacion.componentes.cards.Tarjeta;
import com.sica.presentacion.componentes.cards.EstadoVacio;
import com.sica.presentacion.componentes.cards.IndicadorEstado;
import com.sica.presentacion.componentes.forms.CampoBusqueda;
import com.sica.presentacion.componentes.forms.Tabla;
import com.sica.presentacion.integracion.ConexionInterfaz;
import com.sica.presentacion.integracion.SicaData;
import com.sica.presentacion.integracion.SicaData.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;

import static com.sica.presentacion.componentes.ElementosUi.*;
import static com.sica.presentacion.controladores.ModuleController.*;

final class SupervisorModules {
    private final ShellController shell;
    private final SicaData data;

    SupervisorModules(ShellController shell) {
        this.shell = shell;
        data = shell.data;
    }

    Parent visits() {
        var c = shell.conexion;
        var screen = new ScreenLayout("supervisor/visits");
        var table = visitTable();
        var toolbar = row(9);
        documentSearch(text -> shell.consultar(toolbar, () -> c.app.getSeguridad().consultarVisitasDePersona(c.usuario, text)
                .stream().map(ConexionInterfaz::acceso).toList(), table::show), toolbar);
        var inspect = button("Ver detalle", "arrow", "secondary-button", () -> visitDetail(table.getSelectionModel().getSelectedItem()));
        inspect.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        table.setRowFactory(view -> {
            var fila = new javafx.scene.control.TableRow<Visit>();
            fila.setOnMouseClicked(event -> {
                if (!fila.isEmpty() && event.getButton() == javafx.scene.input.MouseButton.PRIMARY) visitDetail(fila.getItem());
            });
            return fila;
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) visitDetail(table.getSelectionModel().getSelectedItem());
        });
        table.show(List.of());
        screen.add("searchSlot", new Tarjeta("white", heading("Buscar por documento", null), toolbar));
        screen.add("visitsSlot", new Tarjeta("white", heading("Visitas", inspect), table));
        return screen.view;
    }

    private void visitDetail(Visit visit) {
        if (visit == null) return;
        var c = shell.conexion;
        Person person = visit.person();
        VBox related = new VBox(9, label("Incidentes asociados", "card-title"));
        shell.drawer("Detalle de la visita", new VBox(13,
                new Tarjeta("soft", row(12, new Portrait(person.name(), person.portrait(), 44),
                        textBlock(person.name(), "Documento " + person.document())),
                        label(person.company(), "row-title"), label(SicaData.fecha(visit), "muted"),
                        label(visit.reason(), "muted"), new IndicadorEstado(visit.status())),
                related, button("Registrar incidente", "plus", "primary-button", () -> newIncident(visit))));
        shell.consultar(related, () -> c.app.getIncidentes().consultarPorVisita(c.usuario, visit.id()), matches -> {
            if (matches.isEmpty()) related.getChildren().add(label("Sin incidentes asociados.", "muted"));
            matches.stream().map(ConexionInterfaz::incidente).forEach(event -> {
                var text = label(event.description(), "row-title");
                text.setWrapText(true);
                related.getChildren().add(new Tarjeta("soft", text, label(stamp(event.date(), event.time()), "muted")));
            });
        });
    }

    Parent restrictions() {
        var screen = new ScreenLayout("supervisor/restrictions");
        VBox result = new VBox(12);
        var toolbar = row(9);
        var campo = documentSearch(query -> consultarRestriccion(query, toolbar, result), toolbar);
        campo.queryProperty().addListener((obs, anterior, actual) -> result.getChildren().clear());
        screen.add("searchSlot", new Tarjeta("white", heading("Estado de acceso", null), toolbar));
        screen.add("resultSlot", result);
        return screen.view;
    }

    private void consultarRestriccion(String query, Node toolbar, VBox result) {
        var c = shell.conexion;
        result.getChildren().clear();
        shell.consultar(toolbar, () -> c.app.getRestricciones().consultarSituacion(c.usuario, query), respuesta -> {
            if (respuesta.isEmpty()) {
                result.getChildren().setAll(new EstadoVacio("Sin resultados", "Verifica el número de documento."));
                return;
            }
            var dato = respuesta.get();
            var persona = dato.getPersona();
            String estado = dato.getEstadoAcceso().getNombreEstado();
            boolean restringido = "Con Prohibición de Ingreso".equals(estado);
            var historial = new VBox(8, label("Historial de restricciones", "card-title"));
            dato.getHistorial().forEach(registro -> {
                var texto = label(registro.getDetalles(), "muted");
                texto.setWrapText(true);
                historial.getChildren().add(new Tarjeta("soft",
                        textBlock(dato.getNombreResponsable(registro.getUsuarioId()),
                                stamp(registro.getFechaHora().toLocalDate(), registro.getFechaHora().toLocalTime())), texto));
            });
            if (dato.getHistorial().isEmpty())
                historial.getChildren().add(label("Sin prohibiciones ni retiros registrados.", "muted"));
            var card = new Tarjeta("white",
                    row(12, new Portrait(persona.getNombre(), -1, 50),
                            textBlock(persona.getNombre(), "Documento " + persona.getDocumentoIdentidad()), spacer(),
                            label(estado, "row-title")),
                    rule(), historial);
            if (restringido || "Activo".equals(estado)) {
                String titulo = restringido ? "Retirar prohibición" : "Imponer prohibición";
                card.getChildren().add(button(titulo, "shield", restringido ? "secondary-button" : "danger-button", () -> {
                    var motivo = field("Motivo", "");
                    shell.formulario(titulo, persona.getNombre(), fieldGroup("Motivo", motivo), "Confirmar", () -> {
                        String razon = motivo.getText().strip();
                        return () -> restringido ? c.app.getRestricciones().retirarProhibicion(c.usuario, persona.getId(), razon)
                                : c.app.getRestricciones().imponerProhibicion(c.usuario, persona.getId(), razon);
                    }, guardada -> {
                        consultarRestriccion(query, toolbar, result);
                        shell.notify(restringido ? "Prohibición retirada" : "Prohibición registrada", guardada.getNombre());
                    });
                }));
            }
            result.getChildren().setAll(card);
            Motion.reveal(card, 4, 0);
        });
    }

    Parent incidents() {
        var c = shell.conexion;
        var screen = new ScreenLayout("supervisor/incidents");
        var table = new Tabla<Incident>();
        table.cells("Visita", 192, incident -> textBlock(incident.visit().person().name(), incident.visit().person().company()));
        table.text("Fecha y hora", 166, incident -> stamp(incident.date(), incident.time()));
        table.text("Descripción", 430, Incident::description);
        table.cells("Estado", 120, incident -> new IndicadorEstado(Status.LOGGED));
        var from = date("Desde");
        var to = date("Hasta");
        from.setValue(data.today.minusDays(7));
        to.setValue(data.today);
        Runnable refresh = () -> consultarPeriodo(from, to, table, (inicio, fin) -> c.app.getIncidentes()
                .consultarHistorial(c.usuario, inicio.atStartOfDay(), fin.plusDays(1).atStartOfDay())
                .stream().map(ConexionInterfaz::incidente).toList());
        screen.add("filterSlot", new Tarjeta("white", filterRow(fieldGroup("Desde", from), fieldGroup("Hasta", to),
                spacer(), button("Consultar", "search", "primary-button", refresh))));
        screen.add("historySlot", new Tarjeta("white", heading("Incidentes registrados", null), table));
        refresh.run();
        return screen.view;
    }

    Parent audit() { return audit(false); }

    Parent audit(boolean propia) {
        var c = shell.conexion;
        var screen = new ScreenLayout("layouts/audit");
        var table = new Tabla<Audit>();
        table.cells("Acción", 230, event -> row(10, Icons.icon("audit", 16), textBlock(event.action(), event.type())));
        table.text("Usuario", 145, Audit::actor);
        table.text("Detalle", 250, Audit::detail);
        table.text("Fecha y hora", 145, event -> stamp(event.date(), event.time()));
        var from = date("Desde");
        var to = date("Hasta");
        from.setValue(data.today.minusDays(7));
        to.setValue(data.today);
        var type = select("Tipo de acción", List.of("Todas", "Sesiones", "Registros", "Seguridad", "Administración", "Autorizaciones"), "Todas");
        var search = new CampoBusqueda("Buscar acción o usuario", ignored -> {});
        var filas = new javafx.beans.property.SimpleObjectProperty<List<Audit>>(List.of());
        Runnable filtrar = () -> table.show(filas.get().stream()
                .filter(event -> ("Todas".equals(type.getValue()) || type.getValue().equals(event.type()))
                        && contains(event.actor() + " " + event.action() + " " + event.detail(), search.query())).toList());
        Runnable refresh = () -> {
            if (!validarPeriodo(from.getValue(), to.getValue())) return;
            var inicio = from.getValue();
            var fin = to.getValue();
            shell.consultar(table, () -> c.auditoria(inicio, fin, propia), registros -> {
                filas.set(registros);
                filtrar.run();
            });
        };
        search.onSubmit(filtrar);
        type.setOnAction(event -> filtrar.run());
        if (propia) {
            var filtros = new javafx.scene.layout.FlowPane(8, 8, search, fieldGroup("Tipo de acción", type),
                    fieldGroup("Desde", from), fieldGroup("Hasta", to), button("Aplicar", null, "primary-button", refresh));
            screen.add("filterSlot", new Tarjeta("white", filtros));
        } else screen.add("filterSlot", new Tarjeta("white", filterRow(search,
                fieldGroup("Tipo de acción", type), fieldGroup("Desde", from), fieldGroup("Hasta", to),
                button("Aplicar", null, "primary-button", refresh))));
        screen.add("auditSlot", new Tarjeta("white", heading("Registro de actividad", null), table));
        refresh.run();
        return screen.view;
    }

    Parent accesses() {
        var c = shell.conexion;
        var screen = new ScreenLayout("supervisor/incidents");
        var dentro = new Tabla<DetalleAccesoSeguridad>();
        dentro.text("Persona", 235, d -> d.getPersona().getNombre());
        dentro.text("Documento", 125, d -> d.getPersona().getDocumentoIdentidad());
        dentro.text("Empresa", 145, DetalleAccesoSeguridad::getEmpresa);
        dentro.text("Entrada", 165, d -> fechaHora(d.getVisita().getFechaEntrada()));
        Runnable abiertos = () -> shell.consultar(dentro, () -> c.app.getSeguridad().consultarIngresosAbiertos(c.usuario), dentro::show);
        var historial = new Tabla<DetalleMovimientoAcceso>();
        historial.text("Persona", 235, d -> d.getAcceso().getPersona().getNombre());
        historial.text("Empresa", 140, d -> d.getAcceso().getEmpresa());
        historial.text("Movimiento", 190, d -> switch (d.getTipo()) {
            case ENTRADA -> "Entrada";
            case SALIDA -> "Salida";
            case CIERRE_SIN_SALIDA_REGISTRADA -> "Cierre sin salida registrada";
        });
        historial.text("Fecha y hora", 165, d -> fechaHora(d.getFechaMovimiento()));
        var from = date("Desde");
        var to = date("Hasta");
        from.setValue(data.today);
        to.setValue(data.today);
        Runnable refresh = () -> consultarPeriodo(from, to, historial, (inicio, fin) -> c.app.getSeguridad()
                .consultarHistorial(c.usuario, inicio.atStartOfDay(), fin.plusDays(1).atStartOfDay()));
        screen.add("filterSlot", new Tarjeta("white", heading("Personas dentro",
                button("Actualizar", null, "secondary-button", abiertos)), dentro));
        screen.add("historySlot", new Tarjeta("white", heading("Historial de accesos", null),
                filterRow(fieldGroup("Desde", from), fieldGroup("Hasta", to),
                        button("Consultar", "search", "primary-button", refresh)), historial));
        abiertos.run();
        refresh.run();
        return screen.view;
    }

    private <T> void consultarPeriodo(DatePicker from, DatePicker to, Tabla<T> tabla,
            java.util.function.BiFunction<LocalDate, LocalDate, List<T>> consulta) {
        LocalDate inicio = from.getValue(), fin = to.getValue();
        if (validarPeriodo(inicio, fin)) shell.consultar(tabla, () -> consulta.apply(inicio, fin), tabla::show);
    }

    private boolean validarPeriodo(LocalDate inicio, LocalDate fin) {
        if (inicio != null && fin != null && !fin.isBefore(inicio) && !fin.equals(LocalDate.MAX)) return true;
        shell.notify("Revisa las fechas", "La fecha final debe ser igual o posterior a la inicial.");
        return false;
    }

    private void newIncident(Visit visit) {
        var c = shell.conexion;
        var date = date("Fecha");
        date.setValue(data.today);
        var time = field("Hora", data.currentTime().format(SicaData.TIME));
        time.setPrefColumnCount(6);
        var note = new TextArea();
        note.setWrapText(true);
        note.setPrefRowCount(3);
        VBox form = new VBox(11, new Tarjeta("soft", textBlock(visit.person().name(), visit.person().company())),
                row(12, fieldGroup("Fecha", date), fieldGroup("Hora (HH:mm)", time)), fieldGroup("Descripción del incidente", note));
        shell.formulario("Registrar incidente", "Se asociará a la visita seleccionada.", form, "Guardar incidente", () -> {
            var momento = momento(date, time);
            var descripcion = note.getText().strip();
            return () -> c.app.getIncidentes().registrar(c.usuario, visit.id(), momento, descripcion);
        }, guardado -> {
            visitDetail(visit);
            shell.notify("Incidente registrado", visit.person().name());
        });
    }

    static DatePicker date(String caption) {
        DatePicker control = new DatePicker();
        control.setPromptText(caption);
        control.setAccessibleText(caption);
        control.setEditable(false);
        control.setPrefWidth(144);
        return control;
    }

    static String stamp(LocalDate date, LocalTime time) {
        return date.format(SicaData.DATE) + " · " + time.format(SicaData.TIME);
    }

    private static String fechaHora(java.time.LocalDateTime fecha) {
        return stamp(fecha.toLocalDate(), fecha.toLocalTime());
    }
}
