package com.sica.model.domain.policy;

import java.util.regex.Pattern;

// Comparte el formato del correo entre las cuentas, los casos de uso y el formulario.
public final class ValidadorCorreo {

    private static final Pattern NOMBRE = Pattern.compile("[\\p{L}\\p{N}!#$%&'*+/=?^_`{|}~.\\-]+");
    private static final Pattern PARTE_DOMINIO = Pattern.compile("[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?");

    private ValidadorCorreo() { }

    // Devuelve el mismo correo si es válido. No comprueba que el buzón exista en internet.
    public static String validar(String correo) {
        if (correo == null || correo.isBlank()) {
            throw new IllegalArgumentException("Escribe tu correo electrónico.");
        }
        if (correo.length() > 100) {
            throw new IllegalArgumentException("El correo admite hasta 100 caracteres.");
        }
        if (correo.codePoints().anyMatch(c -> Character.isWhitespace(c)
                || Character.isSpaceChar(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("El correo no puede contener espacios ni caracteres de control.");
        }

        int arroba = correo.indexOf('@');
        if (arroba <= 0 || arroba != correo.lastIndexOf('@') || arroba == correo.length() - 1) {
            throw formatoIncompleto();
        }
        String nombre = correo.substring(0, arroba);
        String dominio = correo.substring(arroba + 1);
        if (!NOMBRE.matcher(nombre).matches() || nombre.startsWith(".")
                || nombre.endsWith(".") || nombre.contains("..")) {
            throw formatoIncompleto();
        }

        // Acepta dominios como gmail.com, empresa.com.co y sica.local, sin obligar a usar Gmail.
        String[] partes = dominio.split("\\.", -1);
        if (partes.length < 2) throw formatoIncompleto();
        for (String parte : partes) {
            if (!PARTE_DOMINIO.matcher(parte).matches()) throw formatoIncompleto();
        }
        return correo;
    }

    private static IllegalArgumentException formatoIncompleto() {
        return new IllegalArgumentException("Escribe un correo completo, por ejemplo: nombre@dominio.com.");
    }
}
