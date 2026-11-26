package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Usuario;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

public interface UsuarioRepository {

    Usuario guardar(Usuario usuario);

    Optional<Usuario> buscarPorId(Integer id);

    // La auditoría necesita los nombres actuales, incluidas las cuentas inactivas.
    default Map<Integer, String> buscarNombresPorIds(Set<Integer> ids) {
        Map<Integer, String> nombres = new HashMap<>();
        for (Integer id : ids) {
            buscarPorId(id).ifPresent(usuario -> nombres.put(id, usuario.getNombre()));
        }
        return nombres;
    }

    // Protege la cuenta durante una actualización o desactivación simultánea.
    Optional<Usuario> buscarPorIdParaActualizar(Integer id);

    Optional<Usuario> buscarPorEmail(String email);

    default Optional<byte[]> buscarFotoPerfil(Integer id) { return Optional.empty(); }

    List<Usuario> listar();

    // Consulta solo las cuentas activas para la administración del superusuario.
    List<Usuario> listarActivos();

    List<Usuario> listarPorEmpresa(Integer empresaId);

    List<Usuario> listarFuncionariosActivosPorEmpresa(Integer empresaId);

    // Actualiza nombre, correo y, si se adjunta, foto de perfil.
    boolean actualizar(Usuario usuario);

    // Devuelve true si pasó de activa a inactiva. Conserva todos los datos y el historial.
    boolean desactivar(Integer id);
}
