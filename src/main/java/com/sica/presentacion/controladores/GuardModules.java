package com.sica.presentacion.controladores;

import com.sica.model.application.usecase.ControlSolicitudCase.RespuestaEnPorteria;
import com.sica.model.application.usecase.FuncionarioDisponible;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.TipoPersona;
import com.sica.model.domain.repository.MovimientoPorteria;
import com.sica.presentacion.componentes.forms.SelectorFoto;
import com.sica.presentacion.animaciones.Motion;
import com.sica.presentacion.componentes.branding.Icons;
import com.sica.presentacion.componentes.branding.Portrait;
import com.sica.presentacion.componentes.cards.*;
import com.sica.presentacion.componentes.forms.CampoBusqueda;
import com.sica.presentacion.componentes.forms.SelectorPestanas;
import com.sica.presentacion.componentes.forms.Tabla;
import com.sica.presentacion.integracion.ConexionInterfaz;
import com.sica.presentacion.integracion.SicaData;
import com.sica.presentacion.integracion.SicaData.Status;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import static com.sica.presentacion.componentes.ElementosUi.*;
import static com.sica.presentacion.controladores.ModuleController.*;

final class GuardModules {
    private final ShellController shell;
    private static final List<MovimientoPorteria.Tipo> TIPOS = List.of(MovimientoPorteria.Tipo.ENTRADA_INVITADO,
            MovimientoPorteria.Tipo.INGRESO_LABORAL, MovimientoPorteria.Tipo.SALIDA);

    GuardModules(ShellController shell) { this.shell = shell; }

    Parent records() {
        var screen = new ScreenLayout("guard/records");
        VBox result = new VBox();
        show(result, new EstadoVacio("Consulta un documento", "Ingresa el número de documento de la persona."));
        VBox movements = new VBox();
        var category = new SimpleIntegerProperty(0);
        Runnable refresh = () -> {
            var tipo = TIPOS.get(category.get());
            shell.consultar(movements, () -> shell.conexion.movimientosPorteria(tipo), filas -> {
                if (tipo != TIPOS.get(category.get())) return;
                movements.getChildren().setAll(filas.stream().limit(9).map(DashboardController::movementRow).toList());
                if (filas.isEmpty()) movements.getChildren().add(label("No hay movimientos de este tipo hoy.", "muted"));
            });
        };
        var searchRow = row(9);
        var version = new SimpleIntegerProperty();
        var buscador = documentSearch(query -> {
            int solicitud = version.get() + 1;
            version.set(solicitud);
            buscarRegistro(query, category.get(), result, refresh, () -> version.get() == solicitud);
        }, searchRow);
        var segments = new SelectorPestanas(List.of("Entrada", "Ingreso laboral", "Salida"), index -> {
            category.set(index);
            version.set(version.get() + 1);
            movements.getChildren().clear();
            refresh.run();
            show(result, new EstadoVacio("Consulta un documento", "Pulsa Buscar para verificar este movimiento."));
        });
        buscador.queryProperty().addListener((obs, antes, actual) -> {
            version.set(version.get() + 1);
            result.getChildren().clear();
        });
        screen.add("searchSlot", new Tarjeta("white", segments, searchRow, result));
        screen.add("movementsSlot", new Tarjeta("white", heading("Movimientos de hoy", Icons.icon("clock", 18)), movements));
        refresh.run();
        return screen.view;
    }

