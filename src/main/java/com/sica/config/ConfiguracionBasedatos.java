package com.sica.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;


public class ConfiguracionBasedatos {

    private final Properties configuracion = new Properties();

    public ConfiguracionBasedatos() {
        try (var archivo = ConfiguracionBasedatos.class.getResourceAsStream("/config.properties")) {
            if (archivo == null) throw new IllegalStateException("No se encontró config.properties.");
            configuracion.load(new InputStreamReader(archivo, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("No se pudo leer config.properties.", error);
        }
    }

    // Entrega la dirección de la base a la que nos conectaremos.
    public String obtenerUrl() {
        return leerObligatoria("db.url");
    }

    // Prepara las opciones con los nombres que reconoce Connector/J.
    public Properties obtenerPropiedadesConexion() {
        Properties propiedades = new Properties();

        propiedades.setProperty("user", leerObligatoria("db.user"));
        propiedades.setProperty("password", leerObligatoria("db.password"));
        propiedades.setProperty("sslMode", configuracion.getProperty("db.sslMode", "REQUIRED"));
        propiedades.setProperty("connectionTimeZone", configuracion.getProperty("db.connectionTimeZone", "-05:00"));
        propiedades.setProperty("forceConnectionTimeZoneToSession",
                configuracion.getProperty("db.forceConnectionTimeZoneToSession", "true"));

        // Limita cuánto espera la aplicación si MySQL no responde.
        propiedades.setProperty("connectTimeout", configuracion.getProperty("db.connectTimeout", "10000"));
        propiedades.setProperty("socketTimeout", configuracion.getProperty("db.socketTimeout", "20000"));

        return propiedades;
    }

    // Indica qué opción falta, sin mostrar su contenido.
    private String leerObligatoria(String clave) {
        String valor = configuracion.getProperty(clave);

        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "Falta configurar " + clave + " en config.properties.");
        }

        return valor;
    }

}
