package com.sica.model.domain.entity;

import java.util.Base64;

public final class FotoPersona {
    public static final String ARCHIVO_GUARDADO = "archivo:foto";
    private static final String PREFIJO = "data:image/jpeg;base64,";
    private static final int MAXIMO_BYTES = 512 * 1024;

    private FotoPersona() { }

    public static boolean esCarga(String foto) {
        return foto != null && foto.startsWith(PREFIJO);
    }

    public static String codificar(byte[] bytes) {
        if (bytes.length > MAXIMO_BYTES) throw new IllegalArgumentException("La foto es demasiado grande.");
        return PREFIJO + Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] decodificar(String foto) {
        if (foto.length() > PREFIJO.length() + 4 * ((MAXIMO_BYTES + 2) / 3)) {
            throw new IllegalArgumentException("La foto es demasiado grande.");
        }
        byte[] bytes = Base64.getDecoder().decode(foto.substring(PREFIJO.length()));
        if (bytes.length > MAXIMO_BYTES) throw new IllegalArgumentException("La foto es demasiado grande.");
        if (bytes.length < 3 || bytes[0] != (byte) 0xff || bytes[1] != (byte) 0xd8 || bytes[2] != (byte) 0xff) {
            throw new IllegalArgumentException("La foto seleccionada no es válida.");
        }
        return bytes;
    }

    public static String describir(String foto) {
        return esCarga(foto) || ARCHIVO_GUARDADO.equals(foto) ? "archivo de imagen" : foto;
    }
}
