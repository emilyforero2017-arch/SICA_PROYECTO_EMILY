package com.sica.model.application.usecase;

import com.sica.model.domain.entity.BitacoraAuditoria;
import com.sica.model.domain.entity.EstadoAccesoPersona;
import com.sica.model.domain.entity.Persona;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Devuelve juntos los datos de la persona, su estado y las decisiones de seguridad registradas.
public final class ConsultaRestriccionAcceso {

    private final Persona persona;
    private final EstadoAccesoPersona estadoAcceso;
    private final List<BitacoraAuditoria> historial;
    private final Map<Integer, String> nombresResponsables;

    public ConsultaRestriccionAcceso(
            Persona persona, EstadoAccesoPersona estadoAcceso,
            List<BitacoraAuditoria> historial, Map<Integer, String> nombresResponsables) {
        this.persona = Objects.requireNonNull(persona);
        this.estadoAcceso = Objects.requireNonNull(estadoAcceso);
        this.historial = List.copyOf(historial);
        this.nombresResponsables = Map.copyOf(nombresResponsables);
    }

    public Persona getPersona() { return persona; }
    public EstadoAccesoPersona getEstadoAcceso() { return estadoAcceso; }
    public List<BitacoraAuditoria> getHistorial() { return historial; }

    // Para mostrar quién actuó solo necesitamos su nombre, no los datos de su cuenta.
    public String getNombreResponsable(Integer usuarioId) {
        return usuarioId == null ? "Sistema" : nombresResponsables.getOrDefault(usuarioId, "Cuenta no disponible");
    }
}
