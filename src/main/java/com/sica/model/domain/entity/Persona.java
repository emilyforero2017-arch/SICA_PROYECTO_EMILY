package com.sica.model.domain.entity;

public class Persona {

    private Integer id;
    private String nombre;
    private String documentoIdentidad;
    private Integer empresaId;
    private TipoPersona tipoPersona;
    private Integer estadoAccesoId;
    private String urlFoto;
    private Boolean vinculacionLaboralActiva;

    public Persona(
            String nombre,
            String documentoIdentidad,
            Integer empresaId,
            TipoPersona tipoPersona,
            Integer estadoAccesoId,
            String urlFoto,
            Boolean vinculacionLaboralActiva) {

        this(null, nombre, documentoIdentidad, empresaId, tipoPersona,
                estadoAccesoId, urlFoto, vinculacionLaboralActiva);
    }

    public Persona(
            Integer id,
            String nombre,
            String documentoIdentidad,
            Integer empresaId,
            TipoPersona tipoPersona,
            Integer estadoAccesoId,
            String urlFoto,
            Boolean vinculacionLaboralActiva) {

        validarVinculacion(tipoPersona, empresaId, vinculacionLaboralActiva);

        this.id = id;
        this.nombre = validarNombre(nombre);
        this.documentoIdentidad = validarDocumentoIdentidad(documentoIdentidad);
        this.empresaId = empresaId;
        this.tipoPersona = tipoPersona;
        this.estadoAccesoId = validarEstadoAccesoId(estadoAccesoId);
        this.urlFoto = validarUrlFoto(urlFoto);
        this.vinculacionLaboralActiva = vinculacionLaboralActiva;
    }

    public Integer getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = validarNombre(nombre);
    }

    public String getDocumentoIdentidad() {
        return documentoIdentidad;
    }

    public void setDocumentoIdentidad(String documentoIdentidad) {
        this.documentoIdentidad = validarDocumentoIdentidad(documentoIdentidad);
    }

    public Integer getEmpresaId() {
        return empresaId;
    }

    public TipoPersona getTipoPersona() {
        return tipoPersona;
    }

    public Integer getEstadoAccesoId() {
        return estadoAccesoId;
    }

    public void setEstadoAccesoId(Integer estadoAccesoId) {
        this.estadoAccesoId = validarEstadoAccesoId(estadoAccesoId);
    }

    public String getUrlFoto() {
        return urlFoto;
    }

    public void setUrlFoto(String urlFoto) {
        this.urlFoto = validarUrlFoto(urlFoto);
    }

    public Boolean getVinculacionLaboralActiva() {
        return vinculacionLaboralActiva;
    }

    public void setVinculacionLaboralActiva(Boolean vinculacionLaboralActiva) {
        validarVinculacion(tipoPersona, empresaId, vinculacionLaboralActiva);
        this.vinculacionLaboralActiva = vinculacionLaboralActiva;
    }

    // Estos tres datos se comprueban juntos antes de modificar el objeto. Dra el permiso parar ingresar
    public void actualizarVinculacion(
            TipoPersona tipoPersona,
            Integer empresaId,
            Boolean vinculacionLaboralActiva) {

        validarVinculacion(tipoPersona, empresaId, vinculacionLaboralActiva);

        this.tipoPersona = tipoPersona;
        this.empresaId = empresaId;
        this.vinculacionLaboralActiva = vinculacionLaboralActiva;
    }

    public static String validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre de la persona es obligatorio.");
        }

        if (nombre.length() > 100) {
            throw new IllegalArgumentException(
                    "El nombre de la persona admite hasta 100 caracteres.");
        }

        return nombre;
    }

    public static String validarDocumentoIdentidad(String documentoIdentidad) {
        if (documentoIdentidad == null || documentoIdentidad.isBlank()) {
            throw new IllegalArgumentException(
                    "El documento de identidad es obligatorio.");
        }

        if (documentoIdentidad.length() > 20) {
            throw new IllegalArgumentException(
                    "El documento de identidad admite hasta 20 caracteres.");
        }

        return documentoIdentidad;
    }

    private Integer validarEstadoAccesoId(Integer estadoAccesoId) {
        if (estadoAccesoId == null) {
            throw new IllegalArgumentException(
                    "El estado de acceso de la persona es obligatorio.");
        }

        return estadoAccesoId;
    }

    public static String validarUrlFoto(String urlFoto) {
        if (urlFoto == null || urlFoto.isBlank()) {
            throw new IllegalArgumentException(
                    "La foto es obligatoria.");
        }

        if (FotoPersona.esCarga(urlFoto)) {
            FotoPersona.decodificar(urlFoto);
            return urlFoto;
        }
        if (urlFoto.length() > 255) {
            throw new IllegalArgumentException(
                    "La dirección de la fotografía admite hasta 255 caracteres.");
        }

        return urlFoto;
    }

    // Conserva las mismas combinaciones permitidas por el esquema SQL.
    private void validarVinculacion(
            TipoPersona tipoPersona,
            Integer empresaId,
            Boolean vinculacionLaboralActiva) {

        if (tipoPersona == null) {
            throw new IllegalArgumentException(
                    "El tipo de persona es obligatorio.");
        }

        if (tipoPersona == TipoPersona.TRABAJADOR) {
            if (empresaId == null) {
                throw new IllegalArgumentException(
                        "El trabajador debe tener una empresa asignada.");
            }

            if (vinculacionLaboralActiva == null) {
                throw new IllegalArgumentException(
                        "La vinculación laboral del trabajador debe estar definida.");
            }
        } else if (empresaId != null || vinculacionLaboralActiva != null) {
            throw new IllegalArgumentException(
                    "El invitado no tiene empresa laboral ni vinculación laboral asignadas.");
        }
    }
}
