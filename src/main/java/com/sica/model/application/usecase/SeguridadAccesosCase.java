package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.EstadoVisita;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.entity.Visita;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

// Consulta visitas y prepara los datos de los reportes de accesos para seguridad.
public class SeguridadAccesosCase {

    private final EjecutorOperaciones ejecutor;

    public SeguridadAccesosCase(EjecutorOperaciones ejecutor) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
    }

    public List<DetalleAccesoSeguridad> consultarVisitasDePersona(Integer supervisorId, String documento) {
        if (documento == null || documento.isBlank() || documento.length() > 20) {
            throw new IllegalArgumentException("El documento es obligatorio y admite hasta 20 caracteres.");
        }
        return ejecutor.ejecutar(supervisorId, "consultar_visitas_seguridad", (repositorios, supervisor) -> {
            Persona persona = repositorios.crearPersonaRepository().buscarPorDocumento(documento)
                    .orElseThrow(() -> new IllegalArgumentException("La persona no está registrada con ese documento."));
            // Incluye solicitudes y visitas de todas las empresas y estados de esa persona.
            return repositorios.crearVisitaRepository().listarPorPersona(persona.getId()).stream()
                    .filter(visita -> persona.getId().equals(visita.getPersonaId()))
                    .sorted(Comparator.comparing(Visita::getId, Comparator.reverseOrder()))
                    .map(visita -> detalle(repositorios, visita))
                    .toList();
        });
    }

    public List<DetalleAccesoSeguridad> consultarIngresosAbiertos(Integer supervisorId) {
        return ejecutor.ejecutar(supervisorId, "consultar_ingresos_abiertos", (repositorios, supervisor) -> {
            Integer dentroId = repositorios.crearEstadoVisitaRepository().buscarPorNombre("Dentro")
                    .orElseThrow(() -> new IllegalStateException("No está configurado el estado Dentro.")).getId();
            // Figurar dentro exige una entrada y que todavía no exista salida real ni cierre del sistema.
            return repositorios.crearVisitaRepository().listarIngresosAbiertos().stream()
                    .filter(visita -> dentroId.equals(visita.getEstadoVisitaId()) && visita.getFechaEntrada() != null
                            && visita.getFechaSalida() == null && visita.getFechaCierreSistema() == null)
                    .sorted(Comparator.comparing(Visita::getFechaEntrada, Comparator.reverseOrder())
                            .thenComparing(Visita::getId, Comparator.reverseOrder()))
                    .map(visita -> detalle(repositorios, visita))
                    .toList();
        });
    }

    // El historial reúne movimientos del período; la visita pudo haber empezado antes.
    public List<DetalleMovimientoAcceso> consultarHistorial(
            Integer supervisorId, LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null || !hasta.isAfter(desde)) {
            throw new IllegalArgumentException("Debes indicar un período con fecha final posterior a la inicial.");
        }
        return ejecutor.ejecutar(supervisorId, "consultar_historial_accesos", (repositorios, supervisor) -> {
            List<DetalleMovimientoAcceso> movimientos = new ArrayList<>();
            for (Visita visita : repositorios.crearVisitaRepository().listarPorMovimientosEnPeriodo(desde, hasta)) {
                if (enPeriodo(visita.getFechaEntrada(), desde, hasta)
                        || enPeriodo(visita.getFechaSalida(), desde, hasta)
                        || enPeriodo(visita.getFechaCierreSistema(), desde, hasta)) {
                    DetalleAccesoSeguridad acceso = detalle(repositorios, visita);
                    agregarMovimiento(movimientos, acceso, TipoMovimientoAcceso.ENTRADA, visita.getFechaEntrada(), desde, hasta);
                    agregarMovimiento(movimientos, acceso, TipoMovimientoAcceso.SALIDA, visita.getFechaSalida(), desde, hasta);
                    agregarMovimiento(movimientos, acceso, TipoMovimientoAcceso.CIERRE_SIN_SALIDA_REGISTRADA,
                            visita.getFechaCierreSistema(), desde, hasta);
                }
            }
            // Stream ordena la colección terminada; no ejecuta consultas en paralelo ni modifica registros.
            return movimientos.stream()
                    .sorted(Comparator.comparing(DetalleMovimientoAcceso::getFechaMovimiento, Comparator.reverseOrder())
                            .thenComparing(movimiento -> movimiento.getAcceso().getVisita().getId(), Comparator.reverseOrder())
                            .thenComparing(DetalleMovimientoAcceso::getTipo, Comparator.reverseOrder()))
                    .toList();
        });
    }

    private void agregarMovimiento(List<DetalleMovimientoAcceso> movimientos, DetalleAccesoSeguridad acceso,
            TipoMovimientoAcceso tipo, LocalDateTime fecha, LocalDateTime desde, LocalDateTime hasta) {
        if (enPeriodo(fecha, desde, hasta)) {
            movimientos.add(new DetalleMovimientoAcceso(acceso, tipo, fecha));
        }
    }

    // Se incluye el comienzo del período y se excluye el final, igual que en las consultas de incidentes.
    private boolean enPeriodo(LocalDateTime fecha, LocalDateTime desde, LocalDateTime hasta) {
        return fecha != null && !fecha.isBefore(desde) && fecha.isBefore(hasta);
    }

    private DetalleAccesoSeguridad detalle(FabricarRepositorios repositorios, Visita visita) {
        Persona persona = repositorios.crearPersonaRepository().buscarPorId(visita.getPersonaId())
                .orElseThrow(() -> new IllegalStateException("La persona del acceso no existe."));
        Empresa empresa = repositorios.crearEmpresaRepository().buscarPorId(visita.getEmpresaDestinoId())
                .orElseThrow(() -> new IllegalStateException("La empresa de destino del acceso no existe."));
        EstadoVisita estado = repositorios.crearEstadoVisitaRepository().buscarPorId(visita.getEstadoVisitaId())
                .orElseThrow(() -> new IllegalStateException("El estado del acceso no está configurado."));
        String funcionario = null;
        if (visita.getFuncionarioDestinatarioId() != null) {
            funcionario = repositorios.crearUsuarioRepository().buscarPorId(visita.getFuncionarioDestinatarioId())
                    .map(Usuario::getNombre)
                    .orElseThrow(() -> new IllegalStateException("El funcionario destinatario del acceso no existe."));
        }
        // El destino pertenece a la visita. Empresas retiradas y cuentas inactivas conservan su historial.
        return new DetalleAccesoSeguridad(visita, persona, estado, empresa.getNombre(), funcionario);
    }
}
