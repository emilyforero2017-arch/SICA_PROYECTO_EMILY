package com.sica.model.domain.entity;

import java.time.LocalDateTime;

public class Visita {

    private Integer id;
    private Integer personaId;
    private Integer empresaDestinoId;
    private Integer funcionarioDestinatarioId;
    private TipoIngreso tipoIngreso;
    private LocalDateTime fechaProgramada;
    private LocalDateTime fechaAprobacion;
    private LocalDateTime fechaEntrada;
    private LocalDateTime fechaSalida;
    private LocalDateTime fechaCierreSistema;
    private Integer estadoVisitaId;
    private String vehiculoPlaca;
    private Integer aprobadaPorId;

    public Visita(
            Integer personaId,
            Integer empresaDestinoId,
            Integer funcionarioDestinatarioId,
            TipoIngreso tipoIngreso,
            LocalDateTime fechaProgramada,
            LocalDateTime fechaAprobacion,
            LocalDateTime fechaEntrada,
            LocalDateTime fechaSalida,
            LocalDateTime fechaCierreSistema,
            Integer estadoVisitaId,
            String vehiculoPlaca,
            Integer aprobadaPorId) {

        this(null, personaId, empresaDestinoId, funcionarioDestinatarioId,
                tipoIngreso, fechaProgramada, fechaAprobacion, fechaEntrada,
                fechaSalida, fechaCierreSistema, estadoVisitaId,
                vehiculoPlaca, aprobadaPorId);
    }

    public Visita(
            Integer id,
            Integer personaId,
            Integer empresaDestinoId,
            Integer funcionarioDestinatarioId,
            TipoIngreso tipoIngreso,
            LocalDateTime fechaProgramada,
            LocalDateTime fechaAprobacion,
            LocalDateTime fechaEntrada,
            LocalDateTime fechaSalida,
            LocalDateTime fechaCierreSistema,
            Integer estadoVisitaId,
            String vehiculoPlaca,
            Integer aprobadaPorId) {

        validarDatosObligatorios(personaId, empresaDestinoId, tipoIngreso, estadoVisitaId);
        validarProgramacion(tipoIngreso, fechaProgramada);
        validarDestinatario(tipoIngreso, funcionarioDestinatarioId);
        validarAprobacion(tipoIngreso, funcionarioDestinatarioId,
                aprobadaPorId, fechaAprobacion);
        validarFechas(fechaAprobacion, fechaEntrada, fechaSalida, fechaCierreSistema);

        this.id = id;
        this.personaId = personaId;
        this.empresaDestinoId = empresaDestinoId;
        this.funcionarioDestinatarioId = funcionarioDestinatarioId;
        this.tipoIngreso = tipoIngreso;
        this.fechaProgramada = fechaProgramada;
        this.fechaAprobacion = fechaAprobacion;
        this.fechaEntrada = fechaEntrada;
        this.fechaSalida = fechaSalida;
        this.fechaCierreSistema = fechaCierreSistema;
        this.estadoVisitaId = estadoVisitaId;
        this.vehiculoPlaca = validarVehiculoPlaca(vehiculoPlaca);
        this.aprobadaPorId = aprobadaPorId;
    }

    public Integer getId() {
        return id;
    }

    public Integer getPersonaId() {
        return personaId;
    }

    public Integer getEmpresaDestinoId() {
        return empresaDestinoId;
    }

    public Integer getFuncionarioDestinatarioId() {
        return funcionarioDestinatarioId;
    }

    public TipoIngreso getTipoIngreso() {
        return tipoIngreso;
    }

    public LocalDateTime getFechaProgramada() {
        return fechaProgramada;
    }

    public void setFechaProgramada(LocalDateTime fechaProgramada) {
        validarProgramacion(tipoIngreso, fechaProgramada);
        this.fechaProgramada = fechaProgramada;
    }

    public LocalDateTime getFechaAprobacion() {
        return fechaAprobacion;
    }

    public LocalDateTime getFechaEntrada() {
        return fechaEntrada;
    }

    public LocalDateTime getFechaSalida() {
        return fechaSalida;
    }

    public LocalDateTime getFechaCierreSistema() {
        return fechaCierreSistema;
    }

    public Integer getEstadoVisitaId() {
        return estadoVisitaId;
    }

    public String getVehiculoPlaca() {
        return vehiculoPlaca;
    }

    public void setVehiculoPlaca(String vehiculoPlaca) {
        this.vehiculoPlaca = validarVehiculoPlaca(vehiculoPlaca);
    }

    public Integer getAprobadaPorId() {
        return aprobadaPorId;
    }

    // El aprobador y la fecha se comprueban juntos antes de cambiarlos.
    public void actualizarAprobacion(
            Integer aprobadaPorId,
            LocalDateTime fechaAprobacion) {

        validarAprobacion(tipoIngreso, funcionarioDestinatarioId,
                aprobadaPorId, fechaAprobacion);
        validarFechas(fechaAprobacion, fechaEntrada, fechaSalida, fechaCierreSistema);

        this.aprobadaPorId = aprobadaPorId;
        this.fechaAprobacion = fechaAprobacion;
    }

