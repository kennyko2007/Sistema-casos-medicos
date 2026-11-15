-- =====================================================================
-- 002 · Relaciones (claves foráneas) e índices para LuxeHealth
-- Motor: MySQL 5.7 / 8.0  ·  Ejecutar UNA vez sobre la base del sistema.
--
-- ANTES DE EJECUTAR:
--   1. Haz un respaldo:  mysqldump -u USUARIO -p BASE > respaldo.sql
--   2. Revisa que no existan datos huérfanos (consulta del final).
--   3. Todas las columnas id deben ser del mismo tipo (normalmente INT).
--
-- El script es idempotente: omite una relación o índice si ya existe.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Verificación previa: tipos de las columnas que se van a relacionar
-- ---------------------------------------------------------------------
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND COLUMN_NAME IN ('id', 'id_usuario', 'id_paciente', 'id_doctor', 'id_enfermero',
                      'id_caso', 'id_atencion', 'id_especialidad', 'id_admin_registro')
ORDER BY TABLE_NAME, COLUMN_NAME;

-- ---------------------------------------------------------------------
-- 2. Datos huérfanos (todo debe devolver 0 antes de crear las relaciones)
--    Si alguna consulta devuelve > 0, corrige esos registros primero.
-- ---------------------------------------------------------------------
SELECT 'pacientes sin usuario' AS problema, COUNT(*) AS total
FROM pacientes p LEFT JOIN usuarios u ON u.id = p.id_usuario WHERE u.id IS NULL
UNION ALL
SELECT 'doctores sin usuario', COUNT(*)
FROM doctores d LEFT JOIN usuarios u ON u.id = d.id_usuario WHERE u.id IS NULL
UNION ALL
SELECT 'enfermeros sin usuario', COUNT(*)
FROM enfermeros e LEFT JOIN usuarios u ON u.id = e.id_usuario WHERE u.id IS NULL
UNION ALL
SELECT 'administrativos sin usuario', COUNT(*)
FROM personal_administrativo pa LEFT JOIN usuarios u ON u.id = pa.id_usuario WHERE u.id IS NULL
UNION ALL
SELECT 'horarios sin usuario', COUNT(*)
FROM horarios h LEFT JOIN usuarios u ON u.id = h.id_usuario WHERE u.id IS NULL
UNION ALL
SELECT 'asistencia sin usuario', COUNT(*)
FROM asistencia a LEFT JOIN usuarios u ON u.id = a.id_usuario WHERE u.id IS NULL
UNION ALL
SELECT 'asistencia sin admin', COUNT(*)
FROM asistencia a LEFT JOIN usuarios u ON u.id = a.id_admin_registro
WHERE a.id_admin_registro IS NOT NULL AND u.id IS NULL
UNION ALL
SELECT 'doctor_especialidades sin doctor', COUNT(*)
FROM doctor_especialidades de LEFT JOIN doctores d ON d.id = de.id_doctor WHERE d.id IS NULL
UNION ALL
SELECT 'doctor_especialidades sin especialidad', COUNT(*)
FROM doctor_especialidades de LEFT JOIN especialidades e ON e.id = de.id_especialidad WHERE e.id IS NULL
UNION ALL
SELECT 'asignaciones sin enfermero', COUNT(*)
FROM asignacion_enfermero_doctor a LEFT JOIN enfermeros e ON e.id = a.id_enfermero WHERE e.id IS NULL
UNION ALL
SELECT 'asignaciones sin doctor', COUNT(*)
FROM asignacion_enfermero_doctor a LEFT JOIN doctores d ON d.id = a.id_doctor WHERE d.id IS NULL
UNION ALL
SELECT 'casos sin paciente', COUNT(*)
FROM casos_medicos c LEFT JOIN pacientes p ON p.id = c.id_paciente WHERE p.id IS NULL
UNION ALL
SELECT 'casos sin doctor', COUNT(*)
FROM casos_medicos c LEFT JOIN doctores d ON d.id = c.id_doctor WHERE d.id IS NULL
UNION ALL
SELECT 'atenciones sin caso', COUNT(*)
FROM atenciones a LEFT JOIN casos_medicos c ON c.id = a.id_caso WHERE c.id IS NULL
UNION ALL
SELECT 'atenciones sin doctor', COUNT(*)
FROM atenciones a LEFT JOIN doctores d ON d.id = a.id_doctor WHERE d.id IS NULL
UNION ALL
SELECT 'atenciones sin especialidad', COUNT(*)
FROM atenciones a LEFT JOIN especialidades e ON e.id = a.id_especialidad WHERE e.id IS NULL
UNION ALL
SELECT 'atenciones con enfermero inexistente', COUNT(*)
FROM atenciones a LEFT JOIN enfermeros e ON e.id = a.id_enfermero
WHERE a.id_enfermero IS NOT NULL AND e.id IS NULL
UNION ALL
SELECT 'medicamentos sin atención', COUNT(*)
FROM medicamentos_recetados m LEFT JOIN atenciones a ON a.id = m.id_atencion WHERE a.id IS NULL
UNION ALL
SELECT 'mensajes sin caso', COUNT(*)
FROM mensajes_chat m LEFT JOIN casos_medicos c ON c.id = m.id_caso WHERE c.id IS NULL;

