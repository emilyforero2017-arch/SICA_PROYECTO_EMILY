package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.FotoPersona;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.policy.HorarioIngreso;
import com.sica.model.domain.policy.HorarioVisitaProgramada;
import com.sica.model.domain.policy.PoliticaHorarioIngreso;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// Reúne la información de portería y confirma entradas de visitas previamente agendadas.
public class ControlAccesoProgramadoCase {

    private final EjecutorOperaciones ejecutor;
    private final Clock reloj;
    private final PoliticaHorarioIngreso politicaHorario = new HorarioVisitaProgramada();

    public ControlAccesoProgramadoCase(EjecutorOperaciones ejecutor) {
        this(ejecutor, Clock.system(ZoneId.of("America/Bogota")));
    }

    public ControlAccesoProgramadoCase(EjecutorOperaciones ejecutor, Clock reloj) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
        this.reloj = Objects.requireNonNull(reloj);
    }

    public Optional<ConsultaIngresoProgramado> consultar(Integer guardaId, String documento) {
        validarDocumento(documento);
        IntentoGuardado intento = new IntentoGuardado("ACTUALIZACION_VIGENCIA_FALLIDA", "visitas", null);
        return ejecutor.ejecutarGuardado(guardaId, "consultar_persona_porteria", intento, (repositorios, guarda) -> {
            if (!repositorios.crearPermisoRepository()
                    .tienePermiso(guarda.getRolId(), "consultar_visita_porteria")) {
                throw new SecurityException("La cuenta no tiene permiso para consultar visitas en portería.");
            }
            Optional<Persona> encontrada = repositorios.crearPersonaRepository().buscarPorDocumento(documento);
            if (encontrada.isEmpty()) {
                return Optional.empty();
            }
            Persona persona = encontrada.get();
            String estadoAcceso = estadoAcceso(repositorios, persona);
            Integer aprobadoId = estadoVisita(repositorios, "Aprobado");
            Integer expiradoId = estadoVisita(repositorios, "Expirado");

            // La consulta también actualiza vencimientos, aunque la tarea periódica aún no haya pasado.
            List<Visita> revisadas = new ArrayList<>();
            for (Visita referencia : repositorios.crearVisitaRepository().listarPorPersona(persona.getId())) {
                if (referencia.getTipoIngreso() == TipoIngreso.INVITADO_PREREGISTRADO
                        && referencia.getFechaEntrada() == null) {
                    Visita actual = repositorios.crearVisitaRepository().buscarPorIdParaActualizar(referencia.getId())
                            .orElseThrow(() -> new IllegalStateException("La visita consultada no existe."));
                    if (persona.getId().equals(actual.getPersonaId())) {
                        VencimientoVisitasCase.expirarSiCorresponde(repositorios, actual, LocalDateTime.now(reloj), intento);
                        revisadas.add(actual);
                    }
                }
            }
            LocalDateTime ahora = LocalDateTime.now(reloj);

            // Mostramos también las expiradas para explicar por qué ya no permiten ingresar.
            // Stream recorre la lista; no crea hilos ni registra entradas simultáneas.
            List<DetalleIngresoProgramado> citas = revisadas.stream()
                    .filter(visita -> visita.getTipoIngreso() == TipoIngreso.INVITADO_PREREGISTRADO)
                    .filter(visita -> aprobadoId.equals(visita.getEstadoVisitaId())
                            || expiradoId.equals(visita.getEstadoVisitaId()))
                    .filter(visita -> visita.getFechaEntrada() == null
                            && visita.getFechaSalida() == null && visita.getFechaCierreSistema() == null)
                    .sorted(Comparator.comparing(Visita::getFechaProgramada).thenComparing(Visita::getId))
                    .map(visita -> detalle(repositorios, persona, estadoAcceso, visita, aprobadoId, ahora))
                    .toList();

            // El guarda recibe identidad, foto, anfitrión y horario con una sola consulta.
            byte[] fotoArchivo = FotoPersona.ARCHIVO_GUARDADO.equals(persona.getUrlFoto())
                    ? repositorios.crearPersonaRepository().buscarFotoArchivo(persona.getId()).orElse(null) : null;
            return Optional.of(new ConsultaIngresoProgramado(persona, estadoAcceso, citas, fotoArchivo));
        });
    }

    public Visita registrarEntrada(Integer guardaId, String documento, Integer visitaId) {
        validarDocumento(documento);
        Objects.requireNonNull(visitaId, "La visita a ingresar es obligatoria.");
        IntentoGuardado intento = new IntentoGuardado("REGISTRO_ENTRADA_FALLIDO", "visitas", visitaId);
        Visita resultado = ejecutor.ejecutarGuardado(guardaId, "registrar_entrada", intento, (repositorios, guarda) -> {
            Visita referencia = repositorios.crearVisitaRepository().buscarPorId(visitaId)
                    .orElseThrow(() -> new IllegalArgumentException("La visita no existe."));

            // Primero protegemos la empresa, después la persona y su visita durante esta operación.
            // El repositorio JDBC deberá mantener estas protecciones hasta terminar la transacción.
            Empresa empresa = repositorios.crearEmpresaRepository()
                    .buscarPorIdParaActualizar(referencia.getEmpresaDestinoId())
                    .orElseThrow(() -> new IllegalStateException("La empresa de destino no existe."));
            Persona persona = repositorios.crearPersonaRepository().buscarPorDocumentoParaActualizar(documento)
                    .orElseThrow(() -> new IllegalArgumentException("La persona no está registrada."));
            Visita visita = repositorios.crearVisitaRepository().buscarPorIdParaActualizar(visitaId)
                    .orElseThrow(() -> new IllegalArgumentException("La visita no existe."));
            if (!empresa.getId().equals(visita.getEmpresaDestinoId())) {
                throw new IllegalStateException("El destino cambió; vuelve a consultar la visita.");
            }
            Usuario funcionario = repositorios.crearUsuarioRepository()
                    .buscarPorIdParaActualizar(visita.getFuncionarioDestinatarioId())
                    .orElseThrow(() -> new IllegalStateException("El funcionario destinatario no existe."));
            LocalDateTime ahora = LocalDateTime.now(reloj);

            if (persona.getId().equals(visita.getPersonaId())
                    && visita.getTipoIngreso() == TipoIngreso.INVITADO_PREREGISTRADO
                    && VencimientoVisitasCase.expirarSiCorresponde(repositorios, visita, ahora, intento)) {
                // Confirmamos la expiración antes de comunicar que esta autorización ya no permite entrar.
                return visita;
            }

            // Volvemos a revisar los datos actuales; una consulta anterior no garantiza el ingreso.
            String impedimento = impedimento(repositorios, persona, estadoAcceso(repositorios, persona),
                    visita, empresa, funcionario, estadoVisita(repositorios, "Aprobado"), ahora);
            if (impedimento != null) {
                throw new IllegalStateException(impedimento);
            }
            Integer dentroId = estadoVisita(repositorios, "Dentro");
            Optional<Visita> anterior = repositorios.crearVisitaRepository()
                    .buscarIngresoAbiertoPorPersonaParaActualizar(persona.getId());
            Integer cierreId = anterior.isPresent()
                    ? estadoVisita(repositorios, "Cerrada por Sistema - Salida no registrada") : null;

            // Las consultas anteriores pudieron tardar: usamos la hora actual para decidir y guardar el ingreso.
            ahora = LocalDateTime.now(reloj);
            if (!politicaHorario.calcular(visita).contiene(ahora)) {
                if (VencimientoVisitasCase.expirarSiCorresponde(repositorios, visita, ahora, intento)) {
                    return visita;
                }
                throw new IllegalStateException("La visita está fuera de su horario permitido de ingreso.");
            }

            if (anterior.isPresent()) {
                Visita abierta = anterior.get();
                if (abierta.getId().equals(visitaId) || abierta.getFechaEntrada().isAfter(ahora)) {
                    throw new IllegalStateException("El ingreso anterior no se puede regularizar en este momento.");
                }
            }
            intento.iniciar();
            if (anterior.isPresent()) {
                // Sabemos que volvió, pero no inventamos cuándo salió la vez anterior.
                Visita abierta = anterior.get();
                abierta.actualizarMovimiento(cierreId, abierta.getFechaEntrada(), null, ahora);
                actualizar(repositorios, abierta);
                auditar(repositorios, guarda.getId(), ahora, "CIERRE_SALIDA_NO_REGISTRADA", abierta.getId(),
                        "Se regulariza al registrar un nuevo ingreso. La hora real de salida se desconoce.");
            }

            visita.actualizarMovimiento(dentroId, ahora, null, null);
            actualizar(repositorios, visita);
            auditar(repositorios, guarda.getId(), ahora, "REGISTRO_ENTRADA", visitaId,
                    "Persona: " + persona.getId() + "; empresa: " + empresa.getId()
                    + "; funcionario: " + funcionario.getId() + "; entrada: " + ahora);
            // La entrada y sus auditorías se confirman juntas; si falla algo, se deshace todo.
            return visita;
        });
        if (resultado.getFechaEntrada() == null) {
            throw new IllegalStateException("La autorización está expirada y ya no permite ingresar.");
        }
        return resultado;
    }

    private DetalleIngresoProgramado detalle(
            FabricarRepositorios repositorios, Persona persona, String estadoAcceso,
            Visita visita, Integer aprobadoId, LocalDateTime ahora) {
        Empresa empresa = repositorios.crearEmpresaRepository().buscarPorId(visita.getEmpresaDestinoId())
                .orElseThrow(() -> new IllegalStateException("La empresa de la visita no existe."));
        Usuario funcionario = repositorios.crearUsuarioRepository().buscarPorId(visita.getFuncionarioDestinatarioId())
                .orElseThrow(() -> new IllegalStateException("El funcionario de la visita no existe."));
        HorarioIngreso horario = politicaHorario.calcular(visita);
        String motivo = impedimento(repositorios, persona, estadoAcceso, visita, empresa, funcionario, aprobadoId, ahora);
        return new DetalleIngresoProgramado(visita, empresa.getNombre(), funcionario.getNombre(), horario,
                motivo == null, motivo == null ? "Puede registrar la entrada." : motivo);
    }

    // La consulta y la confirmación utilizan las mismas comprobaciones de autorización.
    private String impedimento(
            FabricarRepositorios repositorios, Persona persona, String estadoAcceso,
            Visita visita, Empresa empresa, Usuario funcionario, Integer aprobadoId, LocalDateTime ahora) {
        if (!persona.getId().equals(visita.getPersonaId())) {
            return "La visita no corresponde al documento presentado.";
        }
        if (visita.getTipoIngreso() != TipoIngreso.INVITADO_PREREGISTRADO) {
            return "Esta operación corresponde únicamente a visitas preregistradas.";
        }
        if (estadoVisita(repositorios, "Expirado").equals(visita.getEstadoVisitaId())) {
            return "La autorización está expirada; el horario permitido ya terminó.";
        }
        if (!aprobadoId.equals(visita.getEstadoVisitaId()) || visita.getFechaEntrada() != null
                || visita.getFechaSalida() != null || visita.getFechaCierreSistema() != null) {
            return "La visita ya ingresó o no conserva una autorización aprobada.";
        }
        if (!"Activo".equals(estadoAcceso)) {
            return "La persona tiene una restricción de ingreso; debe revisarla el supervisor de seguridad.";
        }
        if (!empresa.isEstaActiva()) {
            return "La empresa de destino está desactivada.";
        }
        String rol = repositorios.crearRolRepository().buscarPorId(funcionario.getRolId())
                .orElseThrow(() -> new IllegalStateException("El rol del funcionario no existe.")).getNombreRol();
        if (!funcionario.isEstaActivo() || !empresa.getId().equals(funcionario.getEmpresaId())
                || !"Funcionario de Empresa".equals(rol)) {
            return "El funcionario destinatario ya no está habilitado para recibir la visita.";
        }
        if (visita.getFechaAprobacion() == null || visita.getAprobadaPorId() == null
                || !funcionario.getId().equals(visita.getAprobadaPorId())
                || visita.getFechaAprobacion().isAfter(ahora)) {
            return "La aprobación de la visita no es válida.";
        }
        if (!politicaHorario.calcular(visita).contiene(ahora)) {
            return "Está fuera del horario permitido: una hora antes y una hora después de la cita.";
        }
        return null;
    }

    private String estadoAcceso(FabricarRepositorios repositorios, Persona persona) {
        return repositorios.crearEstadoAccesoPersonaRepository().buscarPorId(persona.getEstadoAccesoId())
                .orElseThrow(() -> new IllegalStateException("El estado de acceso no está configurado.")).getNombreEstado();
    }

    private Integer estadoVisita(FabricarRepositorios repositorios, String nombre) {
        return repositorios.crearEstadoVisitaRepository().buscarPorNombre(nombre)
                .orElseThrow(() -> new IllegalStateException("No está configurado el estado: " + nombre)).getId();
    }

    private void actualizar(FabricarRepositorios repositorios, Visita visita) {
        if (!repositorios.crearVisitaRepository().actualizar(visita)) {
            throw new IllegalStateException("No se pudo guardar el movimiento de la visita.");
        }
    }

    private void auditar(FabricarRepositorios repositorios, Integer guardaId, LocalDateTime ahora,
            String accion, Integer visitaId, String detalle) {
        repositorios.crearBitacoraAuditoriaRepository().guardar(
                new BitacoraAuditoria(guardaId, ahora, accion, "visitas", visitaId, detalle));
    }

    private void validarDocumento(String documento) {
        if (documento == null || documento.isBlank() || documento.length() > 20) {
            throw new IllegalArgumentException("El documento es obligatorio y admite hasta 20 caracteres.");
        }
    }
}
