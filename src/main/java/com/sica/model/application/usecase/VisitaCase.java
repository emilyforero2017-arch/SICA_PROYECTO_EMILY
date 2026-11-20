package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.EstadoVisita;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.policy.HorarioIngreso;
import com.sica.model.domain.policy.HorarioVisitaProgramada;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// Gestiona la programación de visitas que realizarán los funcionarios de empresa.
public class VisitaCase {

    private final EjecutorOperaciones ejecutor;
    private final Clock reloj;
    private final HorarioVisitaProgramada horarioProgramado = new HorarioVisitaProgramada();

    public VisitaCase(EjecutorOperaciones ejecutor) {
        this(ejecutor, Clock.system(ZoneId.of("America/Bogota")));
    }

    // Recibir el reloj también permite comprobar horarios concretos sin esperar ni cambiar el computador.
    public VisitaCase(EjecutorOperaciones ejecutor, Clock reloj) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
        this.reloj = Objects.requireNonNull(reloj);
    }

    public Visita preregistrar(
            Integer funcionarioId, Integer personaId, LocalDateTime fechaProgramada, String placa) {
        if (personaId == null) {
            throw new IllegalArgumentException("La persona es obligatoria.");
        }
        validarProgramacion(fechaProgramada, placa);

        IntentoGuardado intento = new IntentoGuardado("PREREGISTRO_VISITA_FALLIDO", "visitas", null);
        return ejecutor.ejecutarGuardado(funcionarioId, "preregistrar_visita", intento, (repositorios, funcionario) -> {
            exigirEmpresa(funcionario);
            Persona persona = repositorios.crearPersonaRepository().buscarPorId(personaId)
                    .orElseThrow(() -> new IllegalArgumentException("La persona no está registrada."));
            return guardarProgramada(repositorios, funcionario, persona, fechaProgramada, placa, intento);
        });
    }

    // Este preregistro recibe el documento y reúne el registro del invitado con su cita.
    public Visita preregistrar(
            Integer funcionarioId, String documento, String nombre, String urlFoto,
            LocalDateTime fechaProgramada, String placa) {
        if (documento == null || documento.isBlank() || documento.length() > 20) {
            throw new IllegalArgumentException("El documento es obligatorio y admite hasta 20 caracteres.");
        }
        validarProgramacion(fechaProgramada, placa);
        IntentoGuardado intento = new IntentoGuardado("PREREGISTRO_VISITA_FALLIDO", "visitas", null,
                "Documento: " + documento + "; cita solicitada: " + fechaProgramada);
        return ejecutor.ejecutarGuardado(funcionarioId, "preregistrar_visita", intento, (repositorios, funcionario) -> {
            exigirEmpresa(funcionario);
            Optional<Persona> encontrada = repositorios.crearPersonaRepository().buscarPorDocumento(documento);
            Persona persona;
            if (encontrada.isPresent()) {
                // Se conserva su identidad, incluso si es trabajadora o tiene una prohibición.
                persona = encontrada.get();
            } else {
                if (!repositorios.crearPermisoRepository().tienePermiso(funcionario.getRolId(), "registrar_invitado")) {
                    throw new SecurityException("La cuenta no tiene permiso para registrar un invitado nuevo.");
                }
                persona = PersonaCase.registrarInvitadoEnOperacion(
                        repositorios, funcionarioId, nombre, documento, urlFoto, intento, LocalDateTime.now(reloj));
            }
            // Se usan los mismos repositorios: persona, cita y auditorías se confirman o deshacen juntas.
            return guardarProgramada(repositorios, funcionario, persona, fechaProgramada, placa, intento);
        });
    }

    private Visita guardarProgramada(
            FabricarRepositorios repositorios, Usuario funcionario, Persona persona,
            LocalDateTime fechaProgramada, String placa, IntentoGuardado intento) {
        EstadoVisita aprobado = estado(repositorios, "Aprobado");
        Visita nueva = new Visita(
                persona.getId(), funcionario.getEmpresaId(), funcionario.getId(),
                TipoIngreso.INVITADO_PREREGISTRADO, fechaProgramada, LocalDateTime.now(reloj),
                null, null, null, aprobado.getId(), placa, funcionario.getId());
        intento.iniciar();
        Visita registrada = repositorios.crearVisitaRepository().guardar(nueva);
        guardarAuditoria(repositorios, funcionario.getId(), LocalDateTime.now(reloj), "PREREGISTRO_VISITA",
                registrada.getId(), "Persona: " + persona.getId() + "; empresa: " + funcionario.getEmpresaId()
                + "; funcionario destinatario: " + funcionario.getId() + "; cita: " + fechaProgramada);
        return registrada;
    }

    private void validarProgramacion(LocalDateTime fechaProgramada, String placa) {
        if (fechaProgramada == null) {
            throw new IllegalArgumentException("La fecha y hora de la visita son obligatorias.");
        }
        // Se comprueba antes de guardar una persona nueva para evitar escrituras con datos incompletos.
        if (placa != null && placa.length() > 10) {
            throw new IllegalArgumentException("La placa del vehículo admite hasta 10 caracteres.");
        }
    }

    // Devuelve las visitas programadas del propio funcionario, con la persona y el estado de cada cita.
    public List<DetalleVisitaProgramada> listarProgramadas(Integer funcionarioId) {
        IntentoGuardado intento = new IntentoGuardado(
                "ACTUALIZACION_VIGENCIA_FALLIDA", "visitas", null,
                "Consulta de visitas programadas del funcionario: " + funcionarioId);
        return ejecutor.ejecutarGuardado(funcionarioId, "consultar_visitas_asignadas", intento, (repositorios, funcionario) -> {
            exigirEmpresa(funcionario);
            List<DetalleVisitaProgramada> resultado = new ArrayList<>();
            for (Visita referencia : repositorios.crearVisitaRepository().listarPorFuncionarioDestinatario(funcionarioId)) {
                if (!esProgramadaPropia(referencia, funcionario)) {
                    continue;
                }
                Visita visita = repositorios.crearVisitaRepository().buscarPorIdParaActualizar(referencia.getId())
                        .orElseThrow(() -> new IllegalStateException("La visita consultada no existe."));
                if (!esProgramadaPropia(visita, funcionario)) {
                    continue;
                }
                VencimientoVisitasCase.expirarSiCorresponde(repositorios, visita, LocalDateTime.now(reloj), intento);
                Persona persona = repositorios.crearPersonaRepository().buscarPorId(visita.getPersonaId())
                        .orElseThrow(() -> new IllegalStateException("La persona de la visita no existe."));
                EstadoVisita estado = repositorios.crearEstadoVisitaRepository().buscarPorId(visita.getEstadoVisitaId())
                        .orElseThrow(() -> new IllegalStateException("El estado de la visita no está configurado."));
                resultado.add(new DetalleVisitaProgramada(visita, persona, estado, horarioProgramado.calcular(visita)));
            }
            // Conservamos también las canceladas y expiradas para consultar el historial propio.
            return resultado.stream().sorted(Comparator
                    .comparing((DetalleVisitaProgramada detalle) -> detalle.getVisita().getFechaProgramada())
                    .thenComparing(detalle -> detalle.getVisita().getId())).toList();
        });
    }

    public Visita cancelar(Integer funcionarioId, Integer visitaId, String motivo) {
        validarCambio(visitaId, motivo);
        IntentoGuardado intento = new IntentoGuardado("CANCELACION_VISITA_FALLIDA", "visitas", visitaId,
                "Funcionario: " + funcionarioId + "; motivo de la cancelación: " + motivo);
        Optional<Visita> resultado = ejecutor.ejecutarGuardado(funcionarioId, "cancelar_visita", intento, (repositorios, funcionario) -> {
            Visita anterior = buscarProgramadaPropia(repositorios, funcionario, visitaId);
            LocalDateTime ahora = LocalDateTime.now(reloj);
            if (VencimientoVisitasCase.expirarSiCorresponde(repositorios, anterior, ahora, intento)) {
                // Confirmamos el vencimiento y después comunicamos que ya no se puede cancelar.
                return Optional.empty();
            }
            validarAprobadaSinEntrada(repositorios, anterior);
            Integer canceladaId = estado(repositorios, "Cancelada").getId();
            Visita cancelada = copiar(anterior);
            cancelada.actualizarMovimiento(canceladaId, null, null, null);

            intento.iniciar();
            actualizar(repositorios, cancelada);
            guardarAuditoria(repositorios, funcionarioId, ahora, "CANCELACION_VISITA", visitaId,
                    "Persona: " + anterior.getPersonaId() + "; empresa: " + anterior.getEmpresaDestinoId()
                    + "; cita: " + anterior.getFechaProgramada()
                    + "; estado anterior: Aprobado; estado actual: Cancelada; motivo: " + motivo);
            return Optional.of(cancelada);
        });
        return resultado.orElseThrow(() -> new IllegalStateException(
                "La autorización expiró y ya no se puede cancelar."));
    }

    public Visita reprogramar(
            Integer funcionarioId, Integer visitaId, LocalDateTime nuevaFecha, String motivo) {
        validarCambio(visitaId, motivo);
        if (nuevaFecha == null) {
            throw new IllegalArgumentException("La nueva fecha y hora son obligatorias.");
        }
        IntentoGuardado intento = new IntentoGuardado("REPROGRAMACION_VISITA_FALLIDA", "visitas", visitaId,
                "Funcionario: " + funcionarioId + "; nueva cita: " + nuevaFecha + "; motivo: " + motivo);
        Optional<Visita> resultado = ejecutor.ejecutarGuardado(funcionarioId, "reprogramar_visita", intento, (repositorios, funcionario) -> {
            Visita anterior = buscarProgramadaPropia(repositorios, funcionario, visitaId);
            LocalDateTime ahora = LocalDateTime.now(reloj);
            if (VencimientoVisitasCase.expirarSiCorresponde(repositorios, anterior, ahora, intento)) {
                return Optional.empty();
            }
            validarAprobadaSinEntrada(repositorios, anterior);

            Visita reprogramada = copiar(anterior);
            reprogramada.setFechaProgramada(nuevaFecha);
            HorarioIngreso nuevoHorario = horarioProgramado.calcular(reprogramada);
            if (ahora.isAfter(nuevoHorario.getHasta())) {
                throw new IllegalArgumentException("El nuevo horario de ingreso ya está vencido.");
            }
            if (nuevaFecha.equals(anterior.getFechaProgramada())) {
                return Optional.of(anterior);
            }

            // Conservamos la aprobación del mismo funcionario; solo cambia la programación.
            HorarioIngreso horarioAnterior = horarioProgramado.calcular(anterior);
            intento.iniciar();
            actualizar(repositorios, reprogramada);
            guardarAuditoria(repositorios, funcionarioId, ahora, "REPROGRAMACION_VISITA", visitaId,
                    "Cita anterior: " + anterior.getFechaProgramada() + "; cita nueva: " + nuevaFecha
                    + "; horario anterior: " + horarioAnterior.getDesde() + " hasta " + horarioAnterior.getHasta()
                    + "; horario nuevo: " + nuevoHorario.getDesde() + " hasta " + nuevoHorario.getHasta()
                    + "; motivo: " + motivo);
            return Optional.of(reprogramada);
        });
        return resultado.orElseThrow(() -> new IllegalStateException(
                "La autorización expiró y ya no se puede reprogramar."));
    }

    private Visita buscarProgramadaPropia(FabricarRepositorios repositorios, Usuario funcionario, Integer visitaId) {
        exigirEmpresa(funcionario);
        Visita visita = repositorios.crearVisitaRepository().buscarPorIdParaActualizar(visitaId)
                .orElseThrow(() -> new IllegalArgumentException("La visita no existe."));
        if (!esProgramadaPropia(visita, funcionario)) {
            throw new IllegalArgumentException("Solo puedes modificar visitas programadas que tú atenderás en tu empresa.");
        }
        return visita;
    }

    private boolean esProgramadaPropia(Visita visita, Usuario funcionario) {
        return visita.getTipoIngreso() == TipoIngreso.INVITADO_PREREGISTRADO
                && Objects.equals(funcionario.getId(), visita.getFuncionarioDestinatarioId())
                && Objects.equals(funcionario.getEmpresaId(), visita.getEmpresaDestinoId());
    }

    private void exigirEmpresa(Usuario funcionario) {
        if (funcionario.getEmpresaId() == null) {
            throw new IllegalStateException("El funcionario debe tener una empresa asignada.");
        }
    }

    private void validarCambio(Integer visitaId, String motivo) {
        if (visitaId == null) {
            throw new IllegalArgumentException("La visita a modificar es obligatoria.");
        }
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo del cambio es obligatorio.");
        }
    }

    private void validarAprobadaSinEntrada(FabricarRepositorios repositorios, Visita visita) {
        if (!estado(repositorios, "Aprobado").getId().equals(visita.getEstadoVisitaId())
                || visita.getFechaEntrada() != null || visita.getFechaSalida() != null
                || visita.getFechaCierreSistema() != null) {
            throw new IllegalStateException("La visita debe seguir aprobada y no tener una entrada registrada.");
        }
    }

    private EstadoVisita estado(FabricarRepositorios repositorios, String nombre) {
        return repositorios.crearEstadoVisitaRepository().buscarPorNombre(nombre)
                .orElseThrow(() -> new IllegalStateException("No está configurado el estado: " + nombre));
    }

    private Visita copiar(Visita visita) {
        return new Visita(visita.getId(), visita.getPersonaId(), visita.getEmpresaDestinoId(),
                visita.getFuncionarioDestinatarioId(), visita.getTipoIngreso(), visita.getFechaProgramada(),
                visita.getFechaAprobacion(), visita.getFechaEntrada(), visita.getFechaSalida(),
                visita.getFechaCierreSistema(), visita.getEstadoVisitaId(), visita.getVehiculoPlaca(), visita.getAprobadaPorId());
    }

    private void actualizar(FabricarRepositorios repositorios, Visita visita) {
        if (!repositorios.crearVisitaRepository().actualizar(visita)) {
            throw new IllegalStateException("No fue posible guardar el cambio de la visita.");
        }
    }

    private void guardarAuditoria(FabricarRepositorios repositorios, Integer funcionarioId, LocalDateTime ahora,
            String accion, Integer visitaId, String detalles) {
        repositorios.crearBitacoraAuditoriaRepository().guardar(
                new BitacoraAuditoria(funcionarioId, ahora, accion, "visitas", visitaId, detalles));
    }
}