-- ---------------------------------------------------------------------
-- 3. Procedimientos auxiliares (se eliminan al final)
-- ---------------------------------------------------------------------
DELIMITER $$

DROP PROCEDURE IF EXISTS luxe_fk $$
CREATE PROCEDURE luxe_fk(
    IN p_tabla   VARCHAR(64),
    IN p_nombre  VARCHAR(64),
    IN p_col     VARCHAR(64),
    IN p_ref     VARCHAR(64),
    IN p_refcol  VARCHAR(64),
    IN p_accion  VARCHAR(20))
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
            '` FOREIGN KEY (`', p_col, '`) REFERENCES `', p_ref, '` (`', p_refcol, '`)',
            IF(p_accion = '', '', CONCAT(' ON DELETE ', p_accion)));
        PREPARE st FROM @ddl;
        EXECUTE st;
        DEALLOCATE PREPARE st;
    END IF;
END $$

DROP PROCEDURE IF EXISTS luxe_indice $$
CREATE PROCEDURE luxe_indice(
    IN p_tabla    VARCHAR(64),
    IN p_nombre   VARCHAR(64),
    IN p_columnas VARCHAR(255),
    IN p_unico    TINYINT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_tabla
          AND INDEX_NAME = p_nombre
    ) THEN
        SET @ddl = CONCAT(
            'ALTER TABLE `', p_tabla, '` ADD ',
            IF(p_unico = 1, 'UNIQUE ', ''),
            'INDEX `', p_nombre, '` (', p_columnas, ')');
        PREPARE st FROM @ddl;
        EXECUTE st;
        DEALLOCATE PREPARE st;
    END IF;
END $$

DELIMITER ;

-- ---------------------------------------------------------------------
-- 4. Claves foráneas
-- ---------------------------------------------------------------------

-- Cada perfil pertenece a una cuenta de usuario
CALL luxe_fk('pacientes',  'fk_pacientes_usuario',  'id_usuario', 'usuarios', 'id', '');
CALL luxe_fk('doctores',   'fk_doctores_usuario',   'id_usuario', 'usuarios', 'id', '');
CALL luxe_fk('enfermeros', 'fk_enfermeros_usuario', 'id_usuario', 'usuarios', 'id', '');
CALL luxe_fk('personal_administrativo', 'fk_padmin_usuario', 'id_usuario', 'usuarios', 'id', '');

-- Especialidades de cada doctor (tabla puente)
CALL luxe_fk('doctor_especialidades', 'fk_de_doctor', 'id_doctor', 'doctores', 'id', 'CASCADE');
CALL luxe_fk('doctor_especialidades', 'fk_de_especialidad', 'id_especialidad', 'especialidades', 'id', '');

-- Horarios y asistencia
CALL luxe_fk('horarios', 'fk_horarios_usuario', 'id_usuario', 'usuarios', 'id', 'CASCADE');
CALL luxe_fk('asistencia', 'fk_asistencia_usuario', 'id_usuario', 'usuarios', 'id', 'CASCADE');
CALL luxe_fk('asistencia', 'fk_asistencia_admin', 'id_admin_registro', 'usuarios', 'id', '');

-- Enfermero asignado a un doctor
CALL luxe_fk('asignacion_enfermero_doctor', 'fk_aed_enfermero', 'id_enfermero', 'enfermeros', 'id', 'CASCADE');
CALL luxe_fk('asignacion_enfermero_doctor', 'fk_aed_doctor', 'id_doctor', 'doctores', 'id', 'CASCADE');

