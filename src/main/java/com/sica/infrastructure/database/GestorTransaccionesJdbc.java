package com.sica.infrastructure.database;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.application.port.GestorTransacciones;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GestorTransaccionesJdbc implements GestorTransacciones {

    private static final Logger LOG = Logger.getLogger(GestorTransaccionesJdbc.class.getName());

    private final ConexionBaseDatos acceso;

    public GestorTransaccionesJdbc(ConexionBaseDatos acceso) {
        this.acceso = Objects.requireNonNull(
                acceso, "El acceso a la base de datos es obligatorio.");
    }

    @Override
    public <T> T ejecutar(Function<FabricarRepositorios, T> operacion) {
        Objects.requireNonNull(operacion, "La operación a realizar es obligatoria.");

        Connection conexion;
        try {
            // Cada operación recibe su propia conexión, también desde distintos computadores.
            conexion = acceso.abrirConexion();
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "No se pudo abrir la conexión de la transacción.", error);
        }

        Throwable fallo = null;
        try {
            // Las consultas solo pueden ver cambios que otras operaciones ya confirmaron.
            conexion.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            conexion.setAutoCommit(false);

            FabricarRepositorios repositorios = new FabricaRepositoriosJdbc(conexion);
            T resultado = operacion.apply(repositorios);

            // Solo confirmamos después de que termine todo el trabajo recibido.
            conexion.commit();
            return resultado;
        } catch (SQLException error) {
            IllegalStateException problema = new IllegalStateException(
                    "No se pudo completar la transacción.", error);
            fallo = problema;
            deshacer(conexion, problema);
            throw problema;
        } catch (RuntimeException | Error error) {
            fallo = error;
            deshacer(conexion, error);
            throw error;
        } finally {
            cerrar(conexion, fallo);
        }
    }

    private void deshacer(Connection conexion, Throwable fallo) {
        try {
            conexion.rollback();
        } catch (SQLException error) {
            // Conservamos también el problema al deshacer, sin ocultar el error original.
            fallo.addSuppressed(error);
        }
    }

    private void cerrar(Connection conexion, Throwable fallo) {
        try {
            conexion.close();
        } catch (SQLException error) {
            if (fallo != null) {
                fallo.addSuppressed(error);
            } else {
                // Un error al cerrar no convierte un guardado ya confirmado en un fallo de guardado.
                LOG.log(Level.WARNING,
                        "La transacción se confirmó, pero falló el cierre de su conexión.", error);
            }
        }
    }
}
