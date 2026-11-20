package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Usuario;
import java.util.List;
import java.util.Optional;

public interface UsuarioRepository {

    Usuario guardar(Usuario usuario);

    Optional<Usuario> buscarPorId(Integer id);

    // Protege la cuenta durante una actualización o desactivación simultánea.
    Optional<Usuario> buscarPorIdParaActualizar(Integer id);

    Optional<Usuario> buscarPorEmail(String email);

    List<Usuario> listar();

    // Consulta solo las cuentas activas para la administración del superusuario.
    List<Usuario> listarActivos();

    List<Usuario> listarPorEmpresa(Integer empresaId);

    List<Usuario> listarFuncionariosActivosPorEmpresa(Integer empresaId);

    // Actualiza nombre y correo; conserva contraseña, rol, empresa y estado de la cuenta.
    boolean actualizar(Usuario usuario);

    // Devuelve true si pasó de activa a inactiva. Conserva todos los datos y el historial.
    boolean desactivar(Integer id);
}
