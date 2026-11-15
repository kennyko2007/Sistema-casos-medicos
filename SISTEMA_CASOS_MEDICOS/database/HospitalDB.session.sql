-- =========================================================================
-- SISTEMA DE CASOS MEDICOS - HOSPITAL PRIVADO  (v2)
-- Roles: admin, ceo, doctor, enfermero, paciente
-- Contraseña de TODOS los usuarios de prueba: 123456 (guardada con BCrypt)
-- =========================================================================
DROP DATABASE IF EXISTS sistema_casos_medicos;
CREATE DATABASE sistema_casos_medicos CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE sistema_casos_medicos;

-- -------------------------------------------------------------------------
-- 1. USUARIOS (login). Nunca se borran: solo se deshabilitan (activo = 0)
-- -------------------------------------------------------------------------
CREATE TABLE usuarios (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,          -- usuario asignado por el hospital
    correo VARCHAR(100) NULL UNIQUE,               -- opcional (recuperación de datos)
    password VARCHAR(255) NOT NULL,                -- hash BCrypt, nunca texto plano
    rol ENUM('admin','ceo','doctor','enfermero','paciente') NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    intentos_fallidos INT NOT NULL DEFAULT 0,      -- bloqueo de login
    bloqueado_hasta DATETIME NULL,                 -- si es futuro, no puede entrar
    ultimo_acceso DATETIME NULL,
    creado_en TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- -------------------------------------------------------------------------
-- 2. PERFILES
-- -------------------------------------------------------------------------
-- Admin y CEO
CREATE TABLE personal_administrativo (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT NOT NULL UNIQUE,
    nombre_completo VARCHAR(150) NOT NULL,
    cargo VARCHAR(100),
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id) ON DELETE RESTRICT
);

CREATE TABLE doctores (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT NOT NULL UNIQUE,
    nombre_completo VARCHAR(150) NOT NULL,
    telefono VARCHAR(20),
    cv_resumen TEXT,
    cv_pdf_ruta VARCHAR(255) NULL,                 -- ruta del CV en PDF
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id) ON DELETE RESTRICT
);

CREATE TABLE especialidades (
    id INT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE doctor_especialidades (
    id_doctor INT NOT NULL,
    id_especialidad INT NOT NULL,
    PRIMARY KEY (id_doctor, id_especialidad),
    FOREIGN KEY (id_doctor) REFERENCES doctores(id) ON DELETE RESTRICT,
    FOREIGN KEY (id_especialidad) REFERENCES especialidades(id) ON DELETE RESTRICT
);

CREATE TABLE enfermeros (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT NOT NULL UNIQUE,
    nombre_completo VARCHAR(150) NOT NULL,
    telefono VARCHAR(20),
    cv_pdf_ruta VARCHAR(255) NULL,
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id) ON DELETE RESTRICT
);

-- A qué doctor ayuda cada enfermero/a
CREATE TABLE asignacion_enfermero_doctor (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_enfermero INT NOT NULL,
    id_doctor INT NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    asignado_en TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_enfermero) REFERENCES enfermeros(id) ON DELETE RESTRICT,
    FOREIGN KEY (id_doctor) REFERENCES doctores(id) ON DELETE RESTRICT
);

CREATE TABLE pacientes (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT NOT NULL UNIQUE,
    nombre_completo VARCHAR(150) NOT NULL,
    fecha_nacimiento DATE,
    telefono VARCHAR(20),
    tipo_sangre VARCHAR(5),
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id) ON DELETE RESTRICT
);

-- -------------------------------------------------------------------------
-- 3. HORARIOS Y ASISTENCIA (doctores y enfermeros/as)
-- -------------------------------------------------------------------------
CREATE TABLE horarios (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT NOT NULL,
    dia_semana ENUM('Lunes','Martes','Miércoles','Jueves','Viernes','Sábado','Domingo') NOT NULL,
    hora_inicio TIME NOT NULL,
    hora_fin TIME NOT NULL,
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id) ON DELETE RESTRICT
);

