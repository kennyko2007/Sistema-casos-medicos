package bo.edu.casosmedicos.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/paciente")
public class PacienteController {

    private static final int MAX_MENSAJE = 1000;

    private final JdbcTemplate jdbcTemplate;

    public PacienteController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Perfil del paciente a partir del username con el que inició sesión. */
    private Map<String, Object> buscarPaciente(String username) {
        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
            "SELECT p.id, p.nombre_completo, p.fecha_nacimiento, p.telefono, p.tipo_sangre " +
            "FROM pacientes p JOIN usuarios u ON u.id = p.id_usuario " +
            "WHERE u.username = ?", username);
        return filas.isEmpty() ? null : filas.get(0);
    }

    /** Devuelve el caso solo si pertenece al paciente (evita ver casos ajenos cambiando el id en la URL). */
    private Map<String, Object> buscarCasoDelPaciente(Long idCaso, Object idPaciente) {
        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
            "SELECT cm.id, cm.titulo_caso, cm.estado, d.nombre_completo AS nombre_doctor " +
            "FROM casos_medicos cm JOIN doctores d ON d.id = cm.id_doctor " +
            "WHERE cm.id = ? AND cm.id_paciente = ?", idCaso, idPaciente);
        return filas.isEmpty() ? null : filas.get(0);
    }

    @GetMapping("/portal")
    public String portal(Authentication authentication, Model model) {
        Map<String, Object> paciente = buscarPaciente(authentication.getName());
        if (paciente == null) {
            model.addAttribute("error", "No se encontró información del paciente.");
            return "paciente/portal";
        }
        Object idPaciente = paciente.get("id");

        List<Map<String, Object>> casos = jdbcTemplate.queryForList(
            "SELECT cm.id, cm.titulo_caso, cm.estado, cm.diagnostico, cm.fecha_creacion, " +
            "d.nombre_completo AS nombre_doctor, " +
            "(SELECT COUNT(*) FROM mensajes_chat m WHERE m.id_caso = cm.id " +
            "   AND m.remitente = 'doctor' AND m.leido = 0) AS no_leidos " +
            "FROM casos_medicos cm JOIN doctores d ON d.id = cm.id_doctor " +
            "WHERE cm.id_paciente = ? ORDER BY cm.fecha_creacion DESC", idPaciente);

        // Una fila por atención: PDF + recomendación ("Sin recomendaciones" si está vacía)
        List<Map<String, Object>> historial = jdbcTemplate.queryForList(
            "SELECT * FROM v_historial_paciente WHERE id_paciente = ? ORDER BY fecha_atencion DESC",
            idPaciente);

        // Medicamentos agrupados por atención: medicamentos[id_atencion] -> lista
        List<Map<String, Object>> filasMed = jdbcTemplate.queryForList(
            "SELECT m.id_atencion, m.nombre, m.dosis, m.frecuencia, m.duracion, m.indicaciones " +
            "FROM medicamentos_recetados m " +
            "JOIN atenciones a ON a.id = m.id_atencion " +
            "JOIN casos_medicos c ON c.id = a.id_caso " +
            "WHERE c.id_paciente = ? ORDER BY a.fecha_atencion DESC, m.id", idPaciente);
        Map<Object, List<Map<String, Object>>> medicamentos = filasMed.stream()
            .collect(Collectors.groupingBy(f -> f.get("id_atencion"), LinkedHashMap::new, Collectors.toList()));

        model.addAttribute("paciente", paciente);
        model.addAttribute("casos", casos);
        model.addAttribute("historial", historial);
        model.addAttribute("medicamentos", medicamentos);
        return "paciente/portal";
    }
    @GetMapping("/casos_clinicos")
    public String casosClinicos(Authentication authentication, Model model) {
        Map<String, Object> paciente = buscarPaciente(authentication.getName());
        if (paciente == null) {
            model.addAttribute("error", "No se encontró información del paciente.");
            return "paciente/casos_clinicos";
        }
        Object idPaciente = paciente.get("id");

        // Una fila por atención: PDF + recomendación ("Sin recomendaciones" si está vacía)
        List<Map<String, Object>> historial = jdbcTemplate.queryForList(
            "SELECT * FROM v_historial_paciente WHERE id_paciente = ? ORDER BY fecha_atencion DESC",
            idPaciente);

        // Medicamentos agrupados por atención: medicamentos[id_atencion] -> lista
        List<Map<String, Object>> filasMed = jdbcTemplate.queryForList(
            "SELECT m.id_atencion, m.nombre, m.dosis, m.frecuencia, m.duracion, m.indicaciones " +
            "FROM medicamentos_recetados m " +
            "JOIN atenciones a ON a.id = m.id_atencion " +
            "JOIN casos_medicos c ON c.id = a.id_caso " +
            "WHERE c.id_paciente = ? ORDER BY a.fecha_atencion DESC, m.id", idPaciente);
        Map<Object, List<Map<String, Object>>> medicamentos = filasMed.stream()
            .collect(Collectors.groupingBy(f -> f.get("id_atencion"), LinkedHashMap::new, Collectors.toList()));

        model.addAttribute("paciente", paciente);
        model.addAttribute("historial", historial);
        model.addAttribute("medicamentos", medicamentos);
        return "paciente/casos_clinicos";
    }

    @GetMapping("/chat/{idCaso}")
    public String verChatCaso(@PathVariable("idCaso") Long idCaso,
                              Authentication authentication, Model model) {
        Map<String, Object> paciente = buscarPaciente(authentication.getName());
        if (paciente == null) {
            return "redirect:/paciente/portal";
        }
        Map<String, Object> caso = buscarCasoDelPaciente(idCaso, paciente.get("id"));
        if (caso == null) {
            return "redirect:/paciente/portal";
        }

        // Los mensajes del doctor pasan a "leídos" al abrir la conversación
        jdbcTemplate.update(
            "UPDATE mensajes_chat SET leido = 1 WHERE id_caso = ? AND remitente = 'doctor' AND leido = 0",
            idCaso);

        List<Map<String, Object>> mensajes = jdbcTemplate.queryForList(
            "SELECT remitente, mensaje, fecha_envio FROM mensajes_chat " +
            "WHERE id_caso = ? ORDER BY fecha_envio ASC, id ASC", idCaso);

        model.addAttribute("paciente", paciente);
        model.addAttribute("caso", caso);
        model.addAttribute("mensajes", mensajes);
        return "paciente/chat_medico";
    }
        /** Enlace del navbar: abre el chat del caso con actividad más reciente. */
    @GetMapping("/chat")
    public String chatReciente(Authentication authentication) {
        Map<String, Object> paciente = buscarPaciente(authentication.getName());
        if (paciente == null) {
            return "redirect:/paciente/portal";
        }
        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
            "SELECT cm.id FROM casos_medicos cm WHERE cm.id_paciente = ? " +
            "ORDER BY COALESCE((SELECT MAX(m.fecha_envio) FROM mensajes_chat m WHERE m.id_caso = cm.id), " +
            "cm.fecha_creacion) DESC, cm.id DESC LIMIT 1", paciente.get("id"));
        if (filas.isEmpty()) {
            return "redirect:/paciente/portal";
        }
        return "redirect:/paciente/chat/" + filas.get(0).get("id");
    }

    @PostMapping("/chat/enviar")
    public String enviarMensaje(@RequestParam("idCaso") Long idCaso,
                                @RequestParam("mensaje") String mensaje,
                                Authentication authentication) {
        Map<String, Object> paciente = buscarPaciente(authentication.getName());
        if (paciente == null || buscarCasoDelPaciente(idCaso, paciente.get("id")) == null) {
            return "redirect:/paciente/portal";
        }
        String texto = mensaje == null ? "" : mensaje.trim();
        if (!texto.isEmpty()) {
            if (texto.length() > MAX_MENSAJE) {
                texto = texto.substring(0, MAX_MENSAJE);
            }
            jdbcTemplate.update(
                "INSERT INTO mensajes_chat (id_caso, remitente, mensaje) VALUES (?, 'paciente', ?)",
                idCaso, texto);
        }
        return "redirect:/paciente/chat/" + idCaso;
    }
}