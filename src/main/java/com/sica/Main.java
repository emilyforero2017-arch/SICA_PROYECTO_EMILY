package com.sica;

import com.sica.config.AplicacionSica;
import com.sica.config.ConfiguracionBasedatos;

public final class Main {

    private Main() { }

    public static void main(String[] args) {
        int resultado = arrancar();
        if (resultado != 0) System.exit(resultado);
    }

    private static int arrancar() {
        AplicacionSica aplicacion = null;
        Thread apagado = null;
        try {
            aplicacion = new AplicacionSica(new ConfiguracionBasedatos());
            // También cerramos los recursos cuando se detiene el programa desde la terminal.
            apagado = new Thread(aplicacion::close, "sica-apagado");
            Runtime.getRuntime().addShutdownHook(apagado);
            aplicacion.iniciar();
            System.out.println("SICA iniciada. Conexión comprobada y revisiones automáticas activas.");
            System.out.println("Todavía no hay pantallas. Para detener el programa, pulsa Ctrl+C.");
            // Cuando incorporemos la interfaz, su cierre utilizará la misma aplicación.
            aplicacion.esperarCierre();
            return 0;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return 130;
        } catch (RuntimeException error) {
            // No mostramos contraseñas ni el contenido de la configuración en la terminal.
            System.err.println("No fue posible iniciar SICA. Revisa la configuración y la conexión a la base de datos.");
            return 1;
        } finally {
            if (aplicacion != null) aplicacion.close();
            if (apagado != null) {
                try { Runtime.getRuntime().removeShutdownHook(apagado); }
                catch (IllegalStateException cerrandoJava) {
                    // Java ya está terminando y ejecutará el cierre registrado.
                }
            }
        }
    }
}
