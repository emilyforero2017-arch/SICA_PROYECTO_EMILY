package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Persona;
import java.util.List;
import java.util.Optional;

public interface PersonaRepository {

    Persona guardar(Persona persona);

    Optional<Persona> buscarPorId(Integer id);

    Optional<Persona> buscarPorDocumento(String documentoIdentidad);

    List<Persona> listar();

    List<Persona> listarTrabajadoresPorEmpresa(Integer empresaId);

    List<Persona> listarPorEstadoAcceso(Integer estadoAccesoId);

    boolean actualizar(Persona persona);
}
