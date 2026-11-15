package bo.edu.casosmedicos.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import bo.edu.casosmedicos.archivos.AlmacenArchivos;
import bo.edu.casosmedicos.archivos.AlmacenArchivos.ArchivoInvalidoException;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

/**
 * Reemplaza a RecepcionController y PersonalController.
 * Nada se borra: los usuarios solo se deshabilitan (usuarios.activo = 0).
 * IMPORTANTE: este controller confía en que SecurityConfig restringe /admin/** al rol admin.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,50}$");
    private static final Pattern TIPO_SANGRE = Pattern.compile("^(A|B|AB|O)[+-]$");
    private static final Set<String> ESTADOS_ASISTENCIA = Set.of("Presente", "Tarde", "Ausente", "Permiso");
    private static final int PASSWORD_MIN = 6;

    private static final String SQL_ASISTENCIA_HOY =
        "SELECT u.id AS id_usuario, u.rol, " +
        "COALESCE(d.nombre_completo, e.nombre_completo) AS nombre, " +
        "(SELECT TIME_FORMAT(MIN(h.hora_inicio), '%H:%i') FROM horarios h " +
        "   WHERE h.id_usuario = u.id AND h.dia_semana = ?) AS hora_inicio, " +
        "(SELECT TIME_FORMAT(MAX(h.hora_fin), '%H:%i') FROM horarios h " +
        "   WHERE h.id_usuario = u.id AND h.dia_semana = ?) AS hora_fin, " +
        "COALESCE(a.estado, 'Sin marcar') AS estado, " +
        "TIME_FORMAT(a.hora_registro, '%H:%i') AS hora, a.observacion " +
        "FROM usuarios u " +
        "LEFT JOIN doctores d ON d.id_usuario = u.id " +
        "LEFT JOIN enfermeros e ON e.id_usuario = u.id " +
        "LEFT JOIN asistencia a ON a.id_usuario = u.id AND a.fecha = ? " +
        "WHERE u.rol IN ('doctor','enfermero') AND u.activo = 1 " +
        "ORDER BY u.rol, nombre";

    /** Asistencia del día (hora de La Paz) con el horario que le toca hoy a cada persona. */
    private List<Map<String, Object>> asistenciaDeHoy() {
        String dia = HoraBolivia.diaSemana();
        return jdbcTemplate.queryForList(SQL_ASISTENCIA_HOY, dia, dia, java.sql.Date.valueOf(HoraBolivia.hoy()));
    }

        private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final AlmacenArchivos almacenArchivos;

    public AdminController(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                           PlatformTransactionManager txManager, AlmacenArchivos almacenArchivos) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.tx = new TransactionTemplate(txManager);
        this.almacenArchivos = almacenArchivos;
    }

    // ------------------------------------------------------------------ utilidades

    private static String limpiar(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private int contar(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** Debe llamarse dentro de una transacción. */
    private long insertarUsuario(String username, String correo, String password, String rol) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO usuarios (username, correo, password, rol) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, correo);                       // null si no se indicó (UNIQUE permite varios NULL)
            ps.setString(3, passwordEncoder.encode(password));
            ps.setString(4, rol);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    /** Debe llamarse dentro de una transacción. */
    private long insertarConId(String sql, Object... args) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private void auditar(String actor, String accion, String entidad, Long idEntidad, HttpServletRequest request) {
        try {
            jdbcTemplate.update(
                "INSERT INTO auditoria_accesos (id_usuario, accion, entidad, id_entidad, ip) " +
                "VALUES ((SELECT id FROM usuarios WHERE username = ?), ?, ?, ?, ?)",
                actor, accion, entidad, idEntidad, request.getRemoteAddr());
        } catch (Exception e) {
            log.warn("No se pudo registrar la auditoría {}: {}", accion, e.getMessage());
        }
    }

    private static String tablaPerfil(String rol) {
        return switch (rol) {
            case "paciente" -> "pacientes";
            case "doctor" -> "doctores";
            case "enfermero" -> "enfermeros";
            default -> throw new IllegalArgumentException("Rol no gestionable: " + rol);
        };
    }

    // ------------------------------------------------------------------ REGISTRO DE PACIENTES

    @GetMapping("/registro_paciente")
    public String registroPaciente() {
        return "admin/registro_paciente";
    }

    @PostMapping("/pacientes/guardar")
    public String guardarPaciente(@RequestParam("username") String username,
                                  @RequestParam("password") String password,
                                  @RequestParam("nombreCompleto") String nombreCompleto,
                                  @RequestParam("fechaNacimiento")
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaNacimiento,
                                  @RequestParam(value = "correo", required = false) String correo,
                                  @RequestParam(value = "telefono", required = false) String telefono,
                                  @RequestParam(value = "tipoSangre", required = false) String tipoSangre,
                                  Authentication authentication, HttpServletRequest request) {
        final String user = username == null ? "" : username.trim();
        final String nombre = limpiar(nombreCompleto, 150);
        final String sangre = limpiar(tipoSangre, 5);

        if (!USERNAME.matcher(user).matches() || nombre == null) {
            return "redirect:/admin/registro_paciente?error=datos";
        }
        if (password == null || password.length() < PASSWORD_MIN) {
            return "redirect:/admin/registro_paciente?error=clave";
        }
        if (fechaNacimiento.isAfter(HoraBolivia.hoy())) {
            return "redirect:/admin/registro_paciente?error=fecha";
        }
        if (sangre != null && !TIPO_SANGRE.matcher(sangre).matches()) {
            return "redirect:/admin/registro_paciente?error=sangre";
        }

        try {
            Long idUsuario = tx.execute(status -> {
                long id = insertarUsuario(user, limpiar(correo, 100), password, "paciente");
                jdbcTemplate.update(
                    "INSERT INTO pacientes (id_usuario, nombre_completo, fecha_nacimiento, telefono, tipo_sangre) " +
                    "VALUES (?, ?, ?, ?, ?)",
                    id, nombre, java.sql.Date.valueOf(fechaNacimiento), limpiar(telefono, 20), sangre);
                return id;
            });
            auditar(authentication.getName(), "CREAR_USUARIO", "usuarios", idUsuario, request);
            return "redirect:/admin/registro_paciente?exito";
        } catch (DuplicateKeyException e) {
            return "redirect:/admin/registro_paciente?error=duplicado";
        } catch (Exception e) {
            log.error("Error al registrar paciente", e);
            return "redirect:/admin/registro_paciente?error=db";
        }
    }

    // ------------------------------------------------------------------ REGISTRO DE PERSONAL

    @GetMapping("/registro_personal")
    public String registroPersonal(Model model) {
        model.addAttribute("especialidades",
            jdbcTemplate.queryForList("SELECT id, nombre FROM especialidades ORDER BY nombre"));
        model.addAttribute("doctores", jdbcTemplate.queryForList(
            "SELECT d.id, d.nombre_completo FROM doctores d JOIN usuarios u ON u.id = d.id_usuario " +
            "WHERE u.activo = 1 ORDER BY d.nombre_completo"));
        return "admin/registro_personal";
    }

        @PostMapping("/personal/guardar")
    public String guardarPersonal(@RequestParam("rol") String rol,
                                  @RequestParam("username") String username,
                                  @RequestParam("password") String password,
                                  @RequestParam("nombreCompleto") String nombreCompleto,
                                  @RequestParam(value = "correo", required = false) String correo,
                                  @RequestParam(value = "telefono", required = false) String telefono,
                                  @RequestParam(value = "cvResumen", required = false) String cvResumen,
                                  @RequestParam(value = "cvPdf", required = false) MultipartFile cvPdf,
                                  @RequestParam(value = "especialidades", required = false) List<Integer> especialidades,
                                  @RequestParam(value = "idDoctor", required = false) Integer idDoctor,
                                  Authentication authentication, HttpServletRequest request) {
        final boolean esDoctor = "doctor".equals(rol);
        if (!esDoctor && !"enfermero".equals(rol)) {
            return "redirect:/admin/registro_personal?error=datos";
        }
        final String user = username == null ? "" : username.trim();
        final String nombre = limpiar(nombreCompleto, 150);
        if (!USERNAME.matcher(user).matches() || nombre == null) {
            return "redirect:/admin/registro_personal?error=datos";
        }
        if (password == null || password.length() < PASSWORD_MIN) {
            return "redirect:/admin/registro_personal?error=clave";
        }

        // CV opcional, solo para doctores. Se valida y guarda antes de la transacción.
        byte[] cvBytes = null;
        if (cvPdf != null && !cvPdf.isEmpty()) {
            try {
                cvBytes = almacenArchivos.validarCv(cvPdf);
            } catch (ArchivoInvalidoException e) {
                return "redirect:/admin/registro_personal?error=cv";
            } catch (IOException e) {
                log.error("Error al guardar el CV", e);
                return "redirect:/admin/registro_personal?error=db";
            }
        }
        final byte[] cvData = cvBytes;
        final String rutaCv = cvData == null ? null : "db";   // marca: el PDF está en la base de datos

        try {
            Long idUsuario = tx.execute(status -> {
                long id = insertarUsuario(user, limpiar(correo, 100), password, rol);

                if (esDoctor) {
                    long idDoc = insertarConId(
                        "INSERT INTO doctores (id_usuario, nombre_completo, telefono, cv_resumen, cv_pdf_ruta, cv_pdf_data) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                        id, nombre, limpiar(telefono, 20), limpiar(cvResumen, 5000), rutaCv, cvData);

                    // Todo doctor es también Medicina General
                    Set<Integer> ids = new LinkedHashSet<>();
                    Integer idGeneral = jdbcTemplate.queryForObject(
                        "SELECT id FROM especialidades WHERE nombre = 'Medicina General'", Integer.class);
                    ids.add(idGeneral);
                    if (especialidades != null) {
                        ids.addAll(especialidades);
                    }
                    for (Integer idEsp : ids) {
                        jdbcTemplate.update(
                            "INSERT INTO doctor_especialidades (id_doctor, id_especialidad) VALUES (?, ?)",
                            idDoc, idEsp);
                    }
                } else {
                    long idEnf = insertarConId(
                        "INSERT INTO enfermeros (id_usuario, nombre_completo, telefono, cv_pdf_ruta, cv_pdf_data) VALUES (?, ?, ?, ?, ?)",
                        id, nombre, limpiar(telefono, 20), rutaCv, cvData);
                    if (idDoctor != null) {
                        jdbcTemplate.update(
                            "INSERT INTO asignacion_enfermero_doctor (id_enfermero, id_doctor) VALUES (?, ?)",
                            idEnf, idDoctor);
                    }
                }
                return id;
            });
            auditar(authentication.getName(), "CREAR_USUARIO", "usuarios", idUsuario, request);
            return "redirect:/admin/registro_personal?exito";
        } catch (DuplicateKeyException e) {
            return "redirect:/admin/registro_personal?error=duplicado";
        } catch (Exception e) {
            log.error("Error al registrar personal", e);
            return "redirect:/admin/registro_personal?error=db";
        }
    }

    // ------------------------------------------------------------------ DIRECTORIO (listar / deshabilitar / editar)

    @GetMapping("/directorio")
    public String directorio(@RequestParam(value = "rol", required = false) String rol, Model model) {
        String sql =
            "SELECT u.id, u.username, u.correo, u.rol, u.activo, " +
            "DATE_FORMAT(u.ultimo_acceso, '%d/%m/%Y %H:%i') AS ultimo_acceso, " +
            "COALESCE(p.nombre_completo, d.nombre_completo, e.nombre_completo) AS nombre, " +
            "COALESCE(p.telefono, d.telefono, e.telefono) AS telefono, " +
            "COALESCE(d.cv_pdf_ruta, e.cv_pdf_ruta) AS cv_pdf_ruta " +
            "FROM usuarios u " +
            "LEFT JOIN pacientes p ON p.id_usuario = u.id " +
            "LEFT JOIN doctores d ON d.id_usuario = u.id " +
            "LEFT JOIN enfermeros e ON e.id_usuario = u.id " +
            "WHERE u.rol IN ('doctor','enfermero','paciente') ";
        List<Map<String, Object>> usuarios;
        if (rol != null && Set.of("doctor", "enfermero", "paciente").contains(rol)) {
            usuarios = jdbcTemplate.queryForList(sql + "AND u.rol = ? ORDER BY nombre", rol);
            model.addAttribute("rolFiltro", rol);
        } else {
            usuarios = jdbcTemplate.queryForList(sql + "ORDER BY u.rol, nombre");
        }
        model.addAttribute("usuarios", usuarios);
        return "admin/directorio";
    }

    /** Deshabilitar o habilitar. Nunca se borra. Al habilitar se reinician los intentos fallidos. */
    @PostMapping("/usuarios/{id}/estado")
    public String cambiarEstado(@PathVariable("id") Long id,
                                @RequestParam("activo") boolean activo,
                                Authentication authentication, HttpServletRequest request) {
        int n;
        if (activo) {
            n = jdbcTemplate.update(
                "UPDATE usuarios SET activo = 1, intentos_fallidos = 0, bloqueado_hasta = NULL " +
                "WHERE id = ? AND rol IN ('doctor','enfermero','paciente')", id);
        } else {
            n = jdbcTemplate.update(
                "UPDATE usuarios SET activo = 0 WHERE id = ? AND rol IN ('doctor','enfermero','paciente')", id);
        }
        if (n > 0) {
            auditar(authentication.getName(), activo ? "HABILITAR" : "DESHABILITAR", "usuarios", id, request);
        }
        return "redirect:/admin/directorio?" + (n > 0 ? "actualizado" : "error");
    }

    @GetMapping("/usuarios/{id}/editar")
    public String editar(@PathVariable("id") Long id, Model model) {
        List<Map<String, Object>> u = jdbcTemplate.queryForList(
            "SELECT id, username, correo, rol, activo FROM usuarios " +
            "WHERE id = ? AND rol IN ('doctor','enfermero','paciente')", id);
        if (u.isEmpty()) {
            return "redirect:/admin/directorio";
        }
        Map<String, Object> usuario = u.get(0);
        List<Map<String, Object>> perfil = jdbcTemplate.queryForList(
            "SELECT * FROM " + tablaPerfil((String) usuario.get("rol")) + " WHERE id_usuario = ?", id);
        model.addAttribute("usuario", usuario);
        model.addAttribute("perfil", perfil.isEmpty() ? null : perfil.get(0));
        return "admin/editar_usuario";
    }

    @PostMapping("/usuarios/{id}/actualizar")
    public String actualizar(@PathVariable("id") Long id,
                             @RequestParam("nombreCompleto") String nombreCompleto,
                             @RequestParam(value = "telefono", required = false) String telefono,
                             @RequestParam(value = "correo", required = false) String correo,
                             @RequestParam(value = "nuevaPassword", required = false) String nuevaPassword,
                             @RequestParam(value = "fechaNacimiento", required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaNacimiento,
                             @RequestParam(value = "tipoSangre", required = false) String tipoSangre,
                             @RequestParam(value = "cvResumen", required = false) String cvResumen,
                             Authentication authentication, HttpServletRequest request) {
        List<String> roles = jdbcTemplate.queryForList(
            "SELECT rol FROM usuarios WHERE id = ? AND rol IN ('doctor','enfermero','paciente')", String.class, id);
        if (roles.isEmpty()) {
            return "redirect:/admin/directorio?error";
        }
        final String rol = roles.get(0);
        final String base = "redirect:/admin/usuarios/" + id + "/editar";

        final String nombre = limpiar(nombreCompleto, 150);
        final String sangre = limpiar(tipoSangre, 5);
        final String clave = (nuevaPassword == null || nuevaPassword.isEmpty()) ? null : nuevaPassword;
        if (nombre == null) {
            return base + "?error=datos";
        }
        if (clave != null && clave.length() < PASSWORD_MIN) {
            return base + "?error=clave";
        }
        if (sangre != null && !TIPO_SANGRE.matcher(sangre).matches()) {
            return base + "?error=sangre";
        }
        if (fechaNacimiento != null && fechaNacimiento.isAfter(HoraBolivia.hoy())) {
            return base + "?error=fecha";
        }

        try {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE usuarios SET correo = ? WHERE id = ?", limpiar(correo, 100), id);
                if (clave != null) {
                    jdbcTemplate.update(
                        "UPDATE usuarios SET password = ?, intentos_fallidos = 0, bloqueado_hasta = NULL WHERE id = ?",
                        passwordEncoder.encode(clave), id);
                }
                switch (rol) {
                    case "paciente" -> jdbcTemplate.update(
                        "UPDATE pacientes SET nombre_completo = ?, fecha_nacimiento = ?, telefono = ?, tipo_sangre = ? " +
                        "WHERE id_usuario = ?",
                        nombre, fechaNacimiento == null ? null : java.sql.Date.valueOf(fechaNacimiento),
                        limpiar(telefono, 20), sangre, id);
                    case "doctor" -> jdbcTemplate.update(
                        "UPDATE doctores SET nombre_completo = ?, telefono = ?, cv_resumen = ? WHERE id_usuario = ?",
                        nombre, limpiar(telefono, 20), limpiar(cvResumen, 5000), id);
                    default -> jdbcTemplate.update(
                        "UPDATE enfermeros SET nombre_completo = ?, telefono = ? WHERE id_usuario = ?",
                        nombre, limpiar(telefono, 20), id);
                }
            });
            auditar(authentication.getName(), "EDITAR_USUARIO", "usuarios", id, request);
            return "redirect:/admin/directorio?actualizado";
        } catch (DuplicateKeyException e) {
            return base + "?error=duplicado";
        } catch (Exception e) {
            log.error("Error al actualizar el usuario {}", id, e);
            return base + "?error=db";
        }
    }
    /** Sube o reemplaza el CV en PDF de un doctor o enfermero existente. {id} es usuarios.id. */
    @PostMapping("/usuarios/{id}/cv")
    public String subirCv(@PathVariable("id") Long id,
                          @RequestParam("cvPdf") MultipartFile cvPdf,
                          Authentication authentication, HttpServletRequest request) {
        if (contar("SELECT COUNT(*) FROM doctores WHERE id_usuario = ?", id)
                + contar("SELECT COUNT(*) FROM enfermeros WHERE id_usuario = ?", id) == 0) {
            return "redirect:/admin/directorio?error";
        }
        final byte[] nueva;
        try {
            nueva = almacenArchivos.validarCv(cvPdf);
        } catch (ArchivoInvalidoException e) {
            return "redirect:/admin/directorio?error=cv";
        } catch (IOException e) {
            log.error("Error al guardar el CV del usuario {}", id, e);
            return "redirect:/admin/directorio?error";
        }
        try {
            int n = jdbcTemplate.update(
                "UPDATE doctores SET cv_pdf_ruta = 'db', cv_pdf_data = ? WHERE id_usuario = ?", nueva, id);
            if (n == 0) {
                jdbcTemplate.update(
                    "UPDATE enfermeros SET cv_pdf_ruta = 'db', cv_pdf_data = ? WHERE id_usuario = ?", nueva, id);
            }
            auditar(authentication.getName(), "SUBIR_CV", "usuarios", id, request);
            return "redirect:/admin/directorio?actualizado";
        } catch (Exception e) {
            log.error("Error al registrar el CV del usuario {}", id, e);
            return "redirect:/admin/directorio?error";
        }
    }
    // ------------------------------------------------------------------ ASISTENCIA

    @GetMapping("/asistencia")
    public String asistencia(Model model) {
        model.addAttribute("hoyTexto", HoraBolivia.hoyTexto());
        model.addAttribute("asistenciaHoy", asistenciaDeHoy());
        return "admin/asistencia";
    }

    /** Botón de cada día: marca (o corrige) la asistencia de hoy del doctor/enfermero. */
    @PostMapping("/asistencia/marcar")
    public String marcarAsistencia(@RequestParam("idUsuario") Long idUsuario,
                                   @RequestParam("estado") String estado,
                                   @RequestParam(value = "observacion", required = false) String observacion,
                                   Authentication authentication, HttpServletRequest request) {
        if (!ESTADOS_ASISTENCIA.contains(estado) || contar(
                "SELECT COUNT(*) FROM usuarios WHERE id = ? AND rol IN ('doctor','enfermero') AND activo = 1",
                idUsuario) == 0) {
            return "redirect:/admin/asistencia?error";
        }
        Integer idAdmin = jdbcTemplate.queryForObject(
            "SELECT id FROM usuarios WHERE username = ?", Integer.class, authentication.getName());
        String obs = limpiar(observacion, 255);

        try {
            java.sql.Date fecha = java.sql.Date.valueOf(HoraBolivia.hoy());
            java.sql.Time hora = java.sql.Time.valueOf(HoraBolivia.ahora());
            int n = jdbcTemplate.update(
                "UPDATE asistencia SET estado = ?, hora_registro = ?, observacion = ?, id_admin_registro = ? " +
                "WHERE id_usuario = ? AND fecha = ?", estado, hora, obs, idAdmin, idUsuario, fecha);
            if (n == 0) {
                jdbcTemplate.update(
                    "INSERT INTO asistencia (id_usuario, fecha, hora_registro, estado, observacion, id_admin_registro) " +
                    "VALUES (?, ?, ?, ?, ?, ?)", idUsuario, fecha, hora, estado, obs, idAdmin);
            }
            auditar(authentication.getName(), "MARCAR_ASISTENCIA", "asistencia", idUsuario, request);
            return "redirect:/admin/asistencia?marcado";
        } catch (Exception e) {
            log.error("Error al marcar asistencia del usuario {}", idUsuario, e);
            return "redirect:/admin/asistencia?error";
        }
    }

    @GetMapping("/asistencia/registro")
    public String registroAsistencia(
            @RequestParam(value = "desde", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(value = "hasta", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            Model model) {
        LocalDate fin = hasta != null ? hasta : HoraBolivia.hoy();
        LocalDate ini = desde != null ? desde : fin.minusDays(30);

        model.addAttribute("desde", ini);
        model.addAttribute("hasta", fin);
        model.addAttribute("registros", jdbcTemplate.queryForList(
            "SELECT DATE_FORMAT(a.fecha, '%d/%m/%Y') AS fecha, TIME_FORMAT(a.hora_registro, '%H:%i') AS hora, " +
            "a.estado, a.observacion, u.rol, " +
            "COALESCE(d.nombre_completo, e.nombre_completo) AS nombre, " +
            "COALESCE(pa.nombre_completo, ua.username) AS registrado_por " +
            "FROM asistencia a " +
            "JOIN usuarios u ON u.id = a.id_usuario " +
            "LEFT JOIN doctores d ON d.id_usuario = u.id " +
            "LEFT JOIN enfermeros e ON e.id_usuario = u.id " +
            "LEFT JOIN usuarios ua ON ua.id = a.id_admin_registro " +
            "LEFT JOIN personal_administrativo pa ON pa.id_usuario = ua.id " +
            "WHERE a.fecha BETWEEN ? AND ? ORDER BY a.fecha DESC, nombre",
            java.sql.Date.valueOf(ini), java.sql.Date.valueOf(fin)));
        return "admin/asistencia_registro";
    }
}