package com.sica.model.application.port;

// Declara las herramientas que necesitarán el registro de usuarios y el inicio de sesión.
public interface ServicioContrasenas {

    // Recibe los caracteres de la contraseña y devuelve el hash que se guardará.
    String generarHash(char[] contrasena);

    // Devuelve true si la contraseña corresponde al hash guardado y false si no corresponde.
    boolean verificar(char[] contrasena, String hashGuardado);
}
