package com.sica.model.application.port;

import java.util.function.Function;

public interface GestorTransacciones {

    // Cada llamada ejecuta una operación en su propia transacción y con sus repositorios.
    // Confirma antes de devolver el resultado; si falla, deshace los cambios antes de lanzar el error.
    // Las consultas no deben leer cambios sin confirmar de otras operaciones.
    <T> T ejecutar(Function<FabricarRepositorios, T> operacion);
}