    private void buscarRegistro(String documento, int modo, VBox resultado, Runnable refresh, BooleanSupplier vigente) {
        var c = shell.conexion;
        if (documento.isBlank()) {
            show(resultado, new EstadoVacio("Introduce un documento", "El documento es obligatorio."));
            return;
        }
        if (modo == 2) {
            shell.consultar(resultado, () -> c.app.getSalidas().consultarIngresoAbierto(c.usuario, documento), ingreso -> {
                if (!vigente.getAsBoolean()) return;
                if (ingreso.isEmpty()) show(resultado, new EstadoVacio("Sin ingreso abierto", "Verifica el número de documento."));
                else show(resultado, new Tarjeta("soft", textBlock("Ingreso #" + ingreso.get().getId(),
                        "Documento " + documento + " · " + hora(ingreso.get().getFechaEntrada())),
                        button("Confirmar salida", "check", "primary-button", () ->
                                shell.consultar(resultado, () -> c.app.getSalidas().registrarSalida(c.usuario, documento, ingreso.get().getId()),
                                        visita -> confirmado(resultado, "Salida registrada", hora(visita.getFechaSalida()), refresh)))));
            });
            return;
        }
        shell.consultar(resultado, () -> c.app.getAccesoProgramado().consultar(c.usuario, documento), consulta -> {
            if (!vigente.getAsBoolean()) return;
            if (consulta.isEmpty()) {
                show(resultado, new EstadoVacio("Sin resultados", "Si llegó sin cita, envía una solicitud."));
                return;
            }
            var datos = consulta.get();
            var persona = datos.getPersona();
            var identidad = row(12, new Portrait(persona.getNombre(), -1, 54, datos.getFotoArchivo()),
                    textBlock(persona.getNombre(), "Documento " + persona.getDocumentoIdentidad()),
                    spacer(), label(datos.getEstadoAcceso(), "row-title"));
            VBox opciones = new VBox(10);
            if (modo == 1) {
                if (persona.getTipoPersona() != TipoPersona.TRABAJADOR || !Boolean.TRUE.equals(persona.getVinculacionLaboralActiva())) {
                    opciones.getChildren().add(label("No figura como trabajador con vínculo activo.", "muted"));
                } else if ("Activo".equals(datos.getEstadoAcceso())) {
                    var carnet = new CheckBox("Verifiqué el carné del trabajador");
                    var placa = field("Placa", "");
                    var registrar = button("Confirmar ingreso laboral", "check", "primary-button", () -> {
                        String vehiculo = placa.getText().strip();
                        shell.consultar(resultado, () -> c.app.getAccesoTrabajador().registrarEntrada(c.usuario, documento, true, vehiculo),
                                visita -> confirmado(resultado, "Ingreso laboral registrado", hora(visita.getFechaEntrada()), refresh));
                    });
                    registrar.disableProperty().bind(carnet.selectedProperty().not());
                    opciones.getChildren().addAll(fieldGroup("Placa (opcional)", placa), carnet, registrar);
                }
            } else {
                for (var cita : datos.getVisitas()) {
                    var fila = row(10, Icons.icon("clock", 17),
                            textBlock(cita.getEmpresa() + " · " + cita.getFuncionario(), cita.getMensaje() + " · "
                                    + hora(cita.getHorario().getDesde()) + " a " + hora(cita.getHorario().getHasta())), spacer());
                    var confirmar = button("Confirmar entrada", "check", "primary-button", () ->
                            shell.consultar(resultado, () -> c.app.getAccesoProgramado().registrarEntrada(c.usuario, documento, cita.getVisita().getId()),
                                    visita -> confirmado(resultado, "Entrada registrada", hora(visita.getFechaEntrada()), refresh)));
                    confirmar.setDisable(!cita.isPermiteIngreso());
                    fila.getChildren().add(confirmar);
                    opciones.getChildren().add(fila);
                }
                if (datos.getVisitas().isEmpty()) opciones.getChildren().add(label("No hay citas programadas pendientes de ingreso.", "muted"));
            }
            show(resultado, new Tarjeta("soft", identidad, rule(), opciones));
        });
    }

    Parent requests() {
        var screen = new ScreenLayout("guard/requests");
        var table = new Tabla<RespuestaEnPorteria>();
        var search = new CampoBusqueda("Buscar por persona o documento", ignored -> {});
        var filas = new javafx.beans.property.SimpleObjectProperty<List<RespuestaEnPorteria>>(List.of());
        Runnable filtrar = () -> table.show(filas.get().stream().filter(s ->
                contains(s.persona() + " " + s.documento() + " " + s.id(), search.query())).toList());
        var cargando = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable refresh = () -> {
            if (!cargando.compareAndSet(false, true)) return;
            shell.consultar(null, () -> shell.conexion.app.getAccesoPorSolicitud().listarRespuestas(shell.conexion.usuario), datos -> {
                cargando.set(false);
                if (!datos.equals(filas.get()) || table.getItems().isEmpty()) {
                    filas.set(datos);
                    filtrar.run();
                }
            }, error -> {
                cargando.set(false);
                shell.notify("No se pudieron consultar las solicitudes", error);
            });
        };
        search.onSubmit(filtrar);
        table.text("Folio", 66, s -> "#" + s.id());
        table.cells("Persona", 190, s -> textBlock(s.persona(), s.documento()));
        table.text("Empresa", 115, RespuestaEnPorteria::empresa);
        table.text("Funcionario", 125, RespuestaEnPorteria::funcionario);
        table.cells("Estado", 102, s -> new IndicadorEstado(ConexionInterfaz.estado(s.estado(), false)));
        table.cells("Acciones", 140, s -> button("Ver respuesta", "arrow", "text-button", () -> respuesta(s, refresh)));
        screen.add("filterSlot", new Tarjeta("white", filterRow(search, spacer(),
                button("Nueva solicitud", "plus", "primary-button", () -> newRequest(refresh)))));
        screen.add("requestsSlot", new Tarjeta("white", heading("Solicitudes enviadas", null), table));
        refresh.run();
        Timeline actualizar = new Timeline(new KeyFrame(Duration.seconds(5), evento -> refresh.run()));
        actualizar.setCycleCount(Timeline.INDEFINITE);
        actualizar.play();
        shell.alDejarPantalla(actualizar::stop);
        return screen.view;
    }

