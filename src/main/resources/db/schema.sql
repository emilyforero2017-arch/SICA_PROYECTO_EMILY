-- schema.sql | SICA | MySQL 8.4.11
-- Ejecutar sobre una base nueva, antes de data.sql.

CREATE DATABASE IF NOT EXISTS sica
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE sica;

SET NAMES utf8mb4;
SET time_zone = '-05:00';


CREATE TABLE roles (
    id INT AUTO_INCREMENT PRIMARY KEY,
    nombre_rol VARCHAR(50) NOT NULL UNIQUE,

    CONSTRAINT ck_roles_nombre
        CHECK (CHAR_LENGTH(TRIM(nombre_rol)) > 0)
) ENGINE=InnoDB;


CREATE TABLE permisos (
    id INT AUTO_INCREMENT PRIMARY KEY,
    nombre_permiso VARCHAR(100) NOT NULL UNIQUE,
    descripcion TEXT,

    CONSTRAINT ck_permisos_nombre
        CHECK (CHAR_LENGTH(TRIM(nombre_permiso)) > 0)
) ENGINE=InnoDB;


CREATE TABLE rol_permisos (
    rol_id INT NOT NULL,
    permiso_id INT NOT NULL,

    PRIMARY KEY (rol_id, permiso_id),

    CONSTRAINT fk_rp_rol
        FOREIGN KEY (rol_id)
        REFERENCES roles(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_rp_permiso
        FOREIGN KEY (permiso_id)
        REFERENCES permisos(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT
) ENGINE=InnoDB;


CREATE TABLE empresas (
    id INT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(100) NOT NULL,
    contacto_principal VARCHAR(100),
    logo MEDIUMBLOB,
    esta_activa BOOLEAN NOT NULL DEFAULT TRUE,

    CONSTRAINT ck_empresas_nombre
        CHECK (CHAR_LENGTH(TRIM(nombre)) > 0),

    CONSTRAINT ck_empresas_activa
        CHECK (esta_activa IN (0, 1))
) ENGINE=InnoDB;


CREATE TABLE usuarios (
    id INT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    foto_perfil MEDIUMBLOB,
    rol_id INT NOT NULL,
    empresa_id INT,
    esta_activo BOOLEAN NOT NULL DEFAULT TRUE,

    CONSTRAINT fk_usuarios_rol
        FOREIGN KEY (rol_id)
        REFERENCES roles(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_usuarios_empresa
        FOREIGN KEY (empresa_id)
        REFERENCES empresas(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT ck_usuarios_nombre
        CHECK (CHAR_LENGTH(TRIM(nombre)) > 0),

    CONSTRAINT ck_usuarios_email
        CHECK (CHAR_LENGTH(TRIM(email)) > 0),

    CONSTRAINT ck_usuarios_hash
        CHECK (CHAR_LENGTH(TRIM(password_hash)) > 0),

    CONSTRAINT ck_usuarios_activo
        CHECK (esta_activo IN (0, 1))
) ENGINE=InnoDB;


CREATE TABLE persona_estados_acceso (
    id INT AUTO_INCREMENT PRIMARY KEY,
    nombre_estado VARCHAR(50) NOT NULL UNIQUE,

    CONSTRAINT ck_pea_nombre
        CHECK (CHAR_LENGTH(TRIM(nombre_estado)) > 0)
) ENGINE=InnoDB;


CREATE TABLE visita_estados (
    id INT AUTO_INCREMENT PRIMARY KEY,
    nombre_estado VARCHAR(50) NOT NULL UNIQUE,

    CONSTRAINT ck_ve_nombre
        CHECK (CHAR_LENGTH(TRIM(nombre_estado)) > 0)
) ENGINE=InnoDB;


CREATE TABLE personas (
    id INT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(100) NOT NULL,
    documento_identidad VARCHAR(20) NOT NULL UNIQUE,
    empresa_id INT,
    tipo_persona ENUM('Trabajador', 'Invitado') NOT NULL,
    estado_acceso_id INT NOT NULL DEFAULT 1,
    url_foto VARCHAR(255) NOT NULL,
    foto_archivo MEDIUMBLOB,
    vinculacion_laboral_activa BOOLEAN,

    CONSTRAINT fk_personas_empresa
        FOREIGN KEY (empresa_id)
        REFERENCES empresas(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_personas_estado
        FOREIGN KEY (estado_acceso_id)
        REFERENCES persona_estados_acceso(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT ck_personas_nombre
        CHECK (CHAR_LENGTH(TRIM(nombre)) > 0),

    CONSTRAINT ck_personas_documento
        CHECK (CHAR_LENGTH(TRIM(documento_identidad)) > 0),

    CONSTRAINT ck_personas_foto
        CHECK (CHAR_LENGTH(TRIM(url_foto)) > 0),

    CONSTRAINT ck_personas_vinculacion CHECK (
        (
            tipo_persona = 'Trabajador'
            AND empresa_id IS NOT NULL
            AND vinculacion_laboral_activa IS NOT NULL
            AND vinculacion_laboral_activa IN (0, 1)
        )
        OR
        (
            tipo_persona = 'Invitado'
            AND empresa_id IS NULL
            AND vinculacion_laboral_activa IS NULL
        )
    )
) ENGINE=InnoDB;


CREATE TABLE visitas (
    id INT AUTO_INCREMENT PRIMARY KEY,
    persona_id INT NOT NULL,
    empresa_destino_id INT NOT NULL,
    funcionario_destinatario_id INT,

    tipo_ingreso ENUM(
        'Invitado preregistrado',
        'Invitado no anunciado',
        'Trabajador con carnet',
        'Trabajador con carnet olvidado'
    ) NOT NULL,

    fecha_programada DATETIME,
    fecha_aprobacion DATETIME,
    fecha_entrada DATETIME,
    fecha_salida DATETIME,
    fecha_cierre_sistema DATETIME,
    estado_visita_id INT NOT NULL,
    vehiculo_placa VARCHAR(10),
    aprobada_por_id INT,

    CONSTRAINT fk_visitas_persona
        FOREIGN KEY (persona_id)
        REFERENCES personas(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_visitas_empresa
        FOREIGN KEY (empresa_destino_id)
        REFERENCES empresas(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_visitas_destinatario
        FOREIGN KEY (funcionario_destinatario_id)
        REFERENCES usuarios(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_visitas_estado
        FOREIGN KEY (estado_visita_id)
        REFERENCES visita_estados(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_visitas_aprobador
        FOREIGN KEY (aprobada_por_id)
        REFERENCES usuarios(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT ck_visitas_programacion CHECK (
        (
            tipo_ingreso = 'Invitado preregistrado'
            AND fecha_programada IS NOT NULL
        )
        OR
        (
            tipo_ingreso <> 'Invitado preregistrado'
            AND fecha_programada IS NULL
        )
    ),

    CONSTRAINT ck_visitas_destinatario CHECK (
        (
            tipo_ingreso = 'Trabajador con carnet'
            AND funcionario_destinatario_id IS NULL
        )
        OR
        (
            tipo_ingreso <> 'Trabajador con carnet'
            AND funcionario_destinatario_id IS NOT NULL
        )
    ),

    CONSTRAINT ck_visitas_aprobacion CHECK (
        (
            aprobada_por_id IS NULL
            AND fecha_aprobacion IS NULL
        )
        OR
        (
            aprobada_por_id IS NOT NULL
            AND fecha_aprobacion IS NOT NULL
            AND funcionario_destinatario_id IS NOT NULL
            AND aprobada_por_id = funcionario_destinatario_id
        )
    ),

    CONSTRAINT ck_visitas_ingreso_habitual CHECK (
        tipo_ingreso <> 'Trabajador con carnet'
        OR
        (
            aprobada_por_id IS NULL
            AND fecha_aprobacion IS NULL
        )
    ),

    CONSTRAINT ck_visitas_orden_aprobacion CHECK (
        fecha_entrada IS NULL
        OR fecha_aprobacion IS NULL
        OR fecha_entrada >= fecha_aprobacion
    ),

    CONSTRAINT ck_visitas_orden_salida CHECK (
        fecha_salida IS NULL
        OR
        (
            fecha_entrada IS NOT NULL
            AND fecha_salida >= fecha_entrada
        )
    ),

    CONSTRAINT ck_visitas_orden_cierre CHECK (
        fecha_cierre_sistema IS NULL
        OR
        (
            fecha_entrada IS NOT NULL
            AND fecha_cierre_sistema >= fecha_entrada
        )
    ),

    CONSTRAINT ck_visitas_salida_o_cierre CHECK (
        fecha_salida IS NULL
        OR fecha_cierre_sistema IS NULL
    ),

    INDEX ix_visitas_persona_entrada
        (persona_id, fecha_entrada),

    INDEX ix_visitas_empresa_entrada
        (empresa_destino_id, fecha_entrada),

    INDEX ix_visitas_destinatario_estado
        (funcionario_destinatario_id, estado_visita_id),

    INDEX ix_visitas_estado_entrada
        (estado_visita_id, fecha_entrada)
) ENGINE=InnoDB;


CREATE TABLE incidentes (
    id INT AUTO_INCREMENT PRIMARY KEY,
    visita_id INT NOT NULL,
    registrado_por_id INT NOT NULL,
    fecha_incidente DATETIME NOT NULL,
    descripcion TEXT NOT NULL,

    CONSTRAINT fk_incidentes_visita
        FOREIGN KEY (visita_id)
        REFERENCES visitas(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_incidentes_supervisor
        FOREIGN KEY (registrado_por_id)
        REFERENCES usuarios(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT ck_incidentes_descripcion
        CHECK (CHAR_LENGTH(TRIM(descripcion)) > 0),

    INDEX ix_incidentes_fecha (fecha_incidente)
) ENGINE=InnoDB;


CREATE TABLE bitacora_auditoria (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    usuario_id INT,
    fecha_hora TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accion_realizada VARCHAR(255) NOT NULL,
    tabla_afectada VARCHAR(100),
    registro_id_afectado INT,
    detalles TEXT NOT NULL,

    CONSTRAINT fk_auditoria_usuario
        FOREIGN KEY (usuario_id)
        REFERENCES usuarios(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT ck_auditoria_accion
        CHECK (CHAR_LENGTH(TRIM(accion_realizada)) > 0),

    CONSTRAINT ck_auditoria_detalles
        CHECK (CHAR_LENGTH(TRIM(detalles)) > 0),

    INDEX ix_auditoria_fecha
        (fecha_hora),

    INDEX ix_auditoria_usuario_fecha
        (usuario_id, fecha_hora),

    INDEX ix_auditoria_registro
        (tabla_afectada, registro_id_afectado)
) ENGINE=InnoDB;


-- Impedir modificaciones de registros de auditoría.

CREATE TRIGGER auditoria_impedir_actualizacion
BEFORE UPDATE ON bitacora_auditoria
FOR EACH ROW
SIGNAL SQLSTATE '45000'
SET MESSAGE_TEXT =
    'Los registros de auditoría no se pueden modificar';


-- Impedir eliminaciones de registros de auditoría.

CREATE TRIGGER auditoria_impedir_eliminacion
BEFORE DELETE ON bitacora_auditoria
FOR EACH ROW
SIGNAL SQLSTATE '45000'
SET MESSAGE_TEXT =
    'Los registros de auditoría no se pueden eliminar';


-- Las reglas de funcionamiento se completarán desde Java:
-- permisos y alcance por usuario;
-- empresa obligatoria para funcionarios;
-- rol y empresa del destinatario;
-- prohibiciones y vinculación laboral vigente;
-- intervalos autorizados de ingreso;
-- coherencia entre estados y fechas;
-- prevención de ingresos duplicados;
-- cierres automáticos;
-- operaciones críticas y auditoría dentro de una misma transacción.
