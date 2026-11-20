package com.sica.model.application.usecase;

import com.sica.model.domain.entity.Empresa;
import com.sica.model.domain.entity.Rol;
import com.sica.model.domain.entity.Usuario;
import com.sica.model.domain.policy.PoliticaActividadEmpresa;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

// Permite elegir una empresa y un funcionario para dirigir una solicitud desde portería.
public class DestinoVisitaCase {

    private final EjecutorOperaciones ejecutor;
    private final PoliticaActividadEmpresa politicaEmpresa = new PoliticaActividadEmpresa();

    public DestinoVisitaCase(EjecutorOperaciones ejecutor) {
        this.ejecutor = Objects.requireNonNull(ejecutor);
    }

    public List<Empresa> listarEmpresas(Integer guardaId) {
        return ejecutor.ejecutar(guardaId, "consultar_visita_porteria", (repositorios, guarda) ->
                repositorios.crearEmpresaRepository().listarActivas().stream()
                        .filter(Empresa::isEstaActiva)
                        .sorted(Comparator.comparing(Empresa::getNombre, String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(Empresa::getId))
                        .toList());
    }

    public List<FuncionarioDisponible> listarFuncionarios(Integer guardaId, Integer empresaId) {
        if (empresaId == null) {
            throw new IllegalArgumentException("La empresa a consultar es obligatoria.");
        }
        return ejecutor.ejecutar(guardaId, "consultar_visita_porteria", (repositorios, guarda) -> {
            // Se vuelve a comprobar la empresa por si fue retirada después de mostrar el primer listado.
            Empresa empresa = repositorios.crearEmpresaRepository().buscarPorId(empresaId)
                    .orElseThrow(() -> new IllegalArgumentException("La empresa no existe."));
            politicaEmpresa.validarOperativa(empresa);
            Rol funcionario = repositorios.crearRolRepository().buscarPorNombre("Funcionario de Empresa")
                    .orElseThrow(() -> new IllegalStateException("El rol de funcionario no está configurado."));

            return repositorios.crearUsuarioRepository().listarFuncionariosActivosPorEmpresa(empresaId).stream()
                    .filter(usuario -> usuario.isEstaActivo()
                            && empresaId.equals(usuario.getEmpresaId())
                            && funcionario.getId().equals(usuario.getRolId()))
                    .sorted(Comparator.comparing(Usuario::getNombre, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(Usuario::getId))
                    // La selección devuelve nombres e identificadores, sin contraseñas ni hashes.
                    .map(usuario -> new FuncionarioDisponible(usuario.getId(), usuario.getNombre(), usuario.getEmpresaId()))
                    .toList();
        });
    }
}
