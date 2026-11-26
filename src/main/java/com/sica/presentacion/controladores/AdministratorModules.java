package com.sica.presentacion.controladores;

import com.sica.model.application.usecase.UsuarioCase.DatosCuentas;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Permiso;
import com.sica.model.domain.entity.Rol;
import com.sica.presentacion.componentes.forms.SelectorFoto;
import com.sica.presentacion.componentes.branding.Icons;
import com.sica.presentacion.componentes.branding.Portrait;
import com.sica.presentacion.componentes.cards.*;
import com.sica.presentacion.componentes.forms.CampoBusqueda;
import com.sica.presentacion.componentes.forms.Tabla;
import com.sica.presentacion.integracion.ConexionInterfaz;
import com.sica.presentacion.integracion.SicaData;
import com.sica.presentacion.integracion.SicaData.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.scene.Parent;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import static com.sica.presentacion.componentes.ElementosUi.*;
import static com.sica.presentacion.controladores.ModuleController.*;

final class AdministratorModules {
    private final ShellController shell;

    AdministratorModules(ShellController shell) { this.shell = shell; }

    Parent companies() {
        var screen = new ScreenLayout("administrator/companies");
        FlowPane directory = new FlowPane(18, 18);
        var search = new CampoBusqueda("Buscar por empresa o funcionario",
                query -> mostrarEmpresas(directory, query, () -> cargarEmpresas(directory, query)));
        Runnable refresh = () -> cargarEmpresas(directory, search.query());
        screen.add("filterSlot", new Tarjeta("white", filterRow(search, spacer(),
                button("Registrar empresa", "plus", "primary-button", () -> companyForm(null, refresh)))));
        screen.add("directorySlot", directory);
        refresh.run();
        return screen.view;
    }

    private void cargarEmpresas(FlowPane directory, String query) {
        var conexion = shell.conexion;
        shell.consultar(directory, () -> conexion.app.getEmpresas().listar(conexion.usuario), empresas -> {
            shell.data.companies.setAll(empresas.stream().map(ConexionInterfaz::empresa).toList());
            mostrarEmpresas(directory, query, () -> cargarEmpresas(directory, query));
        });
    }

    private void mostrarEmpresas(FlowPane directory, String query, Runnable refresh) {
        directory.getChildren().clear();
        for (Company company : shell.data.companies) {
            if (!contains(company.name() + " " + company.officer(), query)) continue;
            StackPane mark = new StackPane(Icons.icon(company.mark(), 28));
            mark.getStyleClass().addAll("company-mark", "company-" + company.mark());
            if (company.logo() != null) {
                Image imagen = new Image(new java.io.ByteArrayInputStream(company.logo()), 42, 42, true, true);
                if (!imagen.isError()) mark.getChildren().setAll(new ImageView(imagen));
            }
            var card = new Tarjeta("white", row(10, mark, spacer(), new IndicadorEstado(company.status())),
                    new VBox(3, label(company.name(), "card-title"), label(company.officer(), "muted")),
                    rule(), button("Ver empresa", "arrow", "text-button", () -> companyDetail(company, refresh)));
            card.setPrefWidth(278);
            directory.getChildren().add(card);
        }
        if (directory.getChildren().isEmpty()) directory.getChildren().add(
                new EstadoVacio("Sin empresas", "No hay empresas que coincidan con la búsqueda."));
    }

    private void companyDetail(Company company, Runnable refresh) {
        var opciones = new VBox(10, new Tarjeta("soft", textBlock("Contacto principal", company.officer()),
                new IndicadorEstado(company.status())),
                button("Actualizar empresa", "file", "secondary-button", () -> companyForm(company, refresh)));
        if (company.status() == Status.ACTIVE) opciones.getChildren().add(
                button("Desactivar empresa", null, "danger-button", () -> desactivar(company, refresh)));
        shell.drawer(company.name(), opciones);
    }

