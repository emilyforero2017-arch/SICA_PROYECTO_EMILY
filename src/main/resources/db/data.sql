-- data.sql | SICA | MySQL 8.4.11
-- Ejecutar UNA VEZ después de schema.sql, sobre tablas vacías.
-- Todos los nombres, documentos y movimientos son de demostración.

USE sica;

SET NAMES utf8mb4;
SET time_zone = '-05:00';

START TRANSACTION;


INSERT INTO roles (id, nombre_rol) VALUES
(1, 'Superusuario'),
(2, 'Supervisor de Seguridad'),
(3, 'Guarda de Seguridad'),
(4, 'Funcionario de Empresa');


-- El estado Activo tiene id 1.
-- Es el valor inicial de personas.estado_acceso_id.

INSERT INTO persona_estados_acceso (id, nombre_estado) VALUES
(1, 'Activo'),
(2, 'Con Prohibición de Ingreso');


INSERT INTO visita_estados (id, nombre_estado) VALUES
(1, 'Pendiente de Aprobación'),
(2, 'Pendiente de Aprobación por Olvido'),
(3, 'Aprobado'),
(4, 'Rechazado'),
(5, 'Dentro'),
(6, 'Fuera'),
(7, 'Expirado'),
(8, 'Cancelada'),
(9, 'Cerrada por Sistema - Salida no registrada');


INSERT INTO permisos (nombre_permiso, descripcion) VALUES
('consultar_persona_porteria',
 'Consultar a la persona atendida en portería.'),

('consultar_visita_porteria',
 'Consultar autorizaciones y solicitudes de la persona atendida.'),

('registrar_invitado',
 'Registrar datos de un invitado para tramitar su visita.'),

('solicitar_visita_no_anunciada',
 'Enviar una solicitud al funcionario que recibirá al invitado.'),

('solicitar_ingreso_carnet_olvidado',
 'Enviar al funcionario indicado la solicitud del trabajador sin carnet.'),

('registrar_entrada',
 'Registrar una entrada que cumpla las condiciones de acceso.'),

('registrar_salida',
 'Registrar la salida real de una visita abierta.'),

('consultar_trabajadores_empresa',
 'Consultar trabajadores de la propia empresa para gestionarlos.'),

('registrar_trabajador',
 'Registrar un trabajador de la propia empresa.'),

('actualizar_trabajador',
 'Actualizar los datos de un trabajador de la propia empresa.'),

('dar_baja_trabajador',
 'Finalizar la vinculación laboral de un trabajador de la propia empresa.'),

('preregistrar_visita',
 'Crear una visita aprobada que el mismo funcionario atenderá.'),

('reprogramar_visita',
 'Cambiar la programación de una visita propia aprobada y sin entrada.'),

('cancelar_visita',
 'Cancelar una visita propia antes de su entrada, indicando el motivo.'),

('consultar_visitas_asignadas',
 'Consultar visitas propias y solicitudes dirigidas al funcionario.'),

('aprobar_solicitud_ingreso',
 'Aprobar solicitudes no anunciadas o por olvido dirigidas al funcionario.'),

('rechazar_solicitud_ingreso',
 'Rechazar solicitudes no anunciadas o por olvido dirigidas al funcionario.'),

('consultar_auditoria_propia',
 'Consultar acciones propias y actividad de visitas y solicitudes asignadas.'),

('consultar_personas_seguridad',
 'Consultar personas del conjunto para revisar situaciones de seguridad.'),

('consultar_visitas_seguridad',
 'Consultar visitas del conjunto para revisar situaciones de seguridad.'),

('registrar_incidente',
 'Registrar un incidente observado o comunicado al supervisor.'),

('consultar_historial_incidentes',
 'Consultar el historial general de incidentes.'),

('imponer_prohibicion_ingreso',
 'Imponer una prohibición de ingreso indicando el motivo.'),

('retirar_prohibicion_ingreso',
 'Retirar una prohibición de ingreso indicando el motivo.'),

('consultar_ingresos_abiertos',
 'Consultar el reporte general de entradas sin salida registrada.'),

