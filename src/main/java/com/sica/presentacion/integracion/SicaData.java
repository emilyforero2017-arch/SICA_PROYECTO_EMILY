package com.sica.presentacion.integracion;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

public final class SicaData {
    public static final Locale SPANISH = Locale.forLanguageTag("es-CO");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", SPANISH);
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);
    public final LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));

    public enum Route {
        HOME("Inicio", "home", ""), RECORDS("Registros", "scan", "Consulta un documento y registra un movimiento."),
        REQUESTS("Solicitudes", "file", "Consulta y gestiona las solicitudes de acceso."),
        PASSES("Pases temporales", "ticket", "Consulta las solicitudes de pase y sus comprobantes de acceso."),
        VISITS("Consulta de visitas", "search", "Revisa la información de una visita y sus incidentes."),
        RESTRICTIONS("Restricciones", "shield", "Consulta el estado de acceso de una persona."),
        INCIDENTS("Historial de incidentes", "warning", "Consulta las novedades registradas en Zona ACME."),
        AUDIT("Auditoría", "audit", "Explora la actividad de Zona ACME por tipo de acción y fecha."),
        COMPANIES("Empresas", "building", "Consulta las empresas y los funcionarios asociados."),
        ACCOUNTS("Cuentas", "users", "Gestiona las cuentas de SICA."),
        ROLES("Roles y permisos", "key", "Explora los permisos que corresponden a cada perfil."),
        CALENDAR("Calendario de visitas", "calendar", "Consulta la agenda de visitas por día."),
        APPROVALS("Autorizaciones", "check-circle", "Revisa las solicitudes de acceso de tu empresa."),
        HISTORY("Historial", "audit", "Consulta tus solicitudes, visitas y movimientos."),
        WORKERS("Trabajadores", "users", "Gestiona el personal vinculado a tu empresa."),
        ACCESSES("Accesos", "scan", "Consulta quién está dentro y el historial de ingresos y salidas.");

        public final String title;
        public final String icon;
        public final String description;

        Route(String title, String icon, String description) {
            this.title = title;
            this.icon = icon;
            this.description = description;
        }
    }

    public enum Profile {
        GUARD("Guarda de seguridad", "Carlos Ruiz", "Operación de accesos", 0,
                Route.HOME, Route.RECORDS, Route.REQUESTS, Route.PASSES),
        SUPERVISOR("Supervisor de seguridad", "Andrés Pérez", "Monitoreo y seguridad", 1,
                Route.HOME, Route.VISITS, Route.RESTRICTIONS, Route.PASSES, Route.INCIDENTS, Route.AUDIT, Route.ACCESSES),
        ADMIN("Superusuario", "Administrador SICA", "Administración de Zona ACME", 2,
                Route.HOME, Route.COMPANIES, Route.ACCOUNTS, Route.ROLES, Route.AUDIT),
        EMPLOYEE("Funcionario de empresa", "Valentina Mora", "Empresa Sol", 3,
                Route.HOME, Route.CALENDAR, Route.APPROVALS, Route.PASSES, Route.HISTORY, Route.WORKERS);

        public final String title;
        public final String name;
        public final String brief;
        public final int portrait;
        public final List<Route> routes;

        Profile(String title, String name, String brief, int portrait, Route... routes) {
            this.title = title;
            this.name = name;
            this.brief = brief;
            this.portrait = portrait;
            this.routes = List.of(routes);
        }

        @Override public String toString() { return title; }

    }

    public enum Status {
        AUTHORIZED("Acceso autorizado", "positive"), RESTRICTED("Acceso restringido", "negative"),
        WAITING("Visita pendiente", "warning"), PENDING("Pendiente", "warning"),
        APPROVED("Aprobada", "positive"), REJECTED("Rechazada", "negative"),
        SCHEDULED("Programada", "neutral"), ENTRY("Entrada", "positive"),
        EXIT("Salida", "neutral"), WORK("Ingreso laboral", "positive"),
        ACTIVE("Activa", "positive"), INACTIVE("Inactiva", "neutral"),
        LOGGED("Registrado", "neutral"), EXPIRED("Expirada", "negative"), CANCELLED("Cancelada", "neutral"),
        INSIDE("Dentro", "positive"), CLOSED("Cerrada", "neutral");

        public final String label;
        public final String tone;
        Status(String label, String tone) { this.label = label; this.tone = tone; }
    }

    public record Person(String name, String document, String company, int portrait, Status access) {}
    public record Visit(int id, Person person, String reason, LocalDate date, LocalTime time, Status status) { }
    public static Profile perfil(String nombre) {
        return java.util.Arrays.stream(Profile.values()).filter(perfil -> perfil.title.equalsIgnoreCase(nombre))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("El rol no tiene un espacio de trabajo en SICA."));
    }

    public static String fecha(Visit visita) {
        return visita.date() == null ? "—" : visita.date().format(DATE) + " · " + visita.time().format(TIME);
    }
    public record Movement(Person person, LocalDate date, LocalTime time, Status type) {}
    public record Audit(String action, String actor, String detail, LocalDate date, LocalTime time, String type) {}
    public record Incident(Visit visit, LocalDate date, LocalTime time, String description) {}
    public record Account(int id, String name, String email, Profile role, String company, Status status, int portrait, byte[] foto) {}
    public record Company(int id, String name, String officer, Status status, String mark, byte[] logo) { }

    public final ObservableList<Visit> visits = FXCollections.observableArrayList();
    public final ObservableList<Movement> movements = FXCollections.observableArrayList();
    public final ObservableList<Audit> audits = FXCollections.observableArrayList();
    public final ObservableList<Incident> incidents = FXCollections.observableArrayList();
    public final ObservableList<Account> accounts = FXCollections.observableArrayList();
    public final ObservableList<Company> companies = FXCollections.observableArrayList();
    public int entradas;
    public int salidas;
    public int pendientes;
    public String empresaActual = "";
    public String nombreActual;
    public byte[] fotoActual;
    public int[] entradasSemana = new int[7];
    public int[] salidasSemana = new int[7];
    public int[] entradasHoy = new int[6];
    public int[] salidasHoy = new int[6];

    public int movementCount(Status type) { return type == Status.EXIT ? salidas : entradas; }
    public List<Visit> companyVisits() { return List.copyOf(visits); }
    public LocalTime currentTime() { return now().toLocalTime().withSecond(0).withNano(0); }
    public LocalDateTime now() { return LocalDateTime.now(ZoneId.of("America/Bogota")); }
}
