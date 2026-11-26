package com.sica.presentacion.controladores;

import com.sica.model.domain.entity.Persona;
import com.sica.presentacion.componentes.forms.SelectorFoto;
import com.sica.presentacion.componentes.branding.Icons;
import com.sica.presentacion.componentes.calendar.CalendarioVisitas;
import com.sica.presentacion.componentes.cards.*;
import com.sica.presentacion.componentes.forms.CampoBusqueda;
import com.sica.presentacion.componentes.forms.Tabla;
import com.sica.presentacion.integracion.ConexionInterfaz;
import com.sica.presentacion.integracion.SicaData;
import com.sica.presentacion.integracion.SicaData.*;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import static com.sica.presentacion.componentes.ElementosUi.*;
import static com.sica.presentacion.controladores.ModuleController.*;

final class EmployeeModules {
    private final ShellController shell;

    EmployeeModules(ShellController shell) {
        this.shell = shell;
    }

    Parent calendar() {
        var c = shell.conexion;
        var screen = new ScreenLayout("employee/calendar");
        var details = new VBox(10);
        var citas = new javafx.beans.property.SimpleObjectProperty<List<Visit>>(List.of());
        var recargar = new javafx.beans.property.SimpleObjectProperty<Runnable>(() -> {});
        var calendar = new CalendarioVisitas(shell.data.today, citas::get, dia -> {
            details.getChildren().setAll(label(dia.format(SicaData.DATE), "date-text"));
            var agenda = citas.get().stream().filter(v -> dia.equals(v.date())).toList();
            if (agenda.isEmpty()) details.getChildren().add(new EstadoVacio("Día sin visitas", "Selecciona otra fecha para consultar la agenda."));
            agenda.forEach(visita -> {
                var fila = DashboardController.visitRow(visita);
                if (visita.status() == Status.SCHEDULED)
                    fila.getChildren().add(button("Gestionar", "right", "text-button", () -> gestionarVisita(visita, recargar.get())));
                details.getChildren().add(fila);
            });
        });
        recargar.set(() -> shell.consultar(calendar, () -> c.app.getVisitas().listarProgramadas(c.usuario), programadas -> {
            citas.set(programadas.stream().map(c::programada).toList());
            calendar.refresh();
        }));
        screen.add("calendarSlot", split(new Tarjeta("white", heading("Agenda de " + shell.data.empresaActual,
                button("Programar visita", "plus", "primary-button", () -> programar(recargar.get()))), calendar),
                new Tarjeta("aqua", heading("Visitas del día", Icons.icon("calendar", 18)), details), 388));
        calendar.refresh();
        recargar.get().run();
        return screen.view;
    }

    private void programar(Runnable refresh) {
        var c = shell.conexion;
        var documento = field("Documento", "");
        var nombre = field("Nombre completo", "");
        var placa = field("Placa", "");
        var fecha = SupervisorModules.date("Fecha");
        fecha.setValue(shell.data.today);
        var hora = field("Hora", "");
        hora.setPromptText("HH:mm");
        var foto = new SelectorFoto("Foto si es un invitado nuevo", "Ningún archivo seleccionado");
        var form = new VBox(10, fieldGroup("Documento", documento), fieldGroup("Nombre si es un invitado nuevo", nombre),
                foto, row(9, fieldGroup("Fecha", fecha), fieldGroup("Hora", hora)), fieldGroup("Placa (opcional)", placa));
        shell.formulario("Programar visita", "Indica quién llegará y a qué hora.", form, "Programar visita", () -> {
            var cita = momento(fecha, hora);
            String d = documento.getText().strip(), n = nombre.getText().strip(), p = placa.getText().strip();
            var archivo = foto.getArchivo();
            return () -> c.app.getVisitas().preregistrar(c.usuario, d, n, SelectorFoto.preparar(archivo), cita, p);
        }, visita -> {
            refresh.run();
            shell.notify("Visita programada", "Visita #" + visita.getId());
        });
    }

    private void gestionarVisita(Visit visita, Runnable refresh) {
        shell.drawer("Visita #" + visita.id(), new VBox(10,
                new Tarjeta("soft", textBlock(visita.person().name(), SicaData.fecha(visita))),
                button("Reprogramar visita", "calendar", "primary-button", () -> modificarVisita(visita, false, refresh)),
                button("Cancelar visita", "close", "danger-button", () -> modificarVisita(visita, true, refresh))));
    }