    private void companyForm(Company existente, Runnable refresh) {
        var conexion = shell.conexion;
        var nombre = field("Nombre de la empresa", existente == null ? "" : existente.name());
        var contacto = field("Contacto principal", existente == null || "Sin contacto".equals(existente.officer()) ? "" : existente.officer());
        var logo = new SelectorFoto("Logo de la empresa", "Ningún archivo seleccionado");
        String accion = existente == null ? "Registrar empresa" : "Guardar cambios";
        shell.formulario(existente == null ? accion : "Actualizar empresa", "Datos de la empresa en Zona ACME.",
                new VBox(11, fieldGroup("Nombre de la empresa", nombre), fieldGroup("Contacto principal", contacto), logo),
                accion, () -> {
                    var n = nombre.getText().strip();
                    var c = contacto.getText().strip();
                    var archivo = logo.getArchivo();
                    return () -> {
                        var e = new Empresa(existente == null ? null : existente.id(), n, c,
                                existente == null || existente.status() == Status.ACTIVE, SelectorFoto.prepararLogo(archivo));
                        return existente == null ? conexion.app.getEmpresas().registrar(conexion.usuario, e)
                                : conexion.app.getEmpresas().actualizar(conexion.usuario, e);
                    };
                }, empresa -> {
                    refresh.run();
                    companyDetail(ConexionInterfaz.empresa(empresa), refresh);
                    shell.notify("Empresa guardada", empresa.getNombre());
                });
    }

    private void desactivar(Company empresa, Runnable refresh) {
        var conexion = shell.conexion;
        var motivo = field("Motivo", "");
        shell.formulario("Desactivar empresa", empresa.name(), fieldGroup("Motivo", motivo), "Desactivar", () -> {
            String razon = motivo.getText().strip();
            return () -> conexion.app.getEmpresas().desactivar(conexion.usuario, empresa.id(), razon);
        }, actualizada -> {
            refresh.run();
            companyDetail(ConexionInterfaz.empresa(actualizada), refresh);
            shell.notify("Empresa desactivada", actualizada.getNombre());
        });
    }

    Parent accounts() {
        var conexion = shell.conexion;
        var screen = new ScreenLayout("administrator/accounts");
        Tabla<Account> table = new Tabla<>();
        var search = new CampoBusqueda("Buscar por nombre o correo", ignored -> {});
        String todos = "Todos los perfiles";
        var options = new java.util.ArrayList<String>();
        options.add(todos);
        for (Profile profile : Profile.values()) options.add(profile.title);
        var roles = select("Filtrar por perfil", options, todos);
        Runnable filtrar = () -> table.show(shell.data.accounts.stream().filter(account ->
                contains(account.name() + " " + account.email(), search.query())
                        && (todos.equals(roles.getValue()) || account.role().title.equals(roles.getValue()))).toList());
        var directorio = new javafx.beans.property.SimpleObjectProperty<DatosCuentas>();
        Runnable refresh = () -> shell.consultar(table, () -> conexion.app.getUsuarios().consultarDirectorio(conexion.usuario), datos -> {
            directorio.set(datos);
            var catalogo = datos.roles().stream().collect(Collectors.toMap(Rol::getId, Rol::getNombreRol));
            var empresas = datos.empresas().stream().collect(Collectors.toMap(Empresa::getId, Empresa::getNombre));
            shell.data.accounts.setAll(datos.usuarios().stream().filter(u -> catalogo.containsKey(u.getRolId())).map(u ->
                    new Account(u.getId(), u.getNombre(), u.getEmail(), SicaData.perfil(catalogo.get(u.getRolId())),
                            u.getEmpresaId() == null ? "Zona ACME" : empresas.getOrDefault(u.getEmpresaId(), "—"),
                            u.isEstaActivo() ? Status.ACTIVE : Status.INACTIVE, -1, u.getFotoPerfil())).toList());
            filtrar.run();
        });
        search.onSubmit(filtrar);
        roles.setOnAction(event -> filtrar.run());
        table.cells("Cuenta", 245, account -> row(9, new Portrait(account.name(), account.portrait(), 33, account.foto()),
                textBlock(account.name(), account.email())));
        table.text("Rol", 160, account -> account.role().title);
        table.text("Empresa", 105, account -> account.company().equals("Zona ACME") ? "—" : account.company());
        table.cells("Estado", 85, account -> new IndicadorEstado(account.status()));
        table.cells("Acciones", 142, account -> {
            var acciones = row(4, button("Editar", null, "text-button", () -> accountDialog(account, directorio.get(), refresh)));
            if (account.role() != Profile.ADMIN && account.status() == Status.ACTIVE)
                acciones.getChildren().add(button("Desactivar", null, "text-button", () -> desactivarCuenta(account, refresh)));
            return acciones;
        });
        var registrar = button("Nueva cuenta", "plus", "primary-button", () -> accountDialog(null, directorio.get(), refresh));
        registrar.disableProperty().bind(directorio.isNull());
        screen.add("filterSlot", new Tarjeta("white", filterRow(search, roles,
                button("Buscar", null, "secondary-button", filtrar), spacer(), registrar)));
        screen.add("accountsSlot", new Tarjeta("white", heading("Directorio de cuentas", null), table));
        refresh.run();
        return screen.view;
    }