CREATE TABLE asistencia (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT NOT NULL,                       -- el doctor/enfermero evaluado
    fecha DATE NOT NULL,
    hora_registro TIME NOT NULL,
    estado ENUM('Presente','Tarde','Ausente','Permiso') NOT NULL,
    observacion VARCHAR(255) NULL,
    id_admin_registro INT NOT NULL,                -- el admin que la marcó
    UNIQUE KEY uq_asistencia_dia (id_usuario, fecha),
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id) ON DELETE RESTRICT,
    FOREIGN KEY (id_admin_registro) REFERENCES usuarios(id) ON DELETE RESTRICT
);

-- -------------------------------------------------------------------------
-- 4. CASOS, ATENCIONES Y MEDICAMENTOS
-- -------------------------------------------------------------------------
CREATE TABLE casos_medicos (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_paciente INT NOT NULL,
    id_doctor INT NOT NULL,                        -- doctor que lo atiende
    titulo_caso VARCHAR(150) NOT NULL,
    descripcion_sintomas TEXT NOT NULL,
    diagnostico TEXT,
    estado ENUM('Pendiente','En Tratamiento','Concluido') DEFAULT 'Pendiente',
    fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_paciente) REFERENCES pacientes(id) ON DELETE RESTRICT,
    FOREIGN KEY (id_doctor) REFERENCES doctores(id) ON DELETE RESTRICT
);

-- Cada consulta de un caso: genera un PDF y una recomendación
CREATE TABLE atenciones (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_caso INT NOT NULL,
    id_doctor INT NOT NULL,
    id_enfermero INT NULL,                         -- quien tomó los signos vitales
    id_especialidad INT NOT NULL,                  -- define la plantilla usada
    fecha_atencion DATETIME DEFAULT CURRENT_TIMESTAMP,
    datos_formulario JSON NULL,                    -- campos propios de la plantilla (cardiología, neurología...)
    recomendacion TEXT NULL,                       -- NULL = "Sin recomendaciones"
    pdf_ruta VARCHAR(255) NULL,                    -- PDF de la historia clínica
    FOREIGN KEY (id_caso) REFERENCES casos_medicos(id) ON DELETE RESTRICT,
    FOREIGN KEY (id_doctor) REFERENCES doctores(id) ON DELETE RESTRICT,
    FOREIGN KEY (id_enfermero) REFERENCES enfermeros(id) ON DELETE RESTRICT,
    FOREIGN KEY (id_especialidad) REFERENCES especialidades(id) ON DELETE RESTRICT
);

CREATE TABLE medicamentos_recetados (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_atencion INT NOT NULL,
    nombre VARCHAR(120) NOT NULL,
    dosis VARCHAR(60),
    frecuencia VARCHAR(60),
    duracion VARCHAR(60),
    indicaciones VARCHAR(255),
    FOREIGN KEY (id_atencion) REFERENCES atenciones(id) ON DELETE RESTRICT
);

-- -------------------------------------------------------------------------
-- 5. CHAT PACIENTE <-> DOCTOR (por caso)
-- -------------------------------------------------------------------------
CREATE TABLE mensajes_chat (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_caso INT NOT NULL,
    remitente ENUM('paciente','doctor') NOT NULL,
    mensaje TEXT NOT NULL,
    leido BOOLEAN NOT NULL DEFAULT FALSE,
    fecha_envio TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_caso) REFERENCES casos_medicos(id) ON DELETE RESTRICT
);

-- -------------------------------------------------------------------------
-- 6. AUDITORIA (quién vio o hizo qué)
-- -------------------------------------------------------------------------
CREATE TABLE auditoria_accesos (
    id INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT NULL,
    accion VARCHAR(60) NOT NULL,                   -- LOGIN_OK, LOGIN_FALLIDO, VER_PDF, DESHABILITAR...
    entidad VARCHAR(60) NULL,
    id_entidad INT NULL,
    ip VARCHAR(45) NULL,
    fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id) ON DELETE SET NULL
);

-- -------------------------------------------------------------------------
-- 7. VISTA para la pantalla CASOS_CLINICOS del paciente
--    (una fila por atención: PDF + recomendación)
-- -------------------------------------------------------------------------
CREATE VIEW v_historial_paciente AS
SELECT a.id AS id_atencion,
       c.id_paciente,
       c.titulo_caso,
       a.fecha_atencion,
       d.nombre_completo AS doctor,
       e.nombre AS especialidad,
       a.pdf_ruta,
       COALESCE(NULLIF(TRIM(a.recomendacion), ''), 'Sin recomendaciones') AS recomendacion
