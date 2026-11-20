package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.entity.Visita;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Consulta la bitácora general o la actividad que corresponde al funcionario que actúa.
public class AuditoriaCase {

    private final EjecutorOperaciones ejecutor;

    public AuditoriaCase(EjecutorOperaciones ejecutor) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
    }

    public List<DetalleAuditoria> consultarGeneral(
            Integer responsableId, LocalDateTime desde, LocalDateTime hasta) {
        validarPeriodo(desde, hasta);
        return ejecutor.ejecutar(responsableId, "consultar_auditoria_general", (repositorios, responsable) -> {
            List<BitacoraAuditoria> registros = repositorios.crearBitacoraAuditoriaRepository()
                    .listarPorPeriodo(desde, hasta).stream()
                    .filter(registro -> enPeriodo(registro, desde, hasta))
                    .toList();
            // Incluye éxitos, fallos y registros sin usuario asociado, tal como quedaron guardados.
            return preparar(repositorios, registros);
        });
    }

    public List<DetalleAuditoria> consultarPropia(
            Integer funcionarioId, LocalDateTime desde, LocalDateTime hasta) {
        validarPeriodo(desde, hasta);
        return ejecutor.ejecutar(funcionarioId, "consultar_auditoria_propia", (repositorios, funcionario) -> {
            if (funcionario.getEmpresaId() == null) {
                throw new IllegalStateException("El funcionario debe tener una empresa asignada.");
            }
            Map<Long, BitacoraAuditoria> visibles = new LinkedHashMap<>();
            for (BitacoraAuditoria registro : repositorios.crearBitacoraAuditoriaRepository().listarPorUsuario(funcionario.getId())) {
                if (funcionario.getId().equals(registro.getUsuarioId()) && enPeriodo(registro, desde, hasta)) {
                    agregarSinRepetir(visibles, registro);
                }
            }

            // También se incluyen los movimientos hechos por el guarda o el sistema sobre sus visitas.
            for (Visita visita : repositorios.crearVisitaRepository().listarPorFuncionarioDestinatario(funcionario.getId())) {
                if (!funcionario.getId().equals(visita.getFuncionarioDestinatarioId())
                        || !funcionario.getEmpresaId().equals(visita.getEmpresaDestinoId())) {
                    continue;
                }
                for (BitacoraAuditoria registro : repositorios.crearBitacoraAuditoriaRepository().listarPorRegistro("visitas", visita.getId())) {
                    // La relación debe coincidir en la tabla y en el identificador de esta visita exacta.
                    if ("visitas".equals(registro.getTablaAfectada()) && visita.getId().equals(registro.getRegistroIdAfectado())
                            && enPeriodo(registro, desde, hasta)) {
                        agregarSinRepetir(visibles, registro);
                    }
                }
            }
            // Una aprobación propia puede aparecer por ambas consultas; su identificador evita duplicarla.
            return preparar(repositorios, visibles.values());
        });
    }

    private void agregarSinRepetir(Map<Long, BitacoraAuditoria> registros, BitacoraAuditoria registro) {
        if (registro.getId() == null) {
            throw new IllegalStateException("El registro de auditoría consultado no tiene identificador.");
        }
        registros.putIfAbsent(registro.getId(), registro);
    }

    private List<DetalleAuditoria> preparar(
            FabricarRepositorios repositorios, Collection<BitacoraAuditoria> registros) {
        Map<Integer, String> nombres = new HashMap<>();
        // Stream ordena la colección y convierte cada registro en un resultado para consultar.
        return registros.stream()
                .sorted(Comparator.comparing(BitacoraAuditoria::getFechaHora, Comparator.reverseOrder())
                        .thenComparing(BitacoraAuditoria::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(registro -> {
                    // Un usuario nulo puede representar al sistema o un login que no identificó una cuenta.
                    String nombre = registro.getUsuarioId() == null ? "Sin usuario asociado"
                            : nombres.computeIfAbsent(registro.getUsuarioId(), id -> repositorios.crearUsuarioRepository()
                                    .buscarPorId(id).map(Usuario::getNombre).orElse("Cuenta no disponible"));
                    return new DetalleAuditoria(registro, nombre);
                }).toList();
    }

    // Incluye la fecha inicial y excluye la final, como las demás consultas por período.
    private boolean enPeriodo(BitacoraAuditoria registro, LocalDateTime desde, LocalDateTime hasta) {
        return !registro.getFechaHora().isBefore(desde) && registro.getFechaHora().isBefore(hasta);
    }

    private void validarPeriodo(LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null || !hasta.isAfter(desde)) {
            throw new IllegalArgumentException("Debes indicar un período con fecha final posterior a la inicial.");
        }
    }
}
