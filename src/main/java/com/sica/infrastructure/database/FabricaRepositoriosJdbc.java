package com.sica.infrastructure.database;

import com.sica.infrastructure.persistence.BitacoraAuditoriaRepositoryJdbc;
import com.sica.infrastructure.persistence.EmpresaRepositoryJdbc;
import com.sica.infrastructure.persistence.EstadoAccesoPersonaRepositoryJdbc;
import com.sica.infrastructure.persistence.EstadoVisitaRepositoryJdbc;
import com.sica.infrastructure.persistence.IncidenteRepositoryJdbc;
import com.sica.infrastructure.persistence.PermisoRepositoryJdbc;
import com.sica.infrastructure.persistence.PersonaRepositoryJdbc;
import com.sica.infrastructure.persistence.RolRepositoryJdbc;
import com.sica.infrastructure.persistence.UsuarioRepositoryJdbc;
import com.sica.infrastructure.persistence.VisitaRepositoryJdbc;
import com.sica.model.application.port.FabricarRepositorios;
import com.sica.model.domain.repository.BitacoraAuditoriaRepository;
import com.sica.model.domain.repository.EmpresaRepository;
import com.sica.model.domain.repository.EstadoAccesoPersonaRepository;
import com.sica.model.domain.repository.EstadoVisitaRepository;
import com.sica.model.domain.repository.IncidenteRepository;
import com.sica.model.domain.repository.PermisoRepository;
import com.sica.model.domain.repository.PersonaRepository;
import com.sica.model.domain.repository.RolRepository;
import com.sica.model.domain.repository.UsuarioRepository;
import com.sica.model.domain.repository.VisitaRepository;
import java.sql.Connection;
import java.util.Objects;

public final class FabricaRepositoriosJdbc implements FabricarRepositorios {

    private final EmpresaRepository empresas;
    private final UsuarioRepository usuarios;
    private final PermisoRepository permisos;
    private final RolRepository roles;
    private final BitacoraAuditoriaRepository auditoria;
    private final EstadoAccesoPersonaRepository estadosAcceso;
    private final PersonaRepository personas;
    private final EstadoVisitaRepository estadosVisita;
    private final VisitaRepository visitas;
    private final IncidenteRepository incidentes;

    public FabricaRepositoriosJdbc(Connection conexion) {
        Objects.requireNonNull(conexion, "La conexión de la base de datos es obligatoria.");

        // Los repositorios de esta fábrica utilizan la conexión de una misma transacción.
        this.empresas = new EmpresaRepositoryJdbc(conexion);
        this.usuarios = new UsuarioRepositoryJdbc(conexion);
        this.permisos = new PermisoRepositoryJdbc(conexion);
        this.roles = new RolRepositoryJdbc(conexion);
        this.auditoria = new BitacoraAuditoriaRepositoryJdbc(conexion);
        this.estadosAcceso = new EstadoAccesoPersonaRepositoryJdbc(conexion);
        this.personas = new PersonaRepositoryJdbc(conexion);
        this.estadosVisita = new EstadoVisitaRepositoryJdbc(conexion);
        this.visitas = new VisitaRepositoryJdbc(conexion);
        this.incidentes = new IncidenteRepositoryJdbc(conexion);
    }

    @Override
    public EmpresaRepository crearEmpresaRepository() {
        return empresas;
    }

    @Override
    public UsuarioRepository crearUsuarioRepository() {
        return usuarios;
    }

    @Override
    public PermisoRepository crearPermisoRepository() {
        return permisos;
    }

    @Override
    public RolRepository crearRolRepository() {
        return roles;
    }

    @Override
    public BitacoraAuditoriaRepository crearBitacoraAuditoriaRepository() {
        return auditoria;
    }

    @Override
    public EstadoAccesoPersonaRepository crearEstadoAccesoPersonaRepository() {
        return estadosAcceso;
    }

    @Override
    public PersonaRepository crearPersonaRepository() {
        return personas;
    }

    @Override
    public EstadoVisitaRepository crearEstadoVisitaRepository() {
        return estadosVisita;
    }

    @Override
    public VisitaRepository crearVisitaRepository() {
        return visitas;
    }

    @Override
    public IncidenteRepository crearIncidenteRepository() {
        return incidentes;
    }
}