    // Los casos de uso decidirán qué estado corresponde a cada operación.
    public void actualizarMovimiento(
            Integer estadoVisitaId,
            LocalDateTime fechaEntrada,
            LocalDateTime fechaSalida,
            LocalDateTime fechaCierreSistema) {

        if (estadoVisitaId == null) {
            throw new IllegalArgumentException(
                    "El estado de la visita es obligatorio.");
        }

        validarFechas(fechaAprobacion, fechaEntrada, fechaSalida, fechaCierreSistema);

        this.estadoVisitaId = estadoVisitaId;
        this.fechaEntrada = fechaEntrada;
        this.fechaSalida = fechaSalida;
        this.fechaCierreSistema = fechaCierreSistema;
    }

    private void validarDatosObligatorios(
            Integer personaId,
            Integer empresaDestinoId,
            TipoIngreso tipoIngreso,
            Integer estadoVisitaId) {

        if (personaId == null) {
            throw new IllegalArgumentException(
                    "La persona de la visita es obligatoria.");
        }

        if (empresaDestinoId == null) {
            throw new IllegalArgumentException(
                    "La empresa de destino es obligatoria.");
        }

        if (tipoIngreso == null) {
            throw new IllegalArgumentException(
                    "El tipo de ingreso es obligatorio.");
        }

        if (estadoVisitaId == null) {
            throw new IllegalArgumentException(
                    "El estado de la visita es obligatorio.");
        }
    }

    private void validarProgramacion(
            TipoIngreso tipoIngreso,
            LocalDateTime fechaProgramada) {

        if (tipoIngreso == TipoIngreso.INVITADO_PREREGISTRADO) {
            if (fechaProgramada == null) {
                throw new IllegalArgumentException(
                        "La visita preregistrada debe tener una fecha programada.");
            }
        } else if (fechaProgramada != null) {
            throw new IllegalArgumentException(
                    "La fecha programada corresponde a las visitas preregistradas.");
        }
    }

    private void validarDestinatario(
            TipoIngreso tipoIngreso,
            Integer funcionarioDestinatarioId) {

        if (tipoIngreso == TipoIngreso.TRABAJADOR_CON_CARNET) {
            if (funcionarioDestinatarioId != null) {
                throw new IllegalArgumentException(
                        "El ingreso habitual con carnet no tiene funcionario destinatario.");
            }
        } else if (funcionarioDestinatarioId == null) {
            throw new IllegalArgumentException(
                    "La visita debe tener un funcionario destinatario.");
        }
    }

    private void validarAprobacion(
            TipoIngreso tipoIngreso,
            Integer funcionarioDestinatarioId,
            Integer aprobadaPorId,
            LocalDateTime fechaAprobacion) {

        if ((aprobadaPorId == null && fechaAprobacion != null)
                || (aprobadaPorId != null && fechaAprobacion == null)) {
            throw new IllegalArgumentException(
                    "El aprobador y la fecha de aprobación deben registrarse juntos.");
        }

        if (tipoIngreso == TipoIngreso.TRABAJADOR_CON_CARNET
                && aprobadaPorId != null) {
            throw new IllegalArgumentException(
                    "El ingreso habitual con carnet no requiere aprobación de un funcionario.");
        }

        if (aprobadaPorId != null
                && !aprobadaPorId.equals(funcionarioDestinatarioId)) {
            throw new IllegalArgumentException(
                    "La aprobación corresponde al funcionario destinatario.");
        }
    }

    private void validarFechas(
            LocalDateTime fechaAprobacion,
            LocalDateTime fechaEntrada,
            LocalDateTime fechaSalida,
            LocalDateTime fechaCierreSistema) {

        if (fechaEntrada != null && fechaAprobacion != null
                && fechaEntrada.isBefore(fechaAprobacion)) {
            throw new IllegalArgumentException(
                    "La entrada no puede ser anterior a la aprobación.");
        }

        if (fechaSalida != null) {
            if (fechaEntrada == null) {
                throw new IllegalArgumentException(
                        "Para registrar una salida debe existir una entrada.");
            }

            if (fechaSalida.isBefore(fechaEntrada)) {
                throw new IllegalArgumentException(
                        "La salida no puede ser anterior a la entrada.");
            }
        }

        if (fechaCierreSistema != null) {
            if (fechaEntrada == null) {
                throw new IllegalArgumentException(
                        "Para cerrar la visita por sistema debe existir una entrada.");
            }

            if (fechaCierreSistema.isBefore(fechaEntrada)) {
                throw new IllegalArgumentException(
                        "El cierre del sistema no puede ser anterior a la entrada.");
            }
        }

        if (fechaSalida != null && fechaCierreSistema != null) {
            throw new IllegalArgumentException(
                    "La visita tiene salida real o cierre por sistema, no ambos.");
        }
    }

    private String validarVehiculoPlaca(String vehiculoPlaca) {
        if (vehiculoPlaca != null && vehiculoPlaca.length() > 10) {
            throw new IllegalArgumentException(
                    "La placa del vehículo admite hasta 10 caracteres.");
        }

        return vehiculoPlaca;
    }
}