    private void respuesta(RespuestaEnPorteria fila, Runnable refresh) {
        var c = shell.conexion;
        shell.consultar(null, () -> c.app.getAccesoPorSolicitud().consultarResultado(c.usuario, fila.id()), dato -> {
            var contenido = new VBox(12, new Tarjeta("soft", textBlock(dato.getPersona().getNombre(),
                    dato.getEmpresa() + " · " + dato.getFuncionario()),
                    new IndicadorEstado(ConexionInterfaz.estado(dato.getEstado().getNombreEstado(), false))),
                    label(dato.getMensaje(), "muted"));
            dato.getHorario().ifPresent(h -> contenido.getChildren().add(label(
                    "Horario: " + hora(h.getDesde()) + " a " + hora(h.getHasta()), "muted")));
            if (dato.isPermiteIngreso()) {
                var documento = field("Documento presentado", "");
                contenido.getChildren().addAll(fieldGroup("Documento presentado", documento),
                        button("Registrar entrada", "check", "primary-button", () -> {
                            String d = documento.getText().strip();
                            shell.consultar(contenido, () -> c.app.getAccesoPorSolicitud().registrarEntrada(c.usuario, fila.id(), d),
                                    visita -> confirmado(contenido, "Entrada registrada", hora(visita.getFechaEntrada()), refresh));
                        }));
            }
            shell.drawer("Solicitud #" + fila.id(), contenido);
        });
    }

    void newRequest(Runnable onCreated) { newRequest(onCreated, false); }

    void newRequest(Runnable onCreated, boolean carnetOlvidado) {
        var c = shell.conexion;
        shell.consultar(null, () -> c.app.getDestinos().listarEmpresas(c.usuario),
                empresas -> solicitud(empresas, onCreated, carnetOlvidado));
    }

    private void solicitud(List<Empresa> empresas, Runnable onCreated, boolean carnetOlvidado) {
        var c = shell.conexion;
        String visita = "Visita sin aviso", olvido = "Carné olvidado";
        var tipo = select("Tipo de solicitud", List.of(visita, olvido), carnetOlvidado ? olvido : visita);
        var documento = field("Documento", "");
        var nombre = field("Nombre completo", "");
        var foto = new SelectorFoto("Foto del invitado", "Ningún archivo seleccionado");
        var placa = field("Placa", "");
        var empresa = select("Selecciona una empresa", empresas, null, Empresa::getNombre);
        var funcionario = select("Selecciona un funcionario", List.<FuncionarioDisponible>of(), null, FuncionarioDisponible::getNombre);
        empresa.setOnAction(evento -> {
            var elegida = empresa.getValue();
            funcionario.getItems().clear();
            if (elegida != null) shell.consultar(funcionario, () -> c.app.getDestinos().listarFuncionarios(c.usuario, elegida.getId()), destinos -> {
                if (elegida == empresa.getValue()) funcionario.getItems().setAll(destinos);
            });
        });
        var invitado = new VBox(9, fieldGroup("Nombre (si es un invitado nuevo)", nombre), foto);
        invitado.visibleProperty().bind(tipo.valueProperty().isEqualTo(visita));
        invitado.managedProperty().bind(invitado.visibleProperty());
        VBox form = new VBox(9, fieldGroup("Tipo", tipo), fieldGroup("Documento", documento), invitado,
                row(10, fieldGroup("Empresa", empresa), fieldGroup("Funcionario destinatario", funcionario)),
                fieldGroup("Placa (opcional)", placa));
        shell.formulario("Nueva solicitud", "Selecciona a la persona y al funcionario que decidirá sobre el acceso.",
                form, "Enviar solicitud", () -> {
                    var destino = require(empresa.getValue(), "Selecciona una empresa.");
                    var receptor = require(funcionario.getValue(), "Selecciona un funcionario.");
                    var doc = documento.getText().strip();
                    var titular = nombre.getText().strip();
                    var vehiculo = placa.getText().strip();
                    var archivo = foto.getArchivo();
                    boolean laboral = olvido.equals(tipo.getValue());
                    return () -> laboral
                            ? c.app.getSolicitudes().solicitarPorCarnetOlvidado(c.usuario, doc, receptor.getId(), vehiculo)
                            : c.app.getSolicitudes().solicitarSinAgenda(c.usuario, destino.getId(), receptor.getId(), doc, titular,
                                    SelectorFoto.preparar(archivo), vehiculo);
                }, registrada -> {
                    onCreated.run();
                    shell.notify("Solicitud enviada", "Folio #" + registrada.getId());
                });
    }

    private void confirmado(VBox target, String titulo, String detalle, Runnable refresh) {
        var icono = Icons.icon("check-circle", 29);
        show(target, new Tarjeta("soft", row(11, icono, textBlock(titulo, detalle))));
        Motion.confirm(icono);
        refresh.run();
        shell.notify(titulo, detalle);
    }

    private static void show(VBox result, Node content) { result.getChildren().setAll(content); }
    private static String hora(LocalDateTime instante) { return instante.toLocalTime().format(SicaData.TIME); }
}
