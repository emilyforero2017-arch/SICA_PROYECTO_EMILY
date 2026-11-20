package com.sica.model.application.usecase;

import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.TipoIngreso;
import com.sica.model.domain.entity.TipoPersona;
import com.sica.model.domain.entity.Visita;
import com.sica.model.domain.policy.PoliticaActividadEmpresa;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

// Registra el ingreso habitual de un trabajador que presenta su carnet en portería.
public class ControlAccesoTrabajadorCase {

    private final EjecutorOperaciones ejecutor;
    private final Clock reloj;
    private final PoliticaActividadEmpresa politicaEmpresa = new PoliticaActividadEmpresa();

    public ControlAccesoTrabajadorCase(EjecutorOperaciones ejecutor) {
        this(ejecutor, Clock.system(ZoneId.of("America/Bogota")));
    }

    public ControlAccesoTrabajadorCase(EjecutorOperaciones ejecutor, Clock reloj) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
        this.reloj = Objects.requireNonNull(reloj);
    }

    public Visita registrarEntrada(
            Integer guardaId, String documento, boolean carnetPresentado, String vehiculoPlaca) {
        if (documento == null || documento.isBlank() || documento.length() > 20) {
            throw new IllegalArgumentException("El documento es obligatorio y admite hasta 20 caracteres.");
        }
        // Este dato representa la comprobación del carnet físico que realiza el guarda.
        if (!carnetPresentado) {
            throw new IllegalArgumentException(
                    "El ingreso habitual requiere el carnet. Si lo olvidó, debe tramitar la solicitud correspondiente.");
        }
        if (vehiculoPlaca != null && vehiculoPlaca.length() > 10) {
            throw new IllegalArgumentException("La placa del vehículo admite hasta 10 caracteres.");
        }

        IntentoGuardado intento = new IntentoGuardado("REGISTRO_ENTRADA_FALLIDO", "visitas", null,
                "Ingreso habitual con carnet; documento: " + documento);
        return ejecutor.ejecutarGuardado(guardaId, "registrar_entrada", intento, (repositorios, guarda) -> {
            Persona referencia = repositorios.crearPersonaRepository().buscarPorDocumento(documento)
                    .orElseThrow(() -> new IllegalArgumentException("La persona no está registrada."));
            if (referencia.getTipoPersona() != TipoPersona.TRABAJADOR) {
                throw new IllegalArgumentException("El ingreso habitual corresponde a un trabajador registrado.");
            }

            // Protegemos primero la empresa y después al trabajador mientras comprobamos y guardamos.
            Empresa empresa = repositorios.crearEmpresaRepository()
                    .buscarPorIdParaActualizar(referencia.getEmpresaId())
                    .orElseThrow(() -> new IllegalStateException("La empresa del trabajador no existe."));
            Persona trabajador = repositorios.crearPersonaRepository().buscarPorDocumentoParaActualizar(documento)
                    .orElseThrow(() -> new IllegalArgumentException("El trabajador ya no está registrado con ese documento."));
            if (!referencia.getId().equals(trabajador.getId())) {
                throw new IllegalStateException("Los datos del trabajador cambiaron; vuelve a consultarlo.");
            }
            politicaEmpresa.validarIngresoLaboral(trabajador, empresa);
            String estadoAcceso = repositorios.crearEstadoAccesoPersonaRepository()
                    .buscarPorId(trabajador.getEstadoAccesoId())
                    .orElseThrow(() -> new IllegalStateException("El estado de acceso no está configurado."))
                    .getNombreEstado();
            if (!"Activo".equals(estadoAcceso)) {
                throw new IllegalStateException(
                        "El trabajador tiene una restricción de ingreso; debe revisarla el supervisor de seguridad.");
            }

            Integer dentroId = estadoId(repositorios, "Dentro");
            Optional<Visita> anterior = repositorios.crearVisitaRepository()
                    .buscarIngresoAbiertoPorPersonaParaActualizar(trabajador.getId());
            Integer cierreId = anterior.isPresent()
                    ? estadoId(repositorios, "Cerrada por Sistema - Salida no registrada") : null;
            LocalDateTime ahora = LocalDateTime.now(reloj);
            if (anterior.isPresent() && anterior.get().getFechaEntrada().isAfter(ahora)) {
                throw new IllegalStateException("El ingreso anterior tiene una fecha posterior al ingreso actual.");
            }

            // El ingreso laboral usa la empresa del trabajador y no necesita cita ni aprobador.
            Visita entrada = new Visita(trabajador.getId(), empresa.getId(), null,
                    TipoIngreso.TRABAJADOR_CON_CARNET, null, null, ahora, null, null,
                    dentroId, vehiculoPlaca, null);
            intento.iniciar();
            if (anterior.isPresent()) {
                Visita abierta = anterior.get();
                // Sabemos que volvió a llegar; la hora real de la salida anterior sigue siendo desconocida.
                abierta.actualizarMovimiento(cierreId, abierta.getFechaEntrada(), null, ahora);
                if (!repositorios.crearVisitaRepository().actualizar(abierta)) {
                    throw new IllegalStateException("No fue posible regularizar el ingreso anterior.");
                }
                auditar(repositorios, guarda.getId(), ahora, "CIERRE_SALIDA_NO_REGISTRADA", abierta.getId(),
                        "Se regulariza al registrar un nuevo ingreso con carnet. La hora real de salida se desconoce.");
            }

            Visita guardada = repositorios.crearVisitaRepository().guardar(entrada);
            if (guardada == null || guardada.getId() == null) {
                throw new IllegalStateException("No fue posible obtener el registro del nuevo ingreso.");
            }
            auditar(repositorios, guarda.getId(), ahora, "REGISTRO_ENTRADA", guardada.getId(),
                    "Ingreso habitual con carnet; persona: " + trabajador.getId()
                    + "; empresa: " + empresa.getId() + "; entrada: " + ahora);
            // El cierre anterior, la nueva entrada y sus auditorías se confirman juntos.
            return guardada;
        });
    }

    private Integer estadoId(FabricarRepositorios repositorios, String nombre) {
        return repositorios.crearEstadoVisitaRepository().buscarPorNombre(nombre)
                .orElseThrow(() -> new IllegalStateException("No está configurado el estado: " + nombre)).getId();
    }

    private void auditar(FabricarRepositorios repositorios, Integer guardaId, LocalDateTime fecha,
            String accion, Integer visitaId, String detalles) {
        repositorios.crearBitacoraAuditoriaRepository().guardar(
                new BitacoraAuditoria(guardaId, fecha, accion, "visitas", visitaId, detalles));
    }
}
