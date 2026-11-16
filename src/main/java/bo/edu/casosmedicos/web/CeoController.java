package bo.edu.casosmedicos.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/ceo")
public class CeoController {

    private static final Logger log = LoggerFactory.getLogger(CeoController.class);

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

    public CeoController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private int contar(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** Horarios de todos los usuarios de un rol, agrupados por id_usuario: horarios[id_usuario] -> lista. */
    private Map<Object, List<Map<String, Object>>> horariosPorUsuario(String rol) {
        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
            "SELECT h.id_usuario, h.dia_semana, TIME_FORMAT(h.hora_inicio, '%H:%i') AS hora_inicio, " +
            "TIME_FORMAT(h.hora_fin, '%H:%i') AS hora_fin " +
            "FROM horarios h JOIN usuarios u ON u.id = h.id_usuario " +
            "WHERE u.rol = ? " +
            "ORDER BY h.id_usuario, " +
            "FIELD(h.dia_semana,'Lunes','Martes','Miércoles','Jueves','Viernes','Sábado','Domingo'), h.hora_inicio",
            rol);
        return filas.stream().collect(
            Collectors.groupingBy(f -> f.get("id_usuario"), LinkedHashMap::new, Collectors.toList()));
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        try {
            model.addAttribute("resumenCasos", jdbcTemplate.queryForList(
                "SELECT estado, COUNT(*) AS total FROM casos_medicos GROUP BY estado"));
            model.addAttribute("totalCasos", contar("SELECT COUNT(*) FROM casos_medicos"));
            model.addAttribute("totalDoctores", contar(
                "SELECT COUNT(*) FROM doctores d JOIN usuarios u ON u.id = d.id_usuario WHERE u.activo = 1"));
            model.addAttribute("totalEnfermeros", contar(
                "SELECT COUNT(*) FROM enfermeros e JOIN usuarios u ON u.id = e.id_usuario WHERE u.activo = 1"));
            model.addAttribute("totalPacientes", contar(
                "SELECT COUNT(*) FROM pacientes p JOIN usuarios u ON u.id = p.id_usuario WHERE u.activo = 1"));
        } catch (Exception e) {
            log.error("Error al cargar el dashboard del CEO", e);
            model.addAttribute("error", "Error al cargar las estadísticas.");
        }
        return "ceo/dashboard";
    }

    @GetMapping("/doctores")
    public String doctores(Model model) {
        List<Map<String, Object>> doctores = jdbcTemplate.queryForList(
            "SELECT d.id, d.id_usuario, d.nombre_completo, d.telefono, d.cv_resumen, d.cv_pdf_ruta, u.activo, " +
            "(SELECT GROUP_CONCAT(e.nombre ORDER BY e.nombre SEPARATOR ', ') " +
            "   FROM doctor_especialidades de JOIN especialidades e ON e.id = de.id_especialidad " +
            "   WHERE de.id_doctor = d.id) AS especialidades " +
            "FROM doctores d JOIN usuarios u ON u.id = d.id_usuario " +
            "ORDER BY d.nombre_completo");
        model.addAttribute("doctores", doctores);
        model.addAttribute("horarios", horariosPorUsuario("doctor"));
        return "ceo/doctores";
    }

    @GetMapping("/enfermeros")
    public String enfermeros(Model model) {
        List<Map<String, Object>> enfermeros = jdbcTemplate.queryForList(
            "SELECT e.id, e.id_usuario, e.nombre_completo, e.telefono, e.cv_pdf_ruta, u.activo, " +
            "(SELECT d.nombre_completo FROM asignacion_enfermero_doctor a " +
            "   JOIN doctores d ON d.id = a.id_doctor " +
            "   WHERE a.id_enfermero = e.id AND a.activo = 1 " +
            "   ORDER BY a.asignado_en DESC LIMIT 1) AS doctor_asignado " +
            "FROM enfermeros e JOIN usuarios u ON u.id = e.id_usuario " +
            "ORDER BY e.nombre_completo");
        model.addAttribute("enfermeros", enfermeros);
        model.addAttribute("horarios", horariosPorUsuario("enfermero"));
        return "ceo/enfermeros";
    }

    @GetMapping("/pacientes")
    public String pacientes(Model model) {
        List<Map<String, Object>> pacientes = jdbcTemplate.queryForList(
            "SELECT p.id, p.nombre_completo, p.fecha_nacimiento, p.telefono, p.tipo_sangre, u.activo, " +
            "(SELECT COUNT(*) FROM casos_medicos c WHERE c.id_paciente = p.id) AS total_casos " +
            "FROM pacientes p JOIN usuarios u ON u.id = p.id_usuario " +
            "ORDER BY p.nombre_completo");
        model.addAttribute("pacientes", pacientes);
        return "ceo/pacientes";
    }

    @GetMapping("/pacientes/{id}")
    public String detallePaciente(@PathVariable Long id, Model model) {
        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
            "SELECT p.id, p.nombre_completo, p.fecha_nacimiento, p.telefono, p.tipo_sangre, u.activo " +
            "FROM pacientes p JOIN usuarios u ON u.id = p.id_usuario WHERE p.id = ?", id);
        if (filas.isEmpty()) {
            return "redirect:/ceo/pacientes";
        }
        model.addAttribute("paciente", filas.get(0));
        model.addAttribute("casos", jdbcTemplate.queryForList(
            "SELECT cm.id, cm.titulo_caso, cm.descripcion_sintomas, cm.diagnostico, cm.estado, " +
            "cm.fecha_creacion, d.nombre_completo AS nombre_doctor " +
            "FROM casos_medicos cm JOIN doctores d ON d.id = cm.id_doctor " +
            "WHERE cm.id_paciente = ? ORDER BY cm.fecha_creacion DESC", id));
        model.addAttribute("historial", jdbcTemplate.queryForList(
            "SELECT * FROM v_historial_paciente WHERE id_paciente = ? ORDER BY fecha_atencion DESC", id));
        return "ceo/paciente_detalle";
    }

    @GetMapping("/asistencia")
    public String asistencia(Model model) {
        model.addAttribute("hoyTexto", HoraBolivia.hoyTexto());
        model.addAttribute("asistenciaHoy", asistenciaDeHoy());
        return "ceo/asistencia";
    }

    /** JSON para refrescar la asistencia en tiempo real (la página lo consulta cada pocos segundos). */
    @GetMapping("/asistencia/datos")
    @ResponseBody
    public List<Map<String, Object>> asistenciaDatos() {
        return asistenciaDeHoy();
    }
}