FROM atenciones a
JOIN casos_medicos c ON c.id = a.id_caso
JOIN doctores d ON d.id = a.id_doctor
JOIN especialidades e ON e.id = a.id_especialidad;


-- =========================================================================
-- DATOS DE PRUEBA  (contraseña 123456 con BCrypt)
-- Los perfiles se enlazan por username, no por número, para evitar errores.
-- =========================================================================
INSERT INTO usuarios (username, correo, password, rol) VALUES
('admin1',    'admin1@clinica.com',        '$2b$10$5uuRgtirYXPwIoxIq8XCf.8InL/M.7lLip0BQ5zQWqtK1dA9rbyFu', 'admin'),
('ceo',       'ceo@clinica.com',           '$2b$10$5uuRgtirYXPwIoxIq8XCf.8InL/M.7lLip0BQ5zQWqtK1dA9rbyFu', 'ceo'),
('dr.carlos', 'dr.carlos@clinica.com',     '$2b$10$5uuRgtirYXPwIoxIq8XCf.8InL/M.7lLip0BQ5zQWqtK1dA9rbyFu', 'doctor'),
('dra.elena', 'dra.elena@clinica.com',     '$2b$10$5uuRgtirYXPwIoxIq8XCf.8InL/M.7lLip0BQ5zQWqtK1dA9rbyFu', 'doctor'),
('enf.lucia', 'enf.lucia@clinica.com',     '$2b$10$5uuRgtirYXPwIoxIq8XCf.8InL/M.7lLip0BQ5zQWqtK1dA9rbyFu', 'enfermero'),
('enf.marco', 'enf.marco@clinica.com',     '$2b$10$5uuRgtirYXPwIoxIq8XCf.8InL/M.7lLip0BQ5zQWqtK1dA9rbyFu', 'enfermero'),
('paciente1', 'paciente1@gmail.com',       '$2b$10$5uuRgtirYXPwIoxIq8XCf.8InL/M.7lLip0BQ5zQWqtK1dA9rbyFu', 'paciente'),
('paciente2', 'paciente2@gmail.com',       '$2b$10$5uuRgtirYXPwIoxIq8XCf.8InL/M.7lLip0BQ5zQWqtK1dA9rbyFu', 'paciente');

INSERT INTO personal_administrativo (id_usuario, nombre_completo, cargo) VALUES
((SELECT id FROM usuarios WHERE username='admin1'), 'Administrador General', 'Administrador'),
((SELECT id FROM usuarios WHERE username='ceo'),    'Director General',      'CEO');

INSERT INTO especialidades (nombre) VALUES
('Cardiología'), ('Medicina General'), ('Neurología'), ('Pediatría'), ('Medicina Interna');

INSERT INTO doctores (id_usuario, nombre_completo, telefono, cv_resumen, cv_pdf_ruta) VALUES
((SELECT id FROM usuarios WHERE username='dr.carlos'), 'Dr. Carlos Mendoza Tapia', '70123456',
 'Especialista en Cardiología y Medicina Interna con 10 años de trayectoria hospitalaria.', 'cv/dr_carlos.pdf'),
((SELECT id FROM usuarios WHERE username='dra.elena'), 'Dra. Elena Vargas Campos', '71234567',
 'Especialista en Neurología y Medicina General, investigadora clínica.', 'cv/dra_elena.pdf');

-- Todo doctor es también Medicina General
INSERT INTO doctor_especialidades (id_doctor, id_especialidad)
SELECT d.id, e.id FROM doctores d, especialidades e
WHERE (d.nombre_completo LIKE 'Dr. Carlos%' AND e.nombre IN ('Cardiología','Medicina Interna','Medicina General'))
   OR (d.nombre_completo LIKE 'Dra. Elena%' AND e.nombre IN ('Neurología','Medicina General'));

INSERT INTO enfermeros (id_usuario, nombre_completo, telefono) VALUES
((SELECT id FROM usuarios WHERE username='enf.lucia'), 'Lucía Fernández Rojas', '72345678'),
((SELECT id FROM usuarios WHERE username='enf.marco'), 'Marco Quispe Flores',   '72456789');

