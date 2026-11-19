package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Usuario;
import java.util.List;
import java.util.Optional;

public interface UsuarioRepository {

    Usuario guardar(Usuario usuario);

    Optional<Usuario> buscarPorId(Integer id);

    Optional<Usuario> buscarPorEmail(String email);

    List<Usuario> listar();

    List<Usuario> listarPorEmpresa(Integer empresaId);

    List<Usuario> listarFuncionariosActivosPorEmpresa(Integer empresaId);

    boolean actualizar(Usuario usuario);
}