    private void accountDialog(Account existing, DatosCuentas datos, Runnable refresh) {
        var conexion = shell.conexion;
        var name = field("Nombre completo", existing == null ? "" : existing.name());
        var email = field("Correo", existing == null ? "" : existing.email());
        var role = select("Selecciona un perfil", datos.roles(), null, Rol::getNombreRol);
        var company = select("Selecciona una empresa", datos.empresas().stream().filter(Empresa::isEstaActiva).toList(),
                null, Empresa::getNombre);
        PasswordField clave = new PasswordField();
        var foto = new SelectorFoto("Foto de perfil", "Ningún archivo seleccionado");
        var form = new VBox(11, fieldGroup("Nombre completo", name), fieldGroup("Correo", email));
        if (existing == null) {
            var empresa = fieldGroup("Empresa", company);
            empresa.visibleProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                    () -> role.getValue() != null && "Funcionario de Empresa".equals(role.getValue().getNombreRol()), role.valueProperty()));
            empresa.managedProperty().bind(empresa.visibleProperty());
            form.getChildren().addAll(fieldGroup("Contraseña", clave), fieldGroup("Perfil", role), empresa);
        } else {
            form.getChildren().add(textBlock(existing.role().title, existing.company()));
        }
        form.getChildren().add(foto);
        String action = existing == null ? "Crear cuenta" : "Guardar cambios";
        shell.formulario(existing == null ? "Nueva cuenta" : "Editar cuenta", "Datos de acceso y perfil en SICA.", form, action, () -> {
            String n = name.getText().strip(), correo = email.getText().strip();
            var archivo = foto.getArchivo();
            if (existing != null) return () -> conexion.app.getUsuarios().actualizar(conexion.usuario, existing.id(), n, correo,
                    SelectorFoto.prepararPerfil(archivo));
            var rol = require(role.getValue(), "Selecciona un perfil.");
            Integer empresa = "Funcionario de Empresa".equals(rol.getNombreRol())
                    ? require(company.getValue(), "Selecciona la empresa del funcionario.").getId() : null;
            char[] contrasena = clave.getText().toCharArray();
            clave.clear();
            return () -> {
                try { return conexion.app.getUsuarios().registrar(conexion.usuario, n, correo, contrasena,
                        rol.getId(), empresa, SelectorFoto.prepararPerfil(archivo)); }
                finally { Arrays.fill(contrasena, '\0'); }
            };
        }, cuenta -> {
            refresh.run();
            shell.notify("Cuenta guardada", cuenta.getNombre());
        });
    }

    private void desactivarCuenta(Account cuenta, Runnable refresh) {
        var conexion = shell.conexion;
        var motivo = field("Motivo", "");
        shell.formulario("Desactivar cuenta", cuenta.name(), fieldGroup("Motivo", motivo), "Desactivar", () -> {
            String razon = motivo.getText().strip();
            return () -> conexion.app.getUsuarios().desactivar(conexion.usuario, cuenta.id(), razon);
        }, actualizada -> {
            refresh.run();
            shell.notify("Cuenta desactivada", actualizada.getNombre());
        });
    }

    Parent roles() {
        var conexion = shell.conexion;
        var screen = new ScreenLayout("administrator/roles");
        var selected = select("Perfil", List.<Rol>of(), null, Rol::getNombreRol);
        var group = new ToggleGroup();
        var all = new RadioButton("Todos los permisos");
        var enabled = new RadioButton("Solo habilitados");
        all.setToggleGroup(group);
        enabled.setToggleGroup(group);
        all.setSelected(true);
        var filtros = new Tarjeta("white", filterRow(fieldGroup("Perfil", selected), spacer(), all, enabled));
        screen.add("filterSlot", filtros);
        VBox results = screen.slot("permissionsSlot");
        shell.consultar(filtros, () -> conexion.app.getRolesPermisos().consultarCatalogo(conexion.usuario), catalogo -> {
            Runnable refresh = () -> {
                Rol rol = selected.getValue();
                if (rol == null) return;
                shell.consultar(filtros, () -> conexion.app.getRolesPermisos().consultarPermisosDeRol(conexion.usuario, rol.getId()),
                        asignados -> mostrarPermisos(rol, catalogo.permisos(), asignados, enabled.isSelected(), results));
            };
            selected.setOnAction(event -> refresh.run());
            all.setOnAction(event -> refresh.run());
            enabled.setOnAction(event -> refresh.run());
            selected.getItems().setAll(catalogo.roles());
            if (!catalogo.roles().isEmpty()) selected.setValue(catalogo.roles().getFirst());
        });
        return screen.view;
    }

    private void mostrarPermisos(Rol rol, List<Permiso> catalogo, List<Permiso> asignados, boolean soloHabilitados, VBox results) {
        var conexion = shell.conexion;
        var ids = asignados.stream().map(Permiso::getId).collect(Collectors.toSet());
        VBox entries = new VBox();
        for (Permiso permiso : catalogo) {
            boolean activo = ids.contains(permiso.getId());
            if (soloHabilitados && !activo) continue;
            var toggle = new ToggleButton();
            toggle.getStyleClass().add("switch");
            var thumb = new Region();
            thumb.getStyleClass().add("switch-thumb");
            toggle.setGraphic(thumb);
            String titulo = permiso.getNombrePermiso().replace('_', ' ');
            toggle.setAccessibleText(titulo);
            toggle.setSelected(activo);
            toggle.setOnAction(event -> {
                boolean habilitar = toggle.isSelected();
                shell.consultar(toggle, () -> habilitar
                        ? conexion.app.getRolesPermisos().asignarPermiso(conexion.usuario, rol.getId(), permiso.getId())
                        : conexion.app.getRolesPermisos().retirarPermiso(conexion.usuario, rol.getId(), permiso.getId()),
                        cambio -> {
                            shell.notify(habilitar ? "Permiso habilitado" : "Permiso retirado", titulo);
                            if (soloHabilitados && !habilitar) entries.getChildren().remove(toggle.getParent());
                        }, error -> {
                            toggle.setSelected(!habilitar);
                            shell.notify("No se pudo cambiar el permiso", error);
                        });
            });
            var line = row(10, Icons.icon("key", 17), textBlock(titulo, Objects.toString(permiso.getDescripcion(), "")), spacer(), toggle);
            line.getStyleClass().add("list-row");
            entries.getChildren().add(line);
        }
        results.getChildren().setAll(new Tarjeta("white", heading("Permisos de " + rol.getNombreRol(), null),
                entries.getChildren().isEmpty() ? new EstadoVacio("Sin permisos habilitados", "Selecciona otro perfil o muestra todos los permisos.") : entries));
    }
}
