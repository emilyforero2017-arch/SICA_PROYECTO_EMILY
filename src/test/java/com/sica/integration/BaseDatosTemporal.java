package com.sica.integration;

import com.sica.config.ConfiguracionBasedatos;
import com.sica.infrastructure.database.ConexionBaseDatos;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

// Prepara una base desechable con los mismos archivos SQL que utiliza SICA.
final class BaseDatosTemporal implements AutoCloseable {

    private final ConfiguracionBasedatos original = new ConfiguracionBasedatos();
    private final String nombre = "sica_it_" + UUID.randomUUID().toString().replace("-", "");
    private final ConfiguracionBasedatos temporal;
    private boolean creada;

    private BaseDatosTemporal() {
        String url = original.obtenerUrl();
        int consulta = url.indexOf('?');
        int fin = consulta < 0 ? url.length() : consulta;
        int barra = url.lastIndexOf('/', fin - 1);
        if (!url.startsWith("jdbc:mysql://") || barra < "jdbc:mysql://".length()) {
            throw new IllegalStateException("Las pruebas requieren una dirección JDBC de MySQL con base de datos.");
        }
        String urlTemporal = url.substring(0, barra + 1) + nombre + url.substring(fin);

        temporal = new ConfiguracionBasedatos() {
            @Override
            public String obtenerUrl() { return urlTemporal; }

            @Override
            public Properties obtenerPropiedadesConexion() { return propiedadesConexion(); }
        };
    }

    static BaseDatosTemporal crear() throws Exception {
        BaseDatosTemporal base = new BaseDatosTemporal();
        try {
            base.preparar();
            return base;
        } catch (Exception | Error error) {
            try {
                base.close();
            } catch (Exception limpieza) {
                error.addSuppressed(limpieza);
            }
            throw error;
        }
    }

    ConfiguracionBasedatos configuracion() { return temporal; }

    private Properties propiedadesConexion() {
        Properties propiedades = original.obtenerPropiedadesConexion();
        propiedades.setProperty("connectTimeout", "10000");
        propiedades.setProperty("socketTimeout", "20000");
        return propiedades;
    }

    private Connection abrirAdministracion() throws SQLException {
        return DriverManager.getConnection(original.obtenerUrl(), propiedadesConexion());
    }

    private void preparar() throws SQLException, IOException {
        try (Connection conexion = abrirAdministracion();
             var consulta = conexion.createStatement()) {
            creada = true;
            consulta.execute("CREATE DATABASE `" + nombre
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        try (Connection conexion = new ConexionBaseDatos(temporal).abrirConexion()) {
            ejecutarArchivo(conexion, "/db/schema.sql");
            ejecutarArchivo(conexion, "/db/data.sql");
        }
        System.out.println("Base temporal preparada: " + nombre);
    }

    private void ejecutarArchivo(Connection conexion, String ruta) throws IOException, SQLException {
        String contenido;
        try (InputStream archivo = BaseDatosTemporal.class.getResourceAsStream(ruta)) {
            if (archivo == null) throw new IOException("No se encontró el recurso " + ruta);
            contenido = new String(archivo.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String sql : separarSentencias(contenido)) {
            // La conexión ya apunta a la base temporal; se omite la selección de sica.
            if (sql.matches("(?is)USE\\s+sica")
                    || sql.matches("(?is)CREATE\\s+DATABASE\\s+IF\\s+NOT\\s+EXISTS\\s+sica\\b.*")) {
                continue;
            }
            String mayusculas = sql.toUpperCase(Locale.ROOT);
            boolean permitida = mayusculas.startsWith("CREATE TABLE ")
                    || mayusculas.startsWith("CREATE TRIGGER ")
                    || mayusculas.startsWith("INSERT INTO ")
                    || mayusculas.startsWith("SET NAMES ")
                    || mayusculas.startsWith("SET TIME_ZONE ")
                    || mayusculas.startsWith("SET @AHORA ")
                    || mayusculas.equals("START TRANSACTION")
                    || mayusculas.equals("COMMIT");
            if (!permitida || sinTextos(sql).matches("(?is).*\\bsica`?\\s*\\..*")) {
                throw new SQLException("El archivo contiene una instrucción no admitida para la base temporal.");
            }
            try (var consulta = conexion.createStatement()) {
                consulta.execute(sql);
            }
        }
    }

    private String sinTextos(String sql) {
        StringBuilder resultado = new StringBuilder();
        char comilla = 0;
        for (int i = 0; i < sql.length(); i++) {
            char letra = sql.charAt(i);
            char siguiente = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (comilla != 0) {
                if (letra == '\\' && siguiente != '\0') i++;
                else if (letra == comilla) {
                    if (siguiente == comilla) i++;
                    else comilla = 0;
                }
            } else if (letra == '\'' || letra == '"') {
                comilla = letra;
                resultado.append(' ');
            } else resultado.append(letra);
        }
        return resultado.toString();
    }

    // Los puntos y coma dentro de una descripción no separan instrucciones SQL.
    private List<String> separarSentencias(String contenido) {
        List<String> sentencias = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        char comilla = 0;
        boolean comentarioLinea = false;
        boolean comentarioBloque = false;

        for (int i = 0; i < contenido.length(); i++) {
            char letra = contenido.charAt(i);
            char siguiente = i + 1 < contenido.length() ? contenido.charAt(i + 1) : '\0';
            if (comentarioLinea) {
                if (letra == '\n') { comentarioLinea = false; actual.append('\n'); }
            } else if (comentarioBloque) {
                if (letra == '*' && siguiente == '/') { comentarioBloque = false; i++; actual.append(' '); }
            } else if (comilla != 0) {
                actual.append(letra);
                if (letra == '\\' && siguiente != '\0') {
                    actual.append(siguiente); i++;
                } else if (letra == comilla) {
                    if (siguiente == comilla) { actual.append(siguiente); i++; }
                    else comilla = 0;
                }
            } else if (letra == '-' && siguiente == '-') {
                comentarioLinea = true; i++;
            } else if (letra == '#') {
                comentarioLinea = true;
            } else if (letra == '/' && siguiente == '*') {
                comentarioBloque = true; i++;
            } else if (letra == '\'' || letra == '"' || letra == '`') {
                comilla = letra; actual.append(letra);
            } else if (letra == ';') {
                if (!actual.toString().isBlank()) sentencias.add(actual.toString().strip());
                actual.setLength(0);
            } else {
                actual.append(letra);
            }
        }
        if (comilla != 0 || comentarioBloque) {
            throw new IllegalArgumentException("El archivo SQL contiene una instrucción incompleta.");
        }
        if (!actual.toString().isBlank()) sentencias.add(actual.toString().strip());
        return sentencias;
    }

    @Override
    public void close() throws SQLException {
        if (!creada) return;
        // Solo se elimina el nombre aleatorio reservado por esta instancia de pruebas.
        if (!nombre.matches("sica_it_[a-f0-9]{32}")) {
            throw new SQLException("El nombre de la base temporal no es válido.");
        }
        try (Connection conexion = abrirAdministracion();
             var consulta = conexion.createStatement()) {
            consulta.execute("DROP DATABASE IF EXISTS `" + nombre + "`");
            try (var verificacion = conexion.prepareStatement(
                    "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
                verificacion.setString(1, nombre);
                try (var resultado = verificacion.executeQuery()) {
                    if (resultado.next()) throw new SQLException("La base temporal no pudo eliminarse: " + nombre);
                }
            }
        }
        creada = false;
        System.out.println("Base temporal eliminada: " + nombre);
    }
}
