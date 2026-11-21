package com.sica.infrastructure.database;

import com.sica.config.ConfiguracionBasedatos;
import com.sica.model.domain.entity.Empresa;
import java.util.List;

// Permite comprobar el enlace de la fábrica, el gestor y el repositorio de empresas.
public class PruebaTransacciones {

    public static void main(String[] args) {
        ConfiguracionBasedatos configuracion = new ConfiguracionBasedatos();
        ConexionBaseDatos acceso = new ConexionBaseDatos(configuracion);
        GestorTransaccionesJdbc gestor = new GestorTransaccionesJdbc(acceso);

        // El gestor entrega la fábrica, ejecuta la consulta y cierra su conexión al terminar.
        List<Empresa> empresas = gestor.ejecutar(repositorios ->
                repositorios.crearEmpresaRepository().listarActivas());

        System.out.println("Enlace JDBC comprobado.");
        System.out.println("Empresas activas: " + empresas.size());

        for (Empresa empresa : empresas) {
            System.out.println(empresa.getId() + " - " + empresa.getNombre());
        }
    }
}