    private void modificarVisita(Visit visita, boolean cancelar, Runnable refresh) {
        var c = shell.conexion;
        var fecha = SupervisorModules.date("Fecha");
        fecha.setValue(visita.date());
        var hora = field("Hora", visita.time().format(SicaData.TIME));
        var motivo = field("Motivo", "");
        VBox form = new VBox(10);
        if (!cancelar) form.getChildren().add(row(10, fieldGroup("Nueva fecha", fecha), fieldGroup("Nueva hora", hora)));
        form.getChildren().add(fieldGroup("Motivo", motivo));
        String accion = cancelar ? "Cancelar visita" : "Reprogramar visita";
        shell.formulario(accion, visita.person().name(), form, accion, () -> {
            String razon = motivo.getText().strip();
            LocalDateTime cita = cancelar ? null : momento(fecha, hora);
            return () -> cancelar ? c.app.getVisitas().cancelar(c.usuario, visita.id(), razon)
                    : c.app.getVisitas().reprogramar(c.usuario, visita.id(), cita, razon);
        }, guardada -> {
            shell.cerrarDetalle();
            refresh.run();
            shell.notify(cancelar ? "Visita cancelada" : "Visita reprogramada", visita.person().name());
        });
    }

    Parent approvals() {
        var c = shell.conexion;
        var screen = new ScreenLayout("employee/approvals");
        var requests = screen.slot("requestsSlot");
        var filas = new javafx.beans.property.SimpleObjectProperty<List<Visit>>();
        var search = new CampoBusqueda("Buscar visitante", ignored -> {});
        var cargando = new AtomicBoolean();
        Runnable filtrar = () -> {
            var pendientes = filas.get();
            if (pendientes == null) return;
            var visibles = pendientes.stream().filter(v -> contains(v.person().name(), search.query()))
                    .map(visit -> solicitudCard(visit, () -> recargarSolicitudes(c, filas, cargando))).toList();
            requests.getChildren().setAll(visibles);
            if (visibles.isEmpty()) requests.getChildren().add(pendientes.isEmpty()
                    ? new EstadoVacio("Sin solicitudes pendientes", "Las decisiones quedan en el historial.")
                    : new EstadoVacio("Sin coincidencias", "Busca por otro nombre."));
        };
        filas.addListener((obs, antes, actual) -> filtrar.run());
        search.onSubmit(filtrar);
        screen.add("filterSlot", new Tarjeta("white", filterRow(search, spacer(),
                button("Buscar", null, "secondary-button", filtrar))));
        recargarSolicitudes(c, filas, cargando);
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(5), evento -> recargarSolicitudes(c, filas, cargando)));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        shell.alDejarPantalla(timer::stop);
        return screen.view;
    }

    private void recargarSolicitudes(ConexionInterfaz c, javafx.beans.property.ObjectProperty<List<Visit>> filas, AtomicBoolean cargando) {
        if (!cargando.compareAndSet(false, true)) return;
        shell.consultar(null, () -> c.app.getSolicitudes().listarPendientes(c.usuario), pendientes -> {
            cargando.set(false);
            var nuevas = pendientes.stream().map(c::solicitud).toList();
            if (!nuevas.equals(filas.get())) filas.set(nuevas);
        }, error -> {
            cargando.set(false);
            shell.notify("No se pudieron consultar las solicitudes", error);
        });
    }

    private Tarjeta solicitudCard(Visit visit, Runnable refresh) {
        return new Tarjeta("white", row(11, Icons.icon("file", 22), textBlock(visit.person().name(),
                visit.person().document() + " · " + visit.reason()), spacer(), new IndicadorEstado(visit.status())), rule(),
                row(10, label("Solicitud #" + visit.id(), "muted"), spacer(),
                        button("Rechazar", "close", "danger-button", () -> decidir(visit, false, refresh)),
                        button("Aprobar", "check", "primary-button", () -> decidir(visit, true, refresh))));
    }

    void decidir(Visit visit, boolean approved, Runnable refresh) {
        var c = shell.conexion;
        String action = approved ? "Aprobar solicitud" : "Rechazar solicitud";
        var motivo = field("Motivo", "");
        var form = new VBox(10, new Tarjeta("soft", textBlock(visit.person().name(), visit.reason())));
        if (!approved) form.getChildren().add(fieldGroup("Motivo (opcional)", motivo));
        shell.formulario(action, "La decisión quedará disponible en portería.", form, action, () -> {
            String razon = motivo.getText().strip();
            return () -> approved ? c.app.getSolicitudes().aprobar(c.usuario, visit.id())
                    : c.app.getSolicitudes().rechazar(c.usuario, visit.id(), razon);
        }, decidida -> {
            refresh.run();
            shell.notify(approved ? "Solicitud aprobada" : "Solicitud rechazada", visit.person().name());
        });
    }

    Parent history() {
        var c = shell.conexion;
        var screen = new ScreenLayout("employee/history");
        var table = visitTable();
        var statuses = List.of("Todos los estados", "Pendiente", "Aprobada", "Rechazada", "Programada", "Expirada", "Cancelada");
        var status = select("Estado", statuses, statuses.getFirst());
        var from = SupervisorModules.date("Desde");
        var to = SupervisorModules.date("Hasta");
        var search = new CampoBusqueda("Buscar visitante", ignored -> {});
        var filas = new javafx.beans.property.SimpleObjectProperty<List<Visit>>(List.of());
        Runnable refresh = () -> table.show(filas.get().stream()
                .filter(visit -> inRange(visit.date(), from, to) && contains(visit.person().name(), search.query())
                        && (status.getValue().equals(statuses.getFirst()) || visit.status().label.equals(status.getValue())))
                .sorted(Comparator.comparing(Visit::id).reversed()).toList());
        search.onSubmit(refresh);
        status.setOnAction(event -> refresh.run());
        from.setOnAction(event -> refresh.run());
        to.setOnAction(event -> refresh.run());
        screen.add("filterSlot", new Tarjeta("white", filterRow(search, fieldGroup("Estado", status),
                fieldGroup("Desde", from), fieldGroup("Hasta", to), button("Aplicar", null, "primary-button", refresh))));
        screen.add("historySlot", new Tarjeta("white", heading("Solicitudes y visitas",
                button("Mi actividad", "audit", "text-button", () -> shell.drawer("Mi actividad", new SupervisorModules(shell).audit(true)))), table));
        shell.consultar(table, () -> Stream.concat(c.app.getVisitas().listarProgramadas(c.usuario).stream().map(c::programada),
                c.app.getSolicitudes().listarAsignadas(c.usuario).stream().map(c::solicitud)).toList(), datos -> {
                    filas.set(datos);
                    refresh.run();
                });
        return screen.view;
    }

    Parent workers() {
        var c = shell.conexion;
        var screen = new ScreenLayout("employee/history");
        var table = new Tabla<Persona>();
        Runnable refresh = () -> shell.consultar(table, () -> c.app.getPersonas().listarTrabajadores(c.usuario), table::show);
        table.text("Nombre", 240, Persona::getNombre);
        table.text("Documento", 150, Persona::getDocumentoIdentidad);
        table.cells("Estado", 120, persona -> new IndicadorEstado(
                Boolean.TRUE.equals(persona.getVinculacionLaboralActiva()) ? Status.ACTIVE : Status.INACTIVE));
        table.cells("Acciones", 175, persona -> button("Ver trabajador", "right", "text-button",
                () -> workerDetail(persona, refresh)));
        screen.add("filterSlot", new Tarjeta("white", heading("Personal de mi empresa",
                button("Registrar trabajador", "plus", "primary-button", () -> workerForm(null, refresh)))));
        screen.add("historySlot", new Tarjeta("white", table));
        refresh.run();
        return screen.view;
    }

    private void workerDetail(Persona persona, Runnable refresh) {
        var panel = new VBox(9, new Tarjeta("soft", textBlock(persona.getNombre(), persona.getDocumentoIdentidad())));
        if (Boolean.TRUE.equals(persona.getVinculacionLaboralActiva())) panel.getChildren().addAll(
                button("Actualizar", "file", "primary-button", () -> workerForm(persona, refresh)),
                button("Finalizar vínculo", "close", "danger-button", () -> finalizar(persona, refresh)));
        shell.drawer("Trabajador", panel);
    }

    private void workerForm(Persona persona, Runnable refresh) {
        var c = shell.conexion;
        var nombre = field("Nombre", persona == null ? "" : persona.getNombre());
        var documento = field("Documento", persona == null ? "" : persona.getDocumentoIdentidad());
        var foto = new SelectorFoto("Foto", "Ningún archivo seleccionado");
        var form = new VBox(10, fieldGroup("Nombre completo", nombre));
        if (persona == null) form.getChildren().add(fieldGroup("Documento", documento));
        form.getChildren().add(foto);
        String accion = persona == null ? "Registrar trabajador" : "Actualizar trabajador";
        shell.formulario(accion, shell.data.empresaActual, form, "Guardar", () -> {
            String n = nombre.getText().strip(), d = documento.getText().strip();
            var archivo = foto.getArchivo();
            return () -> persona == null ? c.app.getPersonas().registrarTrabajador(c.usuario, n, d, SelectorFoto.preparar(archivo))
                    : c.app.getPersonas().actualizarTrabajador(c.usuario, persona.getId(), n, SelectorFoto.preparar(archivo));
        }, guardada -> {
            shell.cerrarDetalle();
            refresh.run();
            shell.notify("Trabajador guardado", guardada.getNombre());
        });
    }

    private void finalizar(Persona persona, Runnable refresh) {
        var c = shell.conexion;
        var motivo = field("Motivo", "");
        shell.formulario("Finalizar vínculo", persona.getNombre(), fieldGroup("Motivo", motivo), "Finalizar vínculo", () -> {
            String razon = motivo.getText().strip();
            return () -> c.app.getPersonas().finalizarVinculacionLaboral(c.usuario, persona.getId(), razon);
        }, guardada -> {
            shell.cerrarDetalle();
            refresh.run();
            shell.notify("Vínculo finalizado", guardada.getNombre());
        });
    }
}
