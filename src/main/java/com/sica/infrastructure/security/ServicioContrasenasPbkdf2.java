package com.sica.infrastructure.security;

import com.sica.model.application.port.ServicioContrasenas;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// Calcula y comprueba contraseñas usando las herramientas de seguridad de Java.
public class ServicioContrasenasPbkdf2 implements ServicioContrasenas {

    // Estos valores coinciden con los hashes de las cuentas de demostración.
    private static final String ALGORITMO = "PBKDF2WithHmacSHA256";
    private static final int ITERACIONES = 600_000;
    private static final int TAMANO_SAL_BYTES = 16;
    private static final int TAMANO_HASH_BITS = 256;
    private static final HexFormat FORMATO_HEX = HexFormat.of();

    private final SecureRandom aleatorio = new SecureRandom();

    @Override
    public String generarHash(char[] contrasena) {
        if (esContrasenaVacia(contrasena)) {
            throw new IllegalArgumentException("La contraseña es obligatoria.");
        }

        // La sal es un dato aleatorio: dos contraseñas iguales tendrán hashes distintos.
        byte[] sal = new byte[TAMANO_SAL_BYTES];
        aleatorio.nextBytes(sal);

        byte[] resultado = calcularHash(contrasena, sal);
        try {
            // Guardamos los datos necesarios para comprobar la contraseña más adelante.
            // Formato: algoritmo:iteraciones:sal:hash. No incluye la contraseña original.
            return ALGORITMO + ":" + ITERACIONES + ":"
                    + FORMATO_HEX.formatHex(sal) + ":"
                    + FORMATO_HEX.formatHex(resultado);
        } finally {
            Arrays.fill(resultado, (byte) 0);
        }
    }

    @Override
    public boolean verificar(char[] contrasena, String hashGuardado) {
        if (esContrasenaVacia(contrasena) || hashGuardado == null
                || hashGuardado.length() > 255) {
            return false;
        }

        String[] partes = hashGuardado.split(":", -1);

        // Solo procesamos el formato que utiliza este servicio, con sus tamaños y coste conocidos.
        if (partes.length != 4
                || !ALGORITMO.equals(partes[0])
                || !Integer.toString(ITERACIONES).equals(partes[1])
                || partes[2].length() != TAMANO_SAL_BYTES * 2
                || partes[3].length() != TAMANO_HASH_BITS / 4) {
            return false;
        }

        byte[] sal;
        byte[] esperado;
        try {
            sal = FORMATO_HEX.parseHex(partes[2]);
            esperado = FORMATO_HEX.parseHex(partes[3]);
        } catch (IllegalArgumentException error) {
            // Un hash mal formado no se considera una contraseña válida.
            return false;
        }

        // Repetimos el cálculo con la sal guardada; no intentamos recuperar la contraseña original.
        byte[] calculado = calcularHash(contrasena, sal);
        try {
            // Compara los resultados sin detenerse en la primera diferencia.
            return MessageDigest.isEqual(esperado, calculado);
        } finally {
            // Limpiamos los resultados temporales sin modificar la contraseña que recibimos.
            Arrays.fill(esperado, (byte) 0);
            Arrays.fill(calculado, (byte) 0);
        }
    }

    private byte[] calcularHash(char[] contrasena, byte[] sal) {
        PBEKeySpec datos = new PBEKeySpec(
                contrasena, sal, ITERACIONES, TAMANO_HASH_BITS);

        try {
            SecretKeyFactory calculadora = SecretKeyFactory.getInstance(ALGORITMO);
            return calculadora.generateSecret(datos).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(
                    "No fue posible procesar la contraseña.", error);
        } finally {
            // Java conserva una copia en PBEKeySpec; la limpiamos al terminar el cálculo.
            datos.clearPassword();
        }
    }

    private boolean esContrasenaVacia(char[] contrasena) {
        if (contrasena == null || contrasena.length == 0) {
            return true;
        }

        for (char caracter : contrasena) {
            if (!Character.isWhitespace(caracter)) {
                return false;
            }
        }
        return true;
    }
}
