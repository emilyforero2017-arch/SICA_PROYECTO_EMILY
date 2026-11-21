package com.sica.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;


public class ConfiguracionBasedatos {

    private static final Set<String> CONTRASENAS_LOCALES = Set.of(
            "db.password", "db.trustCertificateKeyStorePassword");
    // Conserva las opciones leídas del archivo.
    private final Properties configuracion = new Properties();

    public ConfiguracionBasedatos() {
        // Busca config.properties dentro de los recursos del proyecto.
        try (InputStream archivo = ConfiguracionBasedatos.class
                .getResourceAsStream("/config.properties")) {

            if (archivo == null) {
                throw new IllegalStateException(
                        "No se encontró el archivo config.properties.");
            }

            configuracion.load(
                    new InputStreamReader(archivo, StandardCharsets.UTF_8));

        } catch (IOException error) {
            throw new IllegalStateException(
                    "No se pudo leer la configuración de la base de datos.",
                    error);
        }
        cargarContrasenasLocales();
    }

    // Las contraseñas permanecen en este computador; el resto de la configuración sigue en Git.
    private void cargarContrasenasLocales() {
        Path archivo = Path.of(System.getProperty("user.home"), ".sica", "config-local.properties");
        if (!Files.exists(archivo)) return;

        Properties locales = new Properties();
        try (var lector = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            locales.load(lector);
            for (String clave : CONTRASENAS_LOCALES) {
                if (locales.containsKey(clave)) configuracion.setProperty(clave, locales.getProperty(clave));
            }
        } catch (IOException error) {
            throw new IllegalStateException("No se pudieron leer las contraseñas locales de SICA.", error);
        }
    }

    // Entrega la dirección de la base a la que nos conectaremos.
    public String obtenerUrl() {
        return leerObligatoria("db.url");
    }

    // Prepara las opciones con los nombres que reconoce Connector/J.
    public Properties obtenerPropiedadesConexion() {
        Properties propiedades = new Properties();

        String[] opciones = {
            "user",
            "password",
            "sslMode",
            "trustCertificateKeyStoreUrl",
            "trustCertificateKeyStoreType",
            "trustCertificateKeyStorePassword",
            "connectionTimeZone",
            "forceConnectionTimeZoneToSession"
        };

        for (String opcion : opciones) {
            propiedades.setProperty(
                    opcion,
                    leerObligatoria("db." + opcion));
        }

        return propiedades;
    }

    // Indica qué opción falta, sin mostrar su contenido.
    private String leerObligatoria(String clave) {
        String valor = configuracion.getProperty(clave);

        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "Falta configurar la opción: " + clave
                    + (CONTRASENAS_LOCALES.contains(clave)
                            ? " en ~/.sica/config-local.properties." : ""));
        }

        return valor;
    }

}
