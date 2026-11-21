package com.sica.infrastructure.database;

import com.sica.config.ConfiguracionBasedatos;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

public class ConexionBaseDatos {

    private final ConfiguracionBasedatos configuracion;

    public ConexionBaseDatos(ConfiguracionBasedatos configuracion) {
        // Recibe la configuración que utilizaremos para conectarnos.
        this.configuracion = Objects.requireNonNull(
                configuracion,
                "La configuración de la base de datos es obligatoria.");
    }

    // Abre una conexión nueva usando los datos de config.properties.
    public Connection abrirConexion() throws SQLException {
        return DriverManager.getConnection(
                configuracion.obtenerUrl(),
                configuracion.obtenerPropiedadesConexion());
    }

}