('consultar_historial_accesos',
 'Consultar el historial general de entradas y salidas.'),

('consultar_auditoria_general',
 'Consultar la bitácora general de auditoría.'),

('consultar_empresas',
 'Consultar las empresas para administrarlas.'),

('registrar_empresa',
 'Registrar una empresa de Zona Acme.'),

('actualizar_empresa',
 'Actualizar los datos de una empresa.'),

('desactivar_empresa',
 'Retirar una empresa del funcionamiento conservando sus datos e historial.'),

('consultar_usuarios',
 'Consultar las cuentas de usuario para administrarlas.'),

('crear_usuario',
 'Crear una cuenta con rol y empresa cuando corresponda.'),

('actualizar_usuario',
 'Actualizar los datos de una cuenta de usuario.'),

('desactivar_usuario',
 'Deshabilitar una cuenta conservando su historial.'),

('consultar_roles_permisos',
 'Consultar los roles y permisos para administrarlos.'),

('asignar_permiso_rol',
 'Asignar un permiso existente a un rol.'),

('retirar_permiso_rol',
 'Retirar la asignación de un permiso a un rol.');


-- Permisos del guarda.

INSERT INTO rol_permisos (rol_id, permiso_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permisos p
WHERE r.nombre_rol = 'Guarda de Seguridad'
  AND p.nombre_permiso IN (
      'consultar_persona_porteria',
      'consultar_visita_porteria',
      'registrar_invitado',
      'solicitar_visita_no_anunciada',
      'solicitar_ingreso_carnet_olvidado',
      'registrar_entrada',
      'registrar_salida'
  );


-- Permisos del funcionario.

INSERT INTO rol_permisos (rol_id, permiso_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permisos p
WHERE r.nombre_rol = 'Funcionario de Empresa'
  AND p.nombre_permiso IN (
      'registrar_invitado',
      'consultar_trabajadores_empresa',
      'registrar_trabajador',
      'actualizar_trabajador',
      'dar_baja_trabajador',
      'preregistrar_visita',
      'reprogramar_visita',
      'cancelar_visita',
      'consultar_visitas_asignadas',
      'aprobar_solicitud_ingreso',
      'rechazar_solicitud_ingreso',
      'consultar_auditoria_propia'
  );


-- Permisos del supervisor.

INSERT INTO rol_permisos (rol_id, permiso_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permisos p
WHERE r.nombre_rol = 'Supervisor de Seguridad'
  AND p.nombre_permiso IN (
      'consultar_personas_seguridad',
      'consultar_visitas_seguridad',
      'registrar_incidente',
      'consultar_historial_incidentes',
      'imponer_prohibicion_ingreso',
      'retirar_prohibicion_ingreso',
      'consultar_ingresos_abiertos',
      'consultar_historial_accesos',
      'consultar_auditoria_general'
  );


-- Permisos del superusuario.

INSERT INTO rol_permisos (rol_id, permiso_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permisos p
WHERE r.nombre_rol = 'Superusuario'
  AND p.nombre_permiso IN (
      'consultar_auditoria_general',
      'consultar_empresas',
      'registrar_empresa',
      'actualizar_empresa',
      'desactivar_empresa',
      'consultar_usuarios',
      'crear_usuario',
      'actualizar_usuario',
      'desactivar_usuario',
      'consultar_roles_permisos',
      'asignar_permiso_rol',
      'retirar_permiso_rol'
  );


INSERT INTO empresas (id, nombre, contacto_principal, esta_activa) VALUES
(1, 'Empresa Sol', 'Ana Gómez', TRUE),
(2, 'Empresa Luna', 'Marta Rojas', TRUE);


-- Contraseña de las cinco cuentas de demostración:
-- SicaDemo2026!
--
-- Formato:
-- algoritmo:iteraciones:sal_hex:resultado_hex
--
-- PBKDF2WithHmacSHA256, 600000 iteraciones.
-- Sal aleatoria de 16 bytes y resultado de 32 bytes.

INSERT INTO usuarios
(
    id,
    nombre,
    email,
    password_hash,
    rol_id,
    empresa_id,
    esta_activo
)
VALUES
(
    1,
    'Administrador SICA',
    'admin@sica.local',
    'PBKDF2WithHmacSHA256:600000:9ee1e6bf5b160403886d07d68dae2a18:9a318f10e829bc37b9854325da60538f4bccb1233a447d489e1ffb5d80066ae3',
    1,
    NULL,
    TRUE
),
(
    2,
    'Andrés Pérez',
    'supervisor@sica.local',
    'PBKDF2WithHmacSHA256:600000:1379aaade7569c4ca0937c86e93c872f:c6a8ec4ac05fe9cbdfacbcced983ed4da957fc084ec559151c80520cd36e2b55',
    2,
    NULL,
    TRUE
),
(
    3,
    'Carlos Ruiz',
    'guarda@sica.local',
    'PBKDF2WithHmacSHA256:600000:33df8c1de0269a216bab5793ee445b54:4606ab757219ae06ea8d8d6a1c232e4ed2c5fa4cc0cd779a025db46cea19f835',
    3,
    NULL,
    TRUE
),
(
    4,
    'Ana Gómez',
    'ana@sol.sica.local',
    'PBKDF2WithHmacSHA256:600000:0e27fe5be4a96afacfc878b771958d57:8e7b678306ca05ae8f44d702e419a8f2c862904858e58de89f65263ce6e22a9b',
    4,
    1,
    TRUE
),
(
    5,
    'Marta Rojas',
    'marta@luna.sica.local',
    'PBKDF2WithHmacSHA256:600000:1183d81e22150f0e81a24a64947351ee:0dfdd9d8e1dc637a67650bc1fafd41328643fe6c78cd1581110cd06ef86ec3f4',
    4,
    2,
    TRUE
);


-- Fotografías de demostración.

INSERT INTO personas
(
    id,
    nombre,
    documento_identidad,
    empresa_id,
    tipo_persona,
    estado_acceso_id,
    url_foto,
    vinculacion_laboral_activa
)
VALUES
(
    1, 'Pedro Torres', '100000001', 1, 'Trabajador', 1,
    'https://i.pravatar.cc/200?img=12', TRUE
),
(
    2, 'Laura Méndez', '100000002', NULL, 'Invitado', 1,
    'https://i.pravatar.cc/200?img=47', NULL
),
(
    3, 'Sofía Díaz', '100000003', NULL, 'Invitado', 1,
    'https://i.pravatar.cc/200?img=44', NULL
),
(
    4, 'Jorge López', '100000004', 2, 'Trabajador', 1,
    'https://i.pravatar.cc/200?img=13', TRUE
),
(
    5, 'Diana Castro', '100000005', 1, 'Trabajador', 1,
    'https://i.pravatar.cc/200?img=49', FALSE
),
(
    6, 'Mario Suárez', '100000006', NULL, 'Invitado', 2,
    'https://i.pravatar.cc/200?img=14', NULL
);


-- Referencia temporal común para los ejemplos.

SET @ahora = NOW();


INSERT INTO visitas
(
    id,
    persona_id,
    empresa_destino_id,
    funcionario_destinatario_id,
    tipo_ingreso,
    fecha_programada,
    fecha_aprobacion,
    fecha_entrada,
    fecha_salida,
    fecha_cierre_sistema,
    estado_visita_id,
    vehiculo_placa,
    aprobada_por_id
)
VALUES

-- Laura: visita futura preregistrada por Ana.
(
    1, 2, 1, 4, 'Invitado preregistrado',
    DATE_ADD(@ahora, INTERVAL 2 HOUR),
    @ahora,
    NULL,
    NULL,
    NULL,
    3, 'ABC123', 4
),

-- Sofía: solicitud pendiente dirigida a Marta.
(
    2, 3, 2, 5, 'Invitado no anunciado',
    NULL, NULL, NULL, NULL, NULL,
    1, NULL, NULL
),

-- Pedro: solicitud pendiente por carnet olvidado.
(
    3, 1, 1, 4, 'Trabajador con carnet olvidado',
    NULL, NULL, NULL, NULL, NULL,
    2, NULL, NULL
),

-- Jorge: entrada habitual registrada hace una hora.
(
    4, 4, 2, NULL, 'Trabajador con carnet',
    NULL,
    NULL,
    DATE_SUB(@ahora, INTERVAL 1 HOUR),
    NULL,
    NULL,
    5, NULL, NULL
),

-- Laura: visita anterior a Empresa Luna con salida real.
(
    5, 2, 2, 5, 'Invitado preregistrado',
    DATE_SUB(@ahora, INTERVAL 1 DAY),
    DATE_SUB(@ahora, INTERVAL 2 DAY),
    DATE_SUB(@ahora, INTERVAL 1 DAY),
    DATE_ADD(
        DATE_SUB(@ahora, INTERVAL 1 DAY),
        INTERVAL 1 HOUR
    ),
    NULL,
    6, NULL, 5
),

-- Pedro: visita anterior cerrada a las 24 horas.
-- La hora real de salida permanece desconocida.
(
    6, 1, 1, NULL, 'Trabajador con carnet',
    NULL,
    NULL,
    DATE_SUB(@ahora, INTERVAL 30 HOUR),
    NULL,
    DATE_SUB(@ahora, INTERVAL 6 HOUR),
    9, NULL, NULL
),

-- Mario: solicitud rechazada por Ana.
(
    7, 6, 1, 4, 'Invitado no anunciado',
    NULL, NULL, NULL, NULL, NULL,
    4, NULL, NULL
);


INSERT INTO incidentes
(
    id,
    visita_id,
    registrado_por_id,
    fecha_incidente,
    descripcion
)
VALUES
(
    1,
    5,
    2,
    DATE_ADD(
        DATE_SUB(@ahora, INTERVAL 1 DAY),
        INTERVAL 30 MINUTE
    ),
    'Ejemplo: la invitada comunicó la pérdida de un objeto durante su visita.'
);


-- Auditoría simulada para acompañar los datos de demostración.
-- Las operaciones reales serán auditadas desde los servicios Java.

INSERT INTO bitacora_auditoria
(
    usuario_id,
    fecha_hora,
    accion_realizada,
    tabla_afectada,
    registro_id_afectado,
    detalles
)
VALUES
(
    NULL,
    @ahora,
    'CARGA_DATOS_DEMOSTRACION',
    NULL,
    NULL,
    'Carga inicial de información ficticia mediante data.sql.'
),
(
    4,
    @ahora,
    'PREREGISTRO_VISITA',
    'visitas',
    1,
    'Simulación: Ana preregistró a Laura para Empresa Sol.'
),
(
    3,
    DATE_SUB(@ahora, INTERVAL 1 HOUR),
    'REGISTRO_ENTRADA',
    'visitas',
    4,
    'Simulación: Carlos registró el ingreso habitual de Jorge.'
),
(
    NULL,
    DATE_SUB(@ahora, INTERVAL 6 HOUR),
    'CIERRE_AUTOMATICO_VISITA',
    'visitas',
    6,
    'Simulación: cierre al completar 24 horas sin salida registrada; hora real desconocida.'
),
(
    4,
    @ahora,
    'BAJA_TRABAJADOR',
    'personas',
    5,
    'Simulación: finalizó la vinculación laboral de Diana con Empresa Sol.'
),
(
    2,
    @ahora,
    'PROHIBICION_INGRESO',
    'personas',
    6,
    'Simulación: restricción de Mario por un motivo de seguridad comunicado al supervisor.'
),
(
    2,
    @ahora,
    'REGISTRO_INCIDENTE',
    'incidentes',
    1,
    'Simulación: el supervisor registró hoy una pérdida comunicada sobre la visita anterior.'
),
(
    4,
    @ahora,
    'RECHAZO_SOLICITUD',
    'visitas',
    7,
    'Simulación: Ana rechazó la solicitud; la persona conserva una prohibición de ingreso.'
),
(
    NULL,
    @ahora,
    'LOGIN_FALLIDO',
    NULL,
    NULL,
    'Simulación: intento con el correo inexistente desconocido@sica.local.'
);


COMMIT;
