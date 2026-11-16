-- ============================================================================
-- 003 · Citas médicas, prioridad de casos y adjuntos del chat
-- Ejecutar sobre la misma base de datos de la aplicación.
-- Es idempotente: se puede ejecutar varias veces sin duplicar nada.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Tabla de citas
-- ----------------------------------------------------------------------------
DELIMITER $$

DROP PROCEDURE IF EXISTS luxe_crear_citas $$
CREATE PROCEDURE luxe_crear_citas()
BEGIN
    DECLARE v_tipo_paciente VARCHAR(64);
    DECLARE v_tipo_doctor   VARCHAR(64);

    SELECT COLUMN_TYPE INTO v_tipo_paciente
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'pacientes'
       AND COLUMN_NAME = 'id';

    SELECT COLUMN_TYPE INTO v_tipo_doctor
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'doctores'
       AND COLUMN_NAME = 'id';

    IF v_tipo_paciente IS NULL OR v_tipo_doctor IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Faltan las tablas pacientes o doctores';
    END IF;

    SET @ddl = CONCAT(
        'CREATE TABLE IF NOT EXISTS citas (',
        ' id INT NOT NULL AUTO_INCREMENT,',
        ' id_paciente ', v_tipo_paciente, ' NOT NULL,',
        ' id_doctor ', v_tipo_doctor, ' NOT NULL,',
        ' fecha_hora DATETIME NOT NULL,',
        ' motivo VARCHAR(500) NOT NULL,',
        ' estado ENUM(''Solicitada'',''Confirmada'',''Atendida'',''Cancelada'') ',
        '   NOT NULL DEFAULT ''Solicitada'',',
        ' observaciones VARCHAR(500) NULL,',
        ' creado_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,',
        ' actualizado_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ',
        '   ON UPDATE CURRENT_TIMESTAMP,',
        ' PRIMARY KEY (id),',
        ' INDEX idx_citas_doctor_fecha (id_doctor, fecha_hora),',
        ' INDEX idx_citas_paciente_fecha (id_paciente, fecha_hora),',
        ' INDEX idx_citas_estado_fecha (estado, fecha_hora)',
        ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4'
    );
    PREPARE st FROM @ddl;
    EXECUTE st;
    DEALLOCATE PREPARE st;
END $$

DROP PROCEDURE IF EXISTS luxe_columna $$
CREATE PROCEDURE luxe_columna(
    IN p_tabla VARCHAR(64),
    IN p_columna VARCHAR(64),
    IN p_definicion VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = p_tabla
           AND COLUMN_NAME = p_columna
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_tabla, '` ADD COLUMN `', p_columna, '` ', p_definicion);
        PREPARE st FROM @ddl;
        EXECUTE st;
        DEALLOCATE PREPARE st;
    END IF;
END $$

DELIMITER ;

CALL luxe_crear_citas();

-- Por si una ejecución anterior creó la tabla sin estas columnas.
CALL luxe_columna('citas', 'observaciones', 'VARCHAR(500) NULL');
CALL luxe_columna('citas', 'creado_en', 'DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP');
CALL luxe_columna('citas', 'actualizado_en',
    'DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');

DROP PROCEDURE luxe_crear_citas;

-- ----------------------------------------------------------------------------
-- 2. Columnas nuevas en casos clínicos y mensajes
-- ----------------------------------------------------------------------------
CALL luxe_columna('casos_medicos', 'prioridad',
    'ENUM(''Baja'',''Media'',''Alta'') NOT NULL DEFAULT ''Media''');
CALL luxe_columna('casos_medicos', 'fecha_cierre', 'DATETIME NULL');

CALL luxe_columna('mensajes_chat', 'adjunto_nombre', 'VARCHAR(180) NULL');
CALL luxe_columna('mensajes_chat', 'adjunto_tipo', 'VARCHAR(100) NULL');
CALL luxe_columna('mensajes_chat', 'adjunto_datos', 'MEDIUMBLOB NULL');

DROP PROCEDURE luxe_columna;

-- ----------------------------------------------------------------------------
-- 3. Índices y claves foráneas de citas
-- ----------------------------------------------------------------------------
DELIMITER $$

DROP PROCEDURE IF EXISTS luxe_indice_003 $$
CREATE PROCEDURE luxe_indice_003(
    IN p_tabla VARCHAR(64),
    IN p_nombre VARCHAR(64),
    IN p_columnas VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = p_tabla
           AND INDEX_NAME = p_nombre
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_tabla, '` ADD INDEX `', p_nombre, '` (', p_columnas, ')');
        PREPARE st FROM @ddl;
        EXECUTE st;
        DEALLOCATE PREPARE st;
    END IF;
END $$

DROP PROCEDURE IF EXISTS luxe_fk_003 $$
CREATE PROCEDURE luxe_fk_003(
    IN p_tabla VARCHAR(64),
    IN p_nombre VARCHAR(64),
    IN p_columna VARCHAR(64),
    IN p_ref_tabla VARCHAR(64),
    IN p_ref_columna VARCHAR(64),
    IN p_accion VARCHAR(20))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
         WHERE CONSTRAINT_SCHEMA = DATABASE()
           AND TABLE_NAME = p_tabla
           AND CONSTRAINT_NAME = p_nombre
           AND CONSTRAINT_TYPE = 'FOREIGN KEY'
    ) THEN
        SET @ddl = CONCAT(
            'ALTER TABLE `', p_tabla, '` ADD CONSTRAINT `', p_nombre,
            '` FOREIGN KEY (`', p_columna, '`) REFERENCES `', p_ref_tabla,
            '` (`', p_ref_columna, '`)',
            IF(p_accion = '', '', CONCAT(' ON DELETE ', p_accion))
        );
        PREPARE st FROM @ddl;
        EXECUTE st;
        DEALLOCATE PREPARE st;
    END IF;
END $$

DELIMITER ;

CALL luxe_indice_003('casos_medicos', 'idx_casos_prioridad_estado',
    '`prioridad`,`estado`');
CALL luxe_indice_003('citas', 'idx_citas_paciente_estado',
    '`id_paciente`,`estado`,`fecha_hora`');
CALL luxe_indice_003('citas', 'idx_citas_doctor_estado',
    '`id_doctor`,`estado`,`fecha_hora`');

CALL luxe_fk_003('citas', 'fk_citas_paciente', 'id_paciente', 'pacientes', 'id', 'CASCADE');
CALL luxe_fk_003('citas', 'fk_citas_doctor', 'id_doctor', 'doctores', 'id', 'CASCADE');

DROP PROCEDURE luxe_indice_003;
DROP PROCEDURE luxe_fk_003;

-- ----------------------------------------------------------------------------
-- 4. Verificación rápida
-- ----------------------------------------------------------------------------
SELECT 'citas creadas' AS comprobacion, COUNT(*) AS total FROM citas
UNION ALL
SELECT 'casos con prioridad', COUNT(*) FROM casos_medicos WHERE prioridad IS NOT NULL
UNION ALL
SELECT 'mensajes con adjunto', COUNT(*) FROM mensajes_chat WHERE adjunto_nombre IS NOT NULL;

SHOW COLUMNS FROM citas;
