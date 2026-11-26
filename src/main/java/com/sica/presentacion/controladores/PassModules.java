package com.sica.presentacion.controladores;

import com.sica.presentacion.componentes.branding.Icons;
import com.sica.presentacion.componentes.branding.Portrait;
import com.sica.presentacion.componentes.cards.Tarjeta;
import com.sica.presentacion.componentes.cards.EstadoVacio;
import com.sica.presentacion.componentes.cards.TicketPase;
import com.sica.presentacion.componentes.cards.IndicadorEstado;
import com.sica.presentacion.componentes.forms.CampoBusqueda;
import com.sica.presentacion.componentes.forms.SelectorPestanas;
import com.sica.presentacion.integracion.SicaData;
import com.sica.presentacion.integracion.ConexionInterfaz;
import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.policy.HorarioCarnetOlvidado;
import com.sica.presentacion.integracion.SicaData.*;
import com.sica.presentacion.integracion.TemporaryPass;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static com.sica.presentacion.componentes.ElementosUi.*;
import static com.sica.presentacion.controladores.ModuleController.*;

final class PassModules {
    private static final int ARCHIVE = 0;
    private static final int PENDING = 1;
    private final ShellController shell;
    private final SicaData data;

    PassModules(ShellController shell) {
        this.shell = shell;
        this.data = shell.data;
    }

    Parent build() { return new PassView().layout.view; }

    private boolean canReview(TemporaryPass pass) {
        return shell.profile() == Profile.EMPLOYEE;
    }

    private final class PassView {
        private final ScreenLayout layout = new ScreenLayout("layouts/passes");
        private final Label approvedCount = label("", "pass-count");
        private final Label pendingCount = label("", "pass-count");
        private final SelectorPestanas tabs;
        private List<TemporaryPass> dataRows = List.of();
        private int selected;
        private String query = "";

        PassView() {
            var icon = new StackPane(Icons.icon("ticket", 22));
            icon.getStyleClass().add("pass-banner-mark");
            var title = new VBox(3, label("Ticketera", "pass-banner-title"),
                    label("Pases de acceso con vigencia limitada.", "pass-banner-copy"));
            var banner = row(15, icon, title, spacer(), count(approvedCount, "Emitidos"), count(pendingCount, "Pendientes"));
            banner.getStyleClass().add("pass-banner");
            layout.add("summarySlot", banner);

            selected = shell.profile() == Profile.EMPLOYEE ? PENDING : ARCHIVE;
            tabs = new SelectorPestanas(List.of("Comprobantes", "Pendientes", "Rechazados"), index -> {
                selected = index;
                refresh();
            });
            var search = new CampoBusqueda("Folio, nombre o documento", value -> {
                query = value;
                if (shell.profile() != Profile.SUPERVISOR) refresh();
            });
            search.setPrefWidth(270);
            HBox tools = row(12, tabs, spacer(), search);
            tools.getStyleClass().add("pass-tools");
            if (shell.profile() == Profile.GUARD) tools.getChildren().add(button("Expedir pase", "plus", "primary-button",
                    () -> new GuardModules(shell).newRequest(() -> {
                        search.clear();
                        tabs.select(PENDING);
                        cargar();
                    }, true)));
            if (shell.profile() == Profile.SUPERVISOR) {
                search.onSubmit(this::cargar);
                tools.getChildren().add(button("Consultar documento", "search", "secondary-button", this::cargar));
            }
            layout.add("filterSlot", tools);
            tabs.select(selected);
            cargar();
        }

        private VBox count(Label value, String caption) {
            var box = new VBox(1, value, label(caption, "pass-count-label"));
            box.getStyleClass().add("pass-count-block");
            return box;
        }

        private void cargar() {
            if (shell.profile() == Profile.SUPERVISOR && query.isBlank()) return;
            var documento = query;
            shell.consultar(layout.slot("filterSlot"), () -> consultarPases(documento), pases -> {
                dataRows = pases;
                refresh();
            });
        }

