package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.EstadoVisita;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.Rol;
import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.TipoPersona;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.policy.PoliticaActividadEmpresa;
import com.sica.model.domain.policy.HorarioIngreso;
import com.sica.model.domain.policy.HorarioCarnetOlvidado;
import com.sica.model.domain.policy.HorarioVisitaNoAnunciada;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// Tramita solicitudes que necesitan una decisión del funcionario destinatario.
public class SolicitudIngresoCase {

    // Estas acciones identifican la creación y la decisión que alimentan los avisos de portería.
    static final String SOLICITUD_CREADA = "SOLICITUD_VISITA_NO_ANUNCIADA";
    static final String SOLICITUD_OLVIDO_CREADA = "SOLICITUD_CARNET_OLVIDADO";
    static final String SOLICITUD_APROBADA = "APROBACION_SOLICITUD";
    static final String SOLICITUD_RECHAZADA = "RECHAZO_SOLICITUD";

    private final EjecutorOperaciones ejecutor;
    private final Clock reloj;
    private final PoliticaActividadEmpresa politicaEmpresa = new PoliticaActividadEmpresa();

    public SolicitudIngresoCase(EjecutorOperaciones ejecutor) {
        this(ejecutor, Clock.system(ZoneId.of("America/Bogota")));
    }

    public SolicitudIngresoCase(EjecutorOperaciones ejecutor, Clock reloj) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
        this.reloj = Objects.requireNonNull(reloj);
    }

    public Visita solicitarSinAgenda(
            Integer guardaId, Integer empresaId, Integer funcionarioId,
            String documento, String nombre, String urlFoto, String placa) {
        validarSolicitud(empresaId, funcionarioId, documento, placa);
        IntentoGuardado intento = new IntentoGuardado("SOLICITUD_VISITA_NO_ANUNCIADA_FALLIDA", "visitas", null,
                "Documento: " + documento + "; empresa: " + empresaId + "; destinatario: " + funcionarioId);

        return ejecutor.ejecutarGuardado(guardaId, "solicitar_visita_no_anunciada", intento, (repositorios, guarda) -> {
            // La empresa y el destinatario se comprueban otra vez al enviar, aunque ya se hubieran listado.
            validarDestino(repositorios, empresaId, funcionarioId);
            EstadoVisita pendiente = estadoPendiente(repositorios);
            Optional<Persona> encontrada = repositorios.crearPersonaRepository().buscarPorDocumento(documento);
            Persona persona;
            if (encontrada.isPresent()) {
                // No modificamos la foto, el vínculo laboral ni las restricciones de alguien ya registrado.
                persona = encontrada.get();
            } else {
                if (!repositorios.crearPermisoRepository().tienePermiso(guarda.getRolId(), "registrar_invitado")) {
                    throw new SecurityException("La cuenta no tiene permiso para registrar un invitado nuevo.");
                }
                persona = PersonaCase.registrarInvitadoEnOperacion(
                        repositorios, guardaId, nombre, documento, urlFoto, intento, LocalDateTime.now(reloj));
            }

            // Todavía no hay aprobación ni entrada; la autorización de 30 minutos comenzará al aprobar.
            Visita solicitud = new Visita(persona.getId(), empresaId, funcionarioId,
                    TipoIngreso.INVITADO_NO_ANUNCIADO, null, null, null, null, null,
                    pendiente.getId(), placa, null);
            intento.iniciar();
            Visita registrada = repositorios.crearVisitaRepository().guardar(solicitud);
            repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                    guardaId, LocalDateTime.now(reloj), SOLICITUD_CREADA, "visitas", registrada.getId(),
                    "Persona: " + persona.getId() + "; documento: " + persona.getDocumentoIdentidad()
                    + "; empresa de destino: " + empresaId + "; funcionario destinatario: " + funcionarioId
                    + "; estado: " + pendiente.getNombreEstado()));
            // El invitado nuevo, la solicitud y sus auditorías forman parte de la misma operación.
            return registrada;
        });
    }

    public Visita solicitarPorCarnetOlvidado(
            Integer guardaId, String documento, Integer funcionarioId, String placa) {
        validarDocumentoYPlaca(documento, placa);
        if (funcionarioId == null) {
            throw new IllegalArgumentException("El funcionario indicado por el trabajador es obligatorio.");
        }
        IntentoGuardado intento = new IntentoGuardado("SOLICITUD_CARNET_OLVIDADO_FALLIDA", "visitas", null,
                "Documento: " + documento + "; funcionario indicado: " + funcionarioId);
        return ejecutor.ejecutarGuardado(guardaId, "solicitar_ingreso_carnet_olvidado", intento, (repositorios, guarda) -> {
            Persona referencia = repositorios.crearPersonaRepository().buscarPorDocumento(documento)
                    .orElseThrow(() -> new IllegalArgumentException("El trabajador debe estar registrado."));
            if (referencia.getTipoPersona() != TipoPersona.TRABAJADOR) {
                throw new IllegalArgumentException("El pase por carnet olvidado corresponde a un trabajador.");
            }
            // La empresa se obtiene del trabajador: este pase es para su ingreso laboral.
            Empresa empresa = repositorios.crearEmpresaRepository().buscarPorIdParaActualizar(referencia.getEmpresaId())
                    .orElseThrow(() -> new IllegalStateException("La empresa del trabajador no existe."));
            Persona trabajador = repositorios.crearPersonaRepository().buscarPorDocumentoParaActualizar(documento)
                    .orElseThrow(() -> new IllegalArgumentException("El trabajador ya no está registrado con ese documento."));
            if (!referencia.getId().equals(trabajador.getId())) {
                throw new IllegalStateException("Los datos del trabajador cambiaron; vuelve a consultarlo.");
            }
            politicaEmpresa.validarIngresoLaboral(trabajador, empresa);
            validarDestino(repositorios, empresa.getId(), funcionarioId);
            EstadoVisita pendiente = estadoPendiente(repositorios, TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO);
            Visita solicitud = new Visita(trabajador.getId(), empresa.getId(), funcionarioId,
                    TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO, null, null, null, null, null,
                    pendiente.getId(), placa, null);
            intento.iniciar();
            Visita registrada = repositorios.crearVisitaRepository().guardar(solicitud);
            if (registrada == null || registrada.getId() == null) {
                throw new IllegalStateException("No fue posible obtener la solicitud guardada.");
            }
            repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                    guarda.getId(), LocalDateTime.now(reloj), SOLICITUD_OLVIDO_CREADA, "visitas", registrada.getId(),
                    "Trabajador: " + trabajador.getId() + "; documento: " + documento
                    + "; empresa: " + empresa.getId() + "; funcionario indicado: " + funcionarioId));
            return registrada;
        });
    }

    // Cada funcionario ve únicamente las solicitudes pendientes dirigidas a él y a su empresa.
    public List<DetalleSolicitudIngreso> listarPendientes(Integer funcionarioId) {
        return ejecutor.ejecutar(funcionarioId, "consultar_visitas_asignadas", (repositorios, funcionario) -> {
            if (funcionario.getEmpresaId() == null) {
                throw new IllegalStateException("El funcionario debe tener una empresa asignada.");
            }
            EstadoVisita pendiente = estadoPendiente(repositorios);
            EstadoVisita pendienteOlvido = estadoPendiente(repositorios, TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO);
            return repositorios.crearVisitaRepository().listarPorFuncionarioDestinatario(funcionarioId).stream()
                    .filter(solicitud -> esSolicitud(solicitud.getTipoIngreso()))
                    .filter(solicitud -> funcionarioId.equals(solicitud.getFuncionarioDestinatarioId())
                            && funcionario.getEmpresaId().equals(solicitud.getEmpresaDestinoId())
                            && (solicitud.getTipoIngreso() == TipoIngreso.INVITADO_NO_ANUNCIADO ? pendiente : pendienteOlvido)
                                    .getId().equals(solicitud.getEstadoVisitaId())
                            && solicitud.getFechaAprobacion() == null && solicitud.getAprobadaPorId() == null
                            && solicitud.getFechaEntrada() == null && solicitud.getFechaSalida() == null
                            && solicitud.getFechaCierreSistema() == null)
                    .sorted(Comparator.comparing(Visita::getId))
                    .map(solicitud -> {
                        Persona persona = repositorios.crearPersonaRepository().buscarPorId(solicitud.getPersonaId())
                                .orElseThrow(() -> new IllegalStateException("La persona de la solicitud no existe."));
                        return new DetalleSolicitudIngreso(solicitud, persona,
                                solicitud.getTipoIngreso() == TipoIngreso.INVITADO_NO_ANUNCIADO ? pendiente : pendienteOlvido);
                    }).toList();
        });
    }

    // Autoriza la solicitud pendiente; la fecha de aprobación inicia el intervalo de 30 minutos.
    public Visita aprobar(Integer funcionarioId, Integer solicitudId) {
        return resolver(funcionarioId, solicitudId, true, null);
    }

    public Visita rechazar(Integer funcionarioId, Integer solicitudId) {
        return rechazar(funcionarioId, solicitudId, null);
    }

    // El motivo adicional es opcional y se conserva en la auditoría de la decisión.
    public Visita rechazar(Integer funcionarioId, Integer solicitudId, String motivo) {
        return resolver(funcionarioId, solicitudId, false, motivo);
    }

    private Visita resolver(Integer funcionarioId, Integer solicitudId, boolean aprobar, String motivo) {
        if (solicitudId == null) {
            throw new IllegalArgumentException("La solicitud a resolver es obligatoria.");
        }
        String permiso = aprobar ? "aprobar_solicitud_ingreso" : "rechazar_solicitud_ingreso";
        String accion = aprobar ? SOLICITUD_APROBADA : SOLICITUD_RECHAZADA;
        String accionFallida = aprobar ? "APROBACION_SOLICITUD_FALLIDA" : "RECHAZO_SOLICITUD_FALLIDO";
        String explicacion = motivo == null || motivo.isBlank() ? "Sin explicación adicional." : motivo;
        IntentoGuardado intento = new IntentoGuardado(accionFallida, "visitas", solicitudId,
                "Funcionario: " + funcionarioId + "; decisión: " + (aprobar ? "aprobar" : "rechazar")
                + (aprobar ? "" : "; motivo: " + explicacion));

        return ejecutor.ejecutarGuardado(funcionarioId, permiso, intento, (repositorios, referencia) -> {
            Visita referenciaSolicitud = repositorios.crearVisitaRepository().buscarPorId(solicitudId)
                    .orElseThrow(() -> new IllegalArgumentException("La solicitud no existe."));
            if (!esSolicitud(referenciaSolicitud.getTipoIngreso())
                    || !funcionarioId.equals(referenciaSolicitud.getFuncionarioDestinatarioId())
                    || !Objects.equals(referencia.getEmpresaId(), referenciaSolicitud.getEmpresaDestinoId())) {
                throw new IllegalArgumentException("Solo puedes resolver las solicitudes dirigidas a ti y a tu empresa.");
            }
            // Antes de aprobar el pase laboral comprobamos de nuevo al trabajador, con su registro protegido.
            Persona trabajador = aprobar && referenciaSolicitud.getTipoIngreso() == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO
                    ? repositorios.crearPersonaRepository().buscarPorIdParaActualizar(referenciaSolicitud.getPersonaId())
                            .orElseThrow(() -> new IllegalStateException("El trabajador del pase no existe."))
                    : null;
            // El ejecutor ya protegió la empresa. Releemos la cuenta antes de decidir.
            Usuario funcionario = buscarFuncionarioActual(repositorios, referencia);
            Visita anterior = buscarPendientePropia(repositorios, funcionario, solicitudId);
            if (aprobar && anterior.getTipoIngreso() == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO) {
                if (trabajador == null || !trabajador.getId().equals(anterior.getPersonaId())) {
                    throw new IllegalStateException("Los datos de la solicitud cambiaron; vuelve a consultarla.");
                }
                Empresa empresa = repositorios.crearEmpresaRepository().buscarPorIdParaActualizar(anterior.getEmpresaDestinoId())
                        .orElseThrow(() -> new IllegalStateException("La empresa del pase no existe."));
                politicaEmpresa.validarIngresoLaboral(trabajador, empresa);
            }
            EstadoVisita nuevoEstado = estado(repositorios, aprobar ? "Aprobado" : "Rechazado");
            LocalDateTime ahora = LocalDateTime.now(reloj);
            Visita resuelta = copiar(anterior);

            String detalles = "Persona: " + anterior.getPersonaId()
                    + "; empresa: " + anterior.getEmpresaDestinoId()
                    + "; funcionario destinatario: " + funcionario.getId()
                    + "; estado anterior: " + estadoPendiente(repositorios, anterior.getTipoIngreso()).getNombreEstado()
                    + "; estado actual: " + nuevoEstado.getNombreEstado();
            if (aprobar) {
                resuelta.actualizarAprobacion(funcionario.getId(), ahora);
                HorarioIngreso horario = resuelta.getTipoIngreso() == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO
                        ? new HorarioCarnetOlvidado().calcular(resuelta) : new HorarioVisitaNoAnunciada().calcular(resuelta);
                detalles += "; ingreso permitido desde: " + horario.getDesde() + "; hasta: " + horario.getHasta();
            } else {
                // Un rechazo no es una aprobación: su responsable y fecha quedan en la auditoría.
                detalles += "; motivo: " + explicacion;
            }
            resuelta.actualizarMovimiento(nuevoEstado.getId(), null, null, null);

            intento.iniciar();
            if (!repositorios.crearVisitaRepository().actualizar(resuelta)) {
                throw new IllegalStateException("No fue posible guardar la decisión sobre la solicitud.");
            }
            repositorios.crearBitacoraAuditoriaRepository().guardar(new BitacoraAuditoria(
                    funcionario.getId(), ahora, accion, "visitas", solicitudId, detalles));
            return resuelta;
        });
    }

    private Usuario buscarFuncionarioActual(FabricarRepositorios repositorios, Usuario referencia) {
        if (referencia.getEmpresaId() == null) {
            throw new IllegalStateException("El funcionario debe tener una empresa asignada.");
        }
        Usuario funcionario = repositorios.crearUsuarioRepository().buscarPorIdParaActualizar(referencia.getId())
                .orElseThrow(() -> new IllegalStateException("La cuenta del funcionario no está disponible."));
        if (!funcionario.isEstaActivo() || !Objects.equals(funcionario.getEmpresaId(), referencia.getEmpresaId())) {
            throw new IllegalStateException("La cuenta o su empresa cambiaron; vuelve a consultar la solicitud.");
        }
        Rol rol = repositorios.crearRolRepository().buscarPorId(funcionario.getRolId())
                .orElseThrow(() -> new IllegalStateException("El rol del funcionario no existe."));
        if (!"Funcionario de Empresa".equals(rol.getNombreRol())) {
            throw new IllegalArgumentException("La decisión corresponde a un funcionario de empresa.");
        }
        return funcionario;
    }

    private Visita buscarPendientePropia(FabricarRepositorios repositorios, Usuario funcionario, Integer solicitudId) {
        Visita solicitud = repositorios.crearVisitaRepository().buscarPorIdParaActualizar(solicitudId)
                .orElseThrow(() -> new IllegalArgumentException("La solicitud no existe."));
        if (!esSolicitud(solicitud.getTipoIngreso())
                || !funcionario.getId().equals(solicitud.getFuncionarioDestinatarioId())
                || !funcionario.getEmpresaId().equals(solicitud.getEmpresaDestinoId())) {
            throw new IllegalArgumentException("Solo puedes resolver las solicitudes dirigidas a ti y a tu empresa.");
        }
        if (!estadoPendiente(repositorios, solicitud.getTipoIngreso()).getId().equals(solicitud.getEstadoVisitaId())
                || solicitud.getFechaAprobacion() != null || solicitud.getAprobadaPorId() != null
                || solicitud.getFechaEntrada() != null || solicitud.getFechaSalida() != null
                || solicitud.getFechaCierreSistema() != null) {
            throw new IllegalStateException("La solicitud ya fue resuelta o no conserva un estado pendiente válido.");
        }
        return solicitud;
    }

    private Visita copiar(Visita solicitud) {
        return new Visita(solicitud.getId(), solicitud.getPersonaId(), solicitud.getEmpresaDestinoId(),
                solicitud.getFuncionarioDestinatarioId(), solicitud.getTipoIngreso(), solicitud.getFechaProgramada(),
                solicitud.getFechaAprobacion(), solicitud.getFechaEntrada(), solicitud.getFechaSalida(),
                solicitud.getFechaCierreSistema(), solicitud.getEstadoVisitaId(), solicitud.getVehiculoPlaca(),
                solicitud.getAprobadaPorId());
    }

    private void validarDestino(FabricarRepositorios repositorios, Integer empresaId, Integer funcionarioId) {
        // La empresa se protege antes que la cuenta, igual que al desactivarlas.
        Empresa empresa = repositorios.crearEmpresaRepository().buscarPorIdParaActualizar(empresaId)
                .orElseThrow(() -> new IllegalArgumentException("La empresa de destino no existe."));
        politicaEmpresa.validarOperativa(empresa);
        Usuario funcionario = repositorios.crearUsuarioRepository().buscarPorIdParaActualizar(funcionarioId)
                .orElseThrow(() -> new IllegalArgumentException("El funcionario destinatario no existe."));
        Rol rol = repositorios.crearRolRepository().buscarPorId(funcionario.getRolId())
                .orElseThrow(() -> new IllegalStateException("El rol del destinatario no existe."));
        if (!funcionario.isEstaActivo() || !empresaId.equals(funcionario.getEmpresaId())
                || !"Funcionario de Empresa".equals(rol.getNombreRol())) {
            throw new IllegalArgumentException("El destinatario debe ser un funcionario activo de la empresa seleccionada.");
        }
    }

    private EstadoVisita estadoPendiente(FabricarRepositorios repositorios) {
        return estadoPendiente(repositorios, TipoIngreso.INVITADO_NO_ANUNCIADO);
    }

    private EstadoVisita estadoPendiente(FabricarRepositorios repositorios, TipoIngreso tipo) {
        return estado(repositorios, tipo == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO
                ? "Pendiente de Aprobación por Olvido" : "Pendiente de Aprobación");
    }

    private boolean esSolicitud(TipoIngreso tipo) {
        return tipo == TipoIngreso.INVITADO_NO_ANUNCIADO || tipo == TipoIngreso.TRABAJADOR_CON_CARNET_OLVIDADO;
    }

    private EstadoVisita estado(FabricarRepositorios repositorios, String nombre) {
        return repositorios.crearEstadoVisitaRepository().buscarPorNombre(nombre)
                .orElseThrow(() -> new IllegalStateException("El estado " + nombre + " no está configurado."));
    }

    private void validarSolicitud(Integer empresaId, Integer funcionarioId, String documento, String placa) {
        if (empresaId == null || funcionarioId == null) {
            throw new IllegalArgumentException("La empresa y el funcionario destinatario son obligatorios.");
        }
        validarDocumentoYPlaca(documento, placa);
    }

    private void validarDocumentoYPlaca(String documento, String placa) {
        if (documento == null || documento.isBlank() || documento.length() > 20) {
            throw new IllegalArgumentException("El documento es obligatorio y admite hasta 20 caracteres.");
        }
        if (placa != null && placa.length() > 10) {
            throw new IllegalArgumentException("La placa del vehículo admite hasta 10 caracteres.");
        }
    }
}
