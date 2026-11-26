package com.sica.presentacion.componentes.forms;

import com.sica.model.domain.entity.FotoPersona;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import javafx.scene.control.Button;
import javafx.geometry.Pos;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import static com.sica.presentacion.componentes.ElementosUi.label;

public final class SelectorFoto extends VBox {
    private static final int MAX_LADO = 640;
    private File archivo;

    public SelectorFoto(String titulo, String sinSeleccion) {
        super(6);
        var nombre = label(sinSeleccion, "muted");
        nombre.setWrapText(true);
        Button elegir = new Button("Elegir foto…");
        elegir.getStyleClass().add("secondary-button");
        elegir.setAccessibleText("Elegir foto: " + titulo);
        elegir.setOnAction(evento -> {
            FileChooser selector = new FileChooser();
            selector.setTitle("Elegir foto");
            selector.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "Imágenes JPG y PNG", "*.jpg", "*.jpeg", "*.png", "*.JPG", "*.JPEG", "*.PNG"));
            File elegida = selector.showOpenDialog(getScene().getWindow());
            if (elegida != null) {
                archivo = elegida;
                nombre.setText(elegida.getName());
            }
        });
        FlowPane fila = new FlowPane(8, 8, elegir, nombre);
        fila.setMinWidth(0);
        nombre.maxWidthProperty().bind(fila.widthProperty());
        fila.setAlignment(Pos.CENTER_LEFT);
        var etiqueta = label(titulo, "field-label");
        etiqueta.setWrapText(true);
        etiqueta.setLabelFor(elegir);
        getChildren().addAll(etiqueta, fila);
    }

    public File getArchivo() { return archivo; }

    public static String preparar(File archivo) {
        return archivo == null ? null : FotoPersona.codificar(prepararImagen(archivo, MAX_LADO, false));
    }

    public static byte[] prepararLogo(File archivo) {
        return archivo == null ? null : prepararImagen(archivo, 128, true);
    }

    public static byte[] prepararPerfil(File archivo) {
        return archivo == null ? null : prepararImagen(archivo, 432, false);
    }

    private static byte[] prepararImagen(File archivo, int maxLado, boolean conservarTransparencia) {
        if (archivo.length() > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("Selecciona una imagen de hasta 20 MB.");
        }
        try (var entrada = ImageIO.createImageInputStream(archivo)) {
            if (entrada == null) throw new IOException();
            var lectores = ImageIO.getImageReaders(entrada);
            if (!lectores.hasNext()) throw new IOException();
            var lector = lectores.next();
            try {
                String tipo = lector.getFormatName();
                if (!"JPEG".equalsIgnoreCase(tipo) && !"PNG".equalsIgnoreCase(tipo)) throw new IOException();
                lector.setInput(entrada);
                int ancho = lector.getWidth(0), alto = lector.getHeight(0);
                if (ancho <= 0 || alto <= 0 || (long) ancho * alto > 60_000_000) throw new IOException();
                int ladoMayor = Math.max(ancho, alto);
                int muestreo = Math.max(1, ladoMayor / maxLado);
                var opciones = lector.getDefaultReadParam();
                opciones.setSourceSubsampling(muestreo, muestreo, 0, 0);
                double escala = Math.min(1.0, (double) maxLado / ladoMayor);
                var foto = new BufferedImage(Math.max(1, (int) Math.round(ancho * escala)),
                        Math.max(1, (int) Math.round(alto * escala)),
                        conservarTransparencia ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                var dibujo = foto.createGraphics();
                try {
                    dibujo.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    dibujo.drawImage(lector.read(0, opciones), 0, 0, foto.getWidth(), foto.getHeight(),
                            conservarTransparencia ? null : Color.WHITE, null);
                } finally {
                    dibujo.dispose();
                }
                var salida = new ByteArrayOutputStream();
                if (!ImageIO.write(foto, conservarTransparencia ? "png" : "jpeg", salida)) throw new IOException();
                return salida.toByteArray();
            } finally {
                lector.dispose();
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("No se pudo leer la foto. Selecciona un archivo JPG o PNG válido.", error);
        }
    }
}
