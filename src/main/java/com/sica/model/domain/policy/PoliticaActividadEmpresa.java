package com.sica.model.domain.policy;

import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Persona;
import com.sica.model.domain.entity.TipoPersona;
import com.sica.model.domain.entity.Visita;
import java.util.Objects;

// Revisa si una empresa permite nuevas operaciones y entradas. Su historial siempre se conserva.
public class PoliticaActividadEmpresa {

    public void validarOperativa(Empresa empresa) {
        Objects.requireNonNull(empresa, "La empresa es obligatoria.");

        if (!empresa.isEstaActiva()) {
            throw new IllegalStateException(
                    "La empresa está desactivada y no permite nuevas operaciones.");
        }
    }

    // Revisa la empresa y la vinculación laboral; las demás condiciones de entrada se revisan aparte.
    public void validarIngresoLaboral(Persona trabajador, Empresa empresa) {
        Objects.requireNonNull(trabajador, "El trabajador es obligatorio.");
        Objects.requireNonNull(empresa, "La empresa es obligatoria.");

        if (trabajador.getTipoPersona() != TipoPersona.TRABAJADOR
                || !Objects.equals(trabajador.getEmpresaId(), empresa.getId())) {
            throw new IllegalArgumentException(
                    "El trabajador debe pertenecer a la empresa consultada.");
        }

        validarOperativa(empresa);

        if (!Boolean.TRUE.equals(trabajador.getVinculacionLaboralActiva())) {
            throw new IllegalStateException(
                    "El trabajador no tiene una vinculación laboral activa.");
        }
    }

    // Una autorización previa no permite entrar si su empresa de destino ya fue desactivada.
    // Esta revisión es para nuevas entradas; no se exige una empresa activa para registrar salidas.
    public void validarDestinoVisita(Visita visita, Empresa empresa) {
        Objects.requireNonNull(visita, "La visita es obligatoria.");
        Objects.requireNonNull(empresa, "La empresa es obligatoria.");

        if (!Objects.equals(visita.getEmpresaDestinoId(), empresa.getId())) {
            throw new IllegalArgumentException(
                    "La empresa consultada debe ser el destino de la visita.");
        }

        validarOperativa(empresa);
    }
}