-- Caso clínico: un paciente atendido por un doctor
CALL luxe_fk('casos_medicos', 'fk_casos_paciente', 'id_paciente', 'pacientes', 'id', '');
CALL luxe_fk('casos_medicos', 'fk_casos_doctor', 'id_doctor', 'doctores', 'id', '');

-- Atención: pertenece a un caso y la firma un doctor
CALL luxe_fk('atenciones', 'fk_atenciones_caso', 'id_caso', 'casos_medicos', 'id', 'CASCADE');
CALL luxe_fk('atenciones', 'fk_atenciones_doctor', 'id_doctor', 'doctores', 'id', '');
CALL luxe_fk('atenciones', 'fk_atenciones_enfermero', 'id_enfermero', 'enfermeros', 'id', 'SET NULL');
-- Si en tu esquema id_enfermero es NOT NULL, cambia 'SET NULL' por '' en la línea anterior.
CALL luxe_fk('atenciones', 'fk_atenciones_especialidad', 'id_especialidad', 'especialidades', 'id', '');

-- Recetas y chat
CALL luxe_fk('medicamentos_recetados', 'fk_medicamentos_atencion', 'id_atencion', 'atenciones', 'id', 'CASCADE');
CALL luxe_fk('mensajes_chat', 'fk_mensajes_caso', 'id_caso', 'casos_medicos', 'id', 'CASCADE');

-- ---------------------------------------------------------------------
-- 5. Índices y claves únicas que usan las consultas de la aplicación
-- ---------------------------------------------------------------------

-- Un perfil por cuenta
CALL luxe_indice('pacientes', 'uq_pacientes_usuario', '`id_usuario`', 1);
CALL luxe_indice('doctores', 'uq_doctores_usuario', '`id_usuario`', 1);
CALL luxe_indice('enfermeros', 'uq_enfermeros_usuario', '`id_usuario`', 1);
CALL luxe_indice('personal_administrativo', 'uq_padmin_usuario', '`id_usuario`', 1);

-- Un doctor no repite especialidad; un enfermero no repite doctor
CALL luxe_indice('doctor_especialidades', 'uq_de_doctor_especialidad', '`id_doctor`,`id_especialidad`', 1);
CALL luxe_indice('asignacion_enfermero_doctor', 'uq_aed_enfermero_doctor', '`id_enfermero`,`id_doctor`', 1);
CALL luxe_indice('asignacion_enfermero_doctor', 'idx_aed_doctor_activo', '`id_doctor`,`activo`', 0);

-- Una asistencia por persona y día
CALL luxe_indice('asistencia', 'uq_asistencia_usuario_fecha', '`id_usuario`,`fecha`', 1);
CALL luxe_indice('asistencia', 'idx_asistencia_fecha', '`fecha`', 0);

-- Horarios por usuario y día
CALL luxe_indice('horarios', 'idx_horarios_usuario_dia', '`id_usuario`,`dia_semana`', 0);

-- Bandeja del chat y campanita
CALL luxe_indice('mensajes_chat', 'idx_mensajes_caso_remitente_leido', '`id_caso`,`remitente`,`leido`', 0);
CALL luxe_indice('mensajes_chat', 'idx_mensajes_caso_fecha', '`id_caso`,`fecha_envio`,`id`', 0);

-- Listados de casos por paciente / doctor
CALL luxe_indice('casos_medicos', 'idx_casos_paciente', '`id_paciente`,`fecha_creacion`', 0);
CALL luxe_indice('casos_medicos', 'idx_casos_doctor_estado', '`id_doctor`,`estado`,`fecha_creacion`', 0);
CALL luxe_indice('casos_medicos', 'idx_casos_estado', '`estado`', 0);

-- Historial de atenciones
CALL luxe_indice('atenciones', 'idx_atenciones_caso', '`id_caso`,`fecha_atencion`', 0);
CALL luxe_indice('atenciones', 'idx_atenciones_doctor', '`id_doctor`,`fecha_atencion`', 0);

-- Directorio y conteos del CEO
CALL luxe_indice('usuarios', 'idx_usuarios_rol_activo', '`rol`,`activo`', 0);

-- ---------------------------------------------------------------------
-- 6. Limpieza
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS luxe_fk;
DROP PROCEDURE IF EXISTS luxe_indice;

-- ---------------------------------------------------------------------
-- 7. Verificación: relaciones creadas
-- ---------------------------------------------------------------------
SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME,
       REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = DATABASE()
  AND REFERENCED_TABLE_NAME IS NOT NULL
ORDER BY TABLE_NAME, CONSTRAINT_NAME;
