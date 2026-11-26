package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Persona;
import java.util.List;
import java.util.Optional;

public interface PersonaRepository {

    Persona guardar(Persona persona);

    Optional<Persona> buscarPorId(Integer id);

    // Mantiene protegido el registro mientras se comprueban y guardan sus cambios.
    Optional<Persona> buscarPorIdParaActualizar(Integer id);

    Optional<Persona> buscarPorDocumento(String documentoIdentidad);

    default Optional<byte[]> buscarFotoArchivo(Integer id) { return Optional.empty(); }

    // Protege a la persona durante el ingreso para evitar dos entradas simultáneas.
    Optional<Persona> buscarPorDocumentoParaActualizar(String documentoIdentidad);

    List<Persona> listar();

    List<Persona> listarTrabajadoresPorEmpresa(Integer empresaId);

    List<Persona> listarPorEstadoAcceso(Integer estadoAccesoId);

    boolean actualizar(Persona persona);
}
