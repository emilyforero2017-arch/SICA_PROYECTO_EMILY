package com.sica.model.application.port;

import com.sica.model.domain.repository.BitacoraAuditoriaRepository;
import com.sica.model.domain.repository.EmpresaRepository;
import com.sica.model.domain.repository.EstadoAccesoPersonaRepository;
import com.sica.model.domain.repository.EstadoVisitaRepository;
import com.sica.model.domain.repository.PermisoRepository;
import com.sica.model.domain.repository.PersonaRepository;
import com.sica.model.domain.repository.UsuarioRepository;
import com.sica.model.domain.repository.RolRepository;
import com.sica.model.domain.repository.VisitaRepository;
import com.sica.model.domain.repository.IncidenteRepository;

public interface FabricarRepositorios {
    UsuarioRepository crearUsuarioRepository();

    PermisoRepository crearPermisoRepository();

    EmpresaRepository crearEmpresaRepository();

    PersonaRepository crearPersonaRepository();

    BitacoraAuditoriaRepository crearBitacoraAuditoriaRepository();

    // Permite consultar los roles disponibles al gestionar una cuenta.
    RolRepository crearRolRepository();

    // Permite consultar los estados de acceso al registrar una persona.
    EstadoAccesoPersonaRepository crearEstadoAccesoPersonaRepository();

    VisitaRepository crearVisitaRepository();

    EstadoVisitaRepository crearEstadoVisitaRepository();

    // Ofrece el repositorio para guardar y consultar incidentes relacionados con una visita.
    IncidenteRepository crearIncidenteRepository();
}
