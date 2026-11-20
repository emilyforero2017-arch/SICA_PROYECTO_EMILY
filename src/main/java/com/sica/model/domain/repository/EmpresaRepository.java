package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Empresa;
import java.util.List;
import java.util.Optional;

public interface EmpresaRepository {

    // Registra una empresa nueva y devuelve sus datos con el identificador asignado.
    Empresa guardar(Empresa empresa);

    Optional<Empresa> buscarPorId(Integer id);

    // Mantiene protegida la empresa durante la transacción para coordinar operaciones simultáneas.
    Optional<Empresa> buscarPorIdParaActualizar(Integer id);

    // Incluye las empresas retiradas para poder consultar su historial.
    List<Empresa> listar();

    List<Empresa> listarActivas();

    // Actualiza el nombre y el contacto; conserva el estado de activación que tenga la empresa.
    boolean actualizar(Empresa empresa);

    // Devuelve true si pasó de activa a desactivada. No elimina ningún registro.
    boolean desactivar(Integer id);
}
