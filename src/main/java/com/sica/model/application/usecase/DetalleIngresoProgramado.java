package com.sica.model.application.usecase;

import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.policy.HorarioIngreso;
import java.util.Objects;

// Reúne lo necesario para mostrar una cita en portería, sin exponer datos de la cuenta del anfitrión.
public final class DetalleIngresoProgramado {

    private final Visita visita;
    private final String empresa;
    private final String funcionario;
    private final HorarioIngreso horario;
    private final boolean permiteIngreso;
    private final String mensaje;

    public DetalleIngresoProgramado(
            Visita visita, String empresa, String funcionario, HorarioIngreso horario,
            boolean permiteIngreso, String mensaje) {
        this.visita = Objects.requireNonNull(visita);
        this.empresa = Objects.requireNonNull(empresa);
        this.funcionario = Objects.requireNonNull(funcionario);
        this.horario = Objects.requireNonNull(horario);
        this.permiteIngreso = permiteIngreso;
        this.mensaje = Objects.requireNonNull(mensaje);
    }

    public Visita getVisita() { return visita; }
    public String getEmpresa() { return empresa; }
    public String getFuncionario() { return funcionario; }
    public HorarioIngreso getHorario() { return horario; }
    public boolean isPermiteIngreso() { return permiteIngreso; }
    public String getMensaje() { return mensaje; }
}
