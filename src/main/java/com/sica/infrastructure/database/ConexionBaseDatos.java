package com.sica.infrastructure.database;

import com.sica.config.ConfiguracionBasedatos;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

public class ConexionBaseDatos implements AutoCloseable {

    private final ConfiguracionBasedatos configuracion;
    private final boolean reutilizar;
    private HikariDataSource pool;
    private boolean cerrada;

    public ConexionBaseDatos(ConfiguracionBasedatos configuracion) {
        this(configuracion, false);
    }

    public ConexionBaseDatos(ConfiguracionBasedatos configuracion, boolean reutilizar) {
        this.configuracion = Objects.requireNonNull(
                configuracion,
                "La configuración de la base de datos es obligatoria.");
        this.reutilizar = reutilizar;
    }

    // Cada transacción recibe una conexión exclusiva hasta que la devuelve al pool.
    public Connection abrirConexion() throws SQLException {
        if (reutilizar) return obtenerPool().getConnection();
        return DriverManager.getConnection(
                configuracion.obtenerUrl(),
                configuracion.obtenerPropiedadesConexion());
    }

    private synchronized HikariDataSource obtenerPool() throws SQLException {
        if (cerrada) throw new SQLException("El acceso a la base de datos está cerrado.");
        if (pool == null) {
            String url = configuracion.obtenerUrl();
            var propiedades = configuracion.obtenerPropiedadesConexion();
            HikariDataSource conexiones = new HikariDataSource();
            conexiones.setPoolName("sica-mysql");
            conexiones.setJdbcUrl(url);
            conexiones.setDataSourceProperties(propiedades);
            conexiones.setMaximumPoolSize(4);
            conexiones.setMinimumIdle(1);
            conexiones.setConnectionTimeout(12_000);
            conexiones.setInitializationFailTimeout(-1);
            conexiones.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
            pool = conexiones;
        }
        return pool;
    }

    @Override
    public synchronized void close() {
        cerrada = true;
        if (pool != null) pool.close();
    }
}
