package com.sica.integration;

import com.sica.config.ConfiguracionBasedatos;
import com.sica.infrastructure.database.ConexionBaseDatos;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

// Provoca un error concreto de auditoría; las demás consultas llegan al MySQL real.
final class ConexionConFalloAuditoria extends ConexionBaseDatos {

    private final AtomicInteger aperturas = new AtomicInteger();
    private final AtomicInteger fallosPendientes = new AtomicInteger();
    private final AtomicInteger auditoriasPrevias = new AtomicInteger();
    private final List<Integer> auditorias = new CopyOnWriteArrayList<>();

    ConexionConFalloAuditoria(ConfiguracionBasedatos configuracion) { super(configuracion); }

    void fallarProximasAuditorias(int cantidad) {
        auditorias.clear();
        auditoriasPrevias.set(0);
        fallosPendientes.set(cantidad);
    }

    // Permite fallar después de que una operación ya haya guardado otra auditoría.
    void fallarAuditoriaNumero(int numero) {
        if (numero < 1) throw new IllegalArgumentException("El número de auditoría debe ser positivo.");
        fallarProximasAuditorias(1);
        auditoriasPrevias.set(numero - 1);
    }

    List<Integer> conexionesDeAuditoria() { return List.copyOf(auditorias); }

    @Override
    public Connection abrirConexion() throws SQLException {
        Connection real = super.abrirConexion();
        int identificador = aperturas.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, metodo, argumentos) -> {
                    if ("prepareStatement".equals(metodo.getName()) && argumentos != null
                            && argumentos[0] instanceof String sql
                            && sql.stripLeading().toUpperCase(Locale.ROOT)
                                    .startsWith("INSERT INTO BITACORA_AUDITORIA")) {
                        auditorias.add(identificador);
                        if (auditoriasPrevias.getAndUpdate(n -> Math.max(0, n - 1)) == 0
                                && fallosPendientes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                            throw new SQLException("Fallo de auditoría provocado para esta prueba.", "HY000");
                        }
                    }
                    try {
                        return metodo.invoke(real, argumentos);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }
}
