package bo.edu.casosmedicos.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/enfermeria")
public class EnfermeriaController {

    private final JdbcTemplate jdbcTemplate;

    public EnfermeriaController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/inicio")
    public String inicio(Authentication authentication, Model model) {
        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
            "SELECT e.id, e.id_usuario, e.nombre_completo, e.telefono " +
            "FROM enfermeros e JOIN usuarios u ON u.id = e.id_usuario " +
            "WHERE u.username = ?", authentication.getName());
        if (filas.isEmpty()) {
            model.addAttribute("error", "No se encontró información del enfermero/a.");
            return "enfermeria/inicio";
        }
        Map<String, Object> enfermero = filas.get(0);

        // Horario semanal
        List<Map<String, Object>> horarios = jdbcTemplate.queryForList(
            "SELECT dia_semana, TIME_FORMAT(hora_inicio, '%H:%i') AS hora_inicio, " +
            "TIME_FORMAT(hora_fin, '%H:%i') AS hora_fin " +
            "FROM horarios WHERE id_usuario = ? " +
            "ORDER BY FIELD(dia_semana,'Lunes','Martes','Miércoles','Jueves','Viernes','Sábado','Domingo'), hora_inicio",
            enfermero.get("id_usuario"));

        // Doctor(es) al que está asignado/a
        List<Map<String, Object>> doctores = jdbcTemplate.queryForList(
            "SELECT d.id, d.nombre_completo, d.telefono " +
            "FROM asignacion_enfermero_doctor a JOIN doctores d ON d.id = a.id_doctor " +
            "WHERE a.id_enfermero = ? AND a.activo = 1 ORDER BY d.nombre_completo", enfermero.get("id"));

        // Casos activos de su(s) doctor(es), solo lectura
        List<Map<String, Object>> casos = jdbcTemplate.queryForList(
            "SELECT cm.id, cm.titulo_caso, cm.estado, p.nombre_completo AS nombre_paciente, " +
            "d.nombre_completo AS nombre_doctor " +
            "FROM casos_medicos cm " +
            "JOIN pacientes p ON p.id = cm.id_paciente " +
            "JOIN doctores d ON d.id = cm.id_doctor " +
            "JOIN asignacion_enfermero_doctor a ON a.id_doctor = cm.id_doctor " +
            "WHERE a.id_enfermero = ? AND a.activo = 1 AND cm.estado <> 'Concluido' " +
            "ORDER BY cm.fecha_creacion", enfermero.get("id"));

        model.addAttribute("enfermero", enfermero);
        model.addAttribute("horarios", horarios);
        model.addAttribute("doctores", doctores);
        model.addAttribute("casos", casos);
                // Atenciones con PDF de los casos de su(s) doctor(es), solo lectura
        model.addAttribute("atenciones", jdbcTemplate.queryForList(
            "SELECT a.id, DATE_FORMAT(a.fecha_atencion, '%d/%m/%Y %H:%i') AS fecha, cm.titulo_caso, " +
            "p.nombre_completo AS nombre_paciente, d.nombre_completo AS nombre_doctor, " +
            "es.nombre AS especialidad " +
            "FROM atenciones a " +
            "JOIN casos_medicos cm ON cm.id = a.id_caso " +
            "JOIN pacientes p ON p.id = cm.id_paciente " +
            "JOIN doctores d ON d.id = cm.id_doctor " +
            "JOIN especialidades es ON es.id = a.id_especialidad " +
            "JOIN asignacion_enfermero_doctor x ON x.id_doctor = cm.id_doctor " +
            "WHERE x.id_enfermero = ? AND x.activo = 1 AND a.pdf_ruta IS NOT NULL " +
            "ORDER BY a.fecha_atencion DESC LIMIT 50", enfermero.get("id")));
        return "enfermeria/inicio";
    }
}