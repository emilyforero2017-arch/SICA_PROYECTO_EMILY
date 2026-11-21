package com.sica.infrastructure.database;

import com.sica.config.ConfiguracionBasedatos;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class PruebaConexion {

    public static void main(String[] args) throws SQLException {

        // Lee las opciones guardadas en config.properties.
        ConfiguracionBasedatos configuracion =
                new ConfiguracionBasedatos();

        ConexionBaseDatos acceso =
                new ConexionBaseDatos(configuracion);

        String sql = """
                SELECT DATABASE() AS base,
                       VERSION() AS version,
                       @@session.time_zone AS zona_horaria
                """;

        // Al terminar, se cierran el resultado, la consulta y la conexión.
        try (Connection conexion = acceso.abrirConexion();
             Statement consulta = conexion.createStatement();
             ResultSet resultado = consulta.executeQuery(sql)) {

            if (!resultado.next()) {
                throw new SQLException(
                        "La consulta de conexión no devolvió un resultado.");
            }

            System.out.println("Conexión con Aiven realizada.");
            System.out.println(
                    "Base de datos: " + resultado.getString("base"));
            System.out.println(
                    "Versión: " + resultado.getString("version"));
            System.out.println(
                    "Zona horaria: " + resultado.getString("zona_horaria"));
        }
    }
}