INSERT INTO asignacion_enfermero_doctor (id_enfermero, id_doctor) VALUES
((SELECT id FROM enfermeros WHERE nombre_completo LIKE 'Lucía%'), (SELECT id FROM doctores WHERE nombre_completo LIKE 'Dr. Carlos%')),
((SELECT id FROM enfermeros WHERE nombre_completo LIKE 'Marco%'), (SELECT id FROM doctores WHERE nombre_completo LIKE 'Dra. Elena%'));

INSERT INTO pacientes (id_usuario, nombre_completo, fecha_nacimiento, telefono, tipo_sangre) VALUES
((SELECT id FROM usuarios WHERE username='paciente1'), 'Tonny Cristhian Peredo Tarqui',   '2004-01-15', '73456789', 'O+'),
((SELECT id FROM usuarios WHERE username='paciente2'), 'Helen Margoth Irala Benavides',   '1998-06-20', '74567890', 'A+');

-- Horarios de prueba (lunes a viernes)
INSERT INTO horarios (id_usuario, dia_semana, hora_inicio, hora_fin)
SELECT u.id, d.dia, '08:00:00', '14:00:00'
FROM usuarios u
JOIN (SELECT 'Lunes' AS dia UNION SELECT 'Martes' UNION SELECT 'Miércoles'
      UNION SELECT 'Jueves' UNION SELECT 'Viernes') d
WHERE u.rol IN ('doctor','enfermero');

-- Asistencia de hoy (marcada por el admin)
INSERT INTO asistencia (id_usuario, fecha, hora_registro, estado, id_admin_registro)
SELECT u.id, CURDATE(), '08:05:00', 'Presente', (SELECT id FROM usuarios WHERE username='admin1')
FROM usuarios u WHERE u.username IN ('dr.carlos','enf.lucia');

-- Casos
INSERT INTO casos_medicos (id_paciente, id_doctor, titulo_caso, descripcion_sintomas, diagnostico, estado) VALUES
(1, 1, 'Control de presión y fatiga',
 'Presento mareos ocasionales por las mañanas y sensación de fatiga al caminar.',
 'Hipertensión leve en estudio.', 'En Tratamiento'),
(2, 2, 'Migrañas recurrentes',
 'Dolores de cabeza fuertes en la región frontal con sensibilidad a la luz y ruidos.',
 'Cefalea tensional crónica.', 'En Tratamiento');

-- Atenciones (la 2ª sin recomendación para probar el mensaje "Sin recomendaciones")
INSERT INTO atenciones (id_caso, id_doctor, id_enfermero, id_especialidad, datos_formulario, recomendacion, pdf_ruta) VALUES
(1, 1, 1, (SELECT id FROM especialidades WHERE nombre='Cardiología'),
 '{"presion":"140/90","pulso":82,"temperatura":36.7}',
 'Reducir consumo de sodio, caminatas de 30 min diarios.', 'pdfs/atencion_1.pdf'),
(2, 2, 2, (SELECT id FROM especialidades WHERE nombre='Neurología'),
 '{"presion":"120/80","pulso":74,"temperatura":36.5}',
 NULL, 'pdfs/atencion_2.pdf');

INSERT INTO medicamentos_recetados (id_atencion, nombre, dosis, frecuencia, duracion, indicaciones) VALUES
(1, 'Losartán', '50 mg', 'Cada 24 horas', '30 días', 'Tomar por la mañana'),
(2, 'Paracetamol', '500 mg', 'Cada 8 horas', '5 días', 'Solo si hay dolor');

INSERT INTO mensajes_chat (id_caso, remitente, mensaje) VALUES
(1, 'paciente', 'Doctor Carlos, ¿puedo seguir tomando café por las mañanas con este diagnóstico?'),
(1, 'doctor',   'Hola Tonny. Es preferible que lo reduzcas a medio vaso o cambies a té por un par de semanas mientras controlamos la presión.'),
(2, 'paciente', 'Dra. Elena, el dolor disminuyó pero aún siento molestia al despertar por las mañanas.'),
(2, 'doctor',   'Hola Helen, es un proceso normal de adaptación al tratamiento. Mantén las compresas frías en la frente antes de dormir.');