        private void refresh() {
            var visible = dataRows;
            approvedCount.setText(Long.toString(visible.stream().filter(TemporaryPass::tieneComprobante).count()));
            pendingCount.setText(Long.toString(visible.stream().filter(pass -> pass.status() == Status.PENDING).count()));
            Status status = List.of(Status.APPROVED, Status.PENDING, Status.REJECTED).get(selected);
            var filtered = visible.stream().filter(pass -> (selected == ARCHIVE ? pass.tieneComprobante() : pass.status() == status)
                    && contains(pass.code() + " " + pass.person().name() + " " + pass.person().document(), query)).toList();
            VBox results = layout.slot("passesSlot");
            results.getChildren().clear();
            if (filtered.isEmpty()) {
                results.getChildren().add(new Tarjeta("white", new EstadoVacio(
                        query.isEmpty() ? emptyTitle(status) : "Sin coincidencias",
                        query.isEmpty() ? emptyCopy(status) : "Prueba con un folio, nombre o documento diferente.")));
                return;
            }

            if (selected == ARCHIVE) {
                var archive = new FlowPane(17, 20);
                archive.getStyleClass().add("pass-results");
                var now = data.now();
                for (var pass : filtered) {
                    var card = new VBox(9, new TicketPase(pass, now),
                            button("Ver comprobante", "arrow", "text-button", () -> showReceipt(pass, false)));
                    card.setPrefWidth(345);
                    archive.getChildren().add(card);
                }
                results.getChildren().add(archive);
            } else {
                for (var pass : filtered) results.getChildren().add(requestRow(pass));
            }
        }

        private Tarjeta requestRow(TemporaryPass pass) {
            Person person = pass.person();
            var dates = pass.tieneComprobante() ? dateTime(pass.start()) + "  →  " + dateTime(pass.end())
                    : pass.status() == Status.PENDING ? "La vigencia empieza cuando se aprueba." : "Sin vigencia autorizada." ;
            var badge = new IndicadorEstado(pass.status());
            var identity = row(11, new Portrait(person.name(), person.portrait(), 40),
                    textBlock(person.name(), person.company() + " · Doc. " + person.document()),
                    spacer(), label(pass.code(), "ticket-folio"), badge);
            var reason = label(pass.reason(), "row-title");
            reason.setWrapText(true);
            var text = new VBox(4, reason, label(dates, "muted"));
            var detail = row(12, text, spacer());
            if (pass.status() == Status.PENDING && canReview(pass)) {
                detail.getChildren().addAll(
                        button("Rechazar", "close", "danger-button", () -> review(pass, false)),
                        button("Aprobar y emitir", "check", "primary-button", () -> review(pass, true)));
            }
            var request = new Tarjeta("white", identity, rule(), detail);
            request.getStyleClass().add("pass-request");
            return request;
        }

        private void review(TemporaryPass pass, boolean approved) {
            String title = approved ? "Aprobar pase temporal" : "Rechazar pase temporal";
            var motivo = field("Motivo", "");
            var body = new VBox(10, new Tarjeta("soft", textBlock(pass.person().name(), pass.reason())));
            if (!approved) body.getChildren().add(fieldGroup("Motivo (opcional)", motivo));
            var c = shell.conexion;
            shell.formulario(title, approved ? "El pase será válido durante 30 minutos como máximo."
                    : "La decisión quedará disponible en portería.", body,
                    approved ? "Aprobar y emitir" : "Confirmar rechazo", () -> {
                        String razon = motivo.getText().strip();
                        return () -> approved ? c.app.getSolicitudes().aprobar(c.usuario, pass.id())
                                : c.app.getSolicitudes().rechazar(c.usuario, pass.id(), razon);
                    }, guardado -> {
                        tabs.select(approved ? ARCHIVE : 2);
                        cargar();
                        if (approved) showReceipt(pase(guardado, pass.person(), c.sesion.getNombre(), Status.APPROVED), true);
                        else shell.notify("Pase rechazado", pass.code() + " · " + pass.person().name());
                    });
        }
    }

    private void showReceipt(TemporaryPass pass, boolean animated) {
        var ticket = new TicketPase(pass, data.now());
        var title = label(animated ? "Pase aprobado" : "Comprobante de acceso", "card-title");
        var wrapper = new VBox(17, new VBox(7, title,
                label(pass.code(), "muted")), ticket,
                new Tarjeta("soft", textBlock("Motivo", pass.reason()), textBlock("Expedido por", pass.issuer())));
        wrapper.getStyleClass().add("pass-receipt");
        shell.drawer("Pase temporal", wrapper);
        if (animated) ticket.animateTear(() -> title.setText("Guardado en la ticketera"));
    }

    private List<TemporaryPass> consultarPases(String documento) {
        var c = shell.conexion;
        return switch (shell.profile()) {
            case GUARD -> c.app.getAccesoPorSolicitud().listarRespuestas(c.usuario).stream()
                    .filter(fila -> fila.tipo() == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO)
                    .map(fila -> {
                        var persona = ConexionInterfaz.persona(fila.persona(), fila.documento(), fila.empresa());
                        var fin = fila.aprobacion() == null ? null : finPase(fila.aprobacion());
                        return new TemporaryPass(fila.id(), persona, "Carné olvidado", fila.aprobacion(), fin,
                                c.sesion.getNombre(), fila.funcionario(), ConexionInterfaz.estado(fila.estado(), false));
                    }).toList();
            case EMPLOYEE -> c.app.getSolicitudes().listarAsignadas(c.usuario).stream()
                    .filter(d -> d.getSolicitud().getTipoIngreso() == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO)
                    .map(d -> pase(d.getSolicitud(), ConexionInterfaz.persona(d.getPersona(), c.sesion.getNombreEmpresa()),
                            c.sesion.getNombre(), ConexionInterfaz.estado(d.getEstado().getNombreEstado(), false))).toList();
            case SUPERVISOR -> c.app.getSeguridad().consultarVisitasDePersona(c.usuario, documento).stream()
                    .filter(d -> d.getVisita().getTipoIngreso() == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO)
                    .map(d -> pase(d.getVisita(), ConexionInterfaz.persona(d.getPersona(), d.getEmpresa()),
                            d.getFuncionario().orElse("Empresa destinataria"),
                            ConexionInterfaz.estado(d.getEstado().getNombreEstado(), false))).toList();
            case ADMIN -> List.of();
        };
    }

    private static TemporaryPass pase(Visita visita, Person persona, String responsable, Status estado) {
        var inicio = visita.getFechaAprobacion();
        var fin = inicio == null ? null : new HorarioCarnetOlvidado().calcular(visita).getHasta();
        return new TemporaryPass(visita.getId(), persona, "Carné olvidado", inicio, fin, "Portería", responsable, estado);
    }

    private static LocalDateTime finPase(LocalDateTime aprobacion) {
        LocalDateTime fin = aprobacion.plusMinutes(30);
        LocalDateTime finDia = aprobacion.toLocalDate().atTime(LocalTime.MAX);
        return fin.isBefore(finDia) ? fin : finDia;
    }

    private static String dateTime(LocalDateTime value) { return SupervisorModules.stamp(value.toLocalDate(), value.toLocalTime()); }

    private static String emptyTitle(Status status) {
        return switch (status) {
            case APPROVED -> "La ticketera está vacía";
            case PENDING -> "Sin pases pendientes";
            default -> "Sin pases rechazados";
        };
    }

    private static String emptyCopy(Status status) {
        return switch (status) {
            case APPROVED -> "Los comprobantes aparecen aquí cuando la empresa aprueba el pase.";
            case PENDING -> "Los pases que esperan aprobación aparecerán aquí.";
            default -> "Aquí quedan disponibles los pases que la empresa rechazó.";
        };
    }
}
