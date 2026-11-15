package bo.edu.casosmedicos.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consultas compartidas por la campanita de avisos.
 * El rol siempre proviene de la sesión autenticada, nunca del navegador.
 */
@Component
public class NotificacionesService {

    private static final String SQL_DOCTOR =
        "SELECT cm.id AS idCaso, p.nombre_completo AS nombre, cm.titulo_caso AS titulo, " +
        "(SELECT COUNT(*) FROM mensajes_chat m WHERE m.id_caso = cm.id " +
        "   AND m.remitente = 'paciente' AND m.leido = 0) AS noLeidos, " +
        "(SELECT m.mensaje FROM mensajes_chat m WHERE m.id_caso = cm.id " +
        "   ORDER BY m.fecha_envio DESC, m.id DESC LIMIT 1) AS ultimo, " +
        "(SELECT m.id FROM mensajes_chat m WHERE m.id_caso = cm.id " +
        "   ORDER BY m.fecha_envio DESC, m.id DESC LIMIT 1) AS ultimoId, " +
        "(SELECT DATE_FORMAT(m.fecha_envio, '%d/%m %H:%i') FROM mensajes_chat m " +
        "   WHERE m.id_caso = cm.id ORDER BY m.fecha_envio DESC, m.id DESC LIMIT 1) AS hora " +
        "FROM casos_medicos cm " +
        "JOIN pacientes p ON p.id = cm.id_paciente " +
        "JOIN doctores d ON d.id = cm.id_doctor " +
        "JOIN usuarios u ON u.id = d.id_usuario " +
        "WHERE u.username = ? " +
        "AND EXISTS (SELECT 1 FROM mensajes_chat mx WHERE mx.id_caso = cm.id) " +
        "ORDER BY noLeidos DESC, ultimoId DESC LIMIT 10";

    private static final String SQL_PACIENTE =
        "SELECT cm.id AS idCaso, d.nombre_completo AS nombre, cm.titulo_caso AS titulo, " +
        "(SELECT COUNT(*) FROM mensajes_chat m WHERE m.id_caso = cm.id " +
        "   AND m.remitente = 'doctor' AND m.leido = 0) AS noLeidos, " +
        "(SELECT m.mensaje FROM mensajes_chat m WHERE m.id_caso = cm.id " +
        "   ORDER BY m.fecha_envio DESC, m.id DESC LIMIT 1) AS ultimo, " +
        "(SELECT m.id FROM mensajes_chat m WHERE m.id_caso = cm.id " +
        "   ORDER BY m.fecha_envio DESC, m.id DESC LIMIT 1) AS ultimoId, " +
        "(SELECT DATE_FORMAT(m.fecha_envio, '%d/%m %H:%i') FROM mensajes_chat m " +
        "   WHERE m.id_caso = cm.id ORDER BY m.fecha_envio DESC, m.id DESC LIMIT 1) AS hora " +
        "FROM casos_medicos cm " +
        "JOIN pacientes p ON p.id = cm.id_paciente " +
        "JOIN doctores d ON d.id = cm.id_doctor " +
        "JOIN usuarios u ON u.id = p.id_usuario " +
        "WHERE u.username = ? " +
        "AND EXISTS (SELECT 1 FROM mensajes_chat mx WHERE mx.id_caso = cm.id) " +
        "ORDER BY noLeidos DESC, ultimoId DESC LIMIT 10";

    private static final String SQL_TOTAL_DOCTOR =
        "SELECT COUNT(*) FROM mensajes_chat m " +
        "JOIN casos_medicos cm ON cm.id = m.id_caso " +
        "JOIN doctores d ON d.id = cm.id_doctor " +
        "JOIN usuarios u ON u.id = d.id_usuario " +
        "WHERE u.username = ? AND m.remitente = 'paciente' AND m.leido = 0";

    private static final String SQL_TOTAL_PACIENTE =
        "SELECT COUNT(*) FROM mensajes_chat m " +
        "JOIN casos_medicos cm ON cm.id = m.id_caso " +
        "JOIN pacientes p ON p.id = cm.id_paciente " +
        "JOIN usuarios u ON u.id = p.id_usuario " +
        "WHERE u.username = ? AND m.remitente = 'doctor' AND m.leido = 0";

    private final JdbcTemplate jdbcTemplate;

    public NotificacionesService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Resumen listo para serializar a JSON y pintar la campanita. */
    public Map<String, Object> resumen(String rol, String username) {
        boolean doctor = "doctor".equals(rol);

        Integer total = jdbcTemplate.queryForObject(
            doctor ? SQL_TOTAL_DOCTOR : SQL_TOTAL_PACIENTE, Integer.class, username);

        List<Map<String, Object>> conversaciones = jdbcTemplate.query(
            doctor ? SQL_DOCTOR : SQL_PACIENTE,
            (rs, i) -> {
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("idCaso", rs.getLong("idCaso"));
                fila.put("nombre", rs.getString("nombre"));
                fila.put("titulo", rs.getString("titulo"));
                fila.put("noLeidos", rs.getInt("noLeidos"));
                fila.put("ultimo", rs.getString("ultimo"));
                fila.put("ultimoId", rs.getLong("ultimoId"));
                fila.put("hora", rs.getString("hora"));
                return fila;
            },
            username);

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("tipo", "resumen");
        salida.put("total", total == null ? 0 : total);
        salida.put("conversaciones", conversaciones);
        return salida;
    }

    /** Usuario que debe recibir el aviso cuando escribe la contraparte del caso. */
    public String usuarioContraparte(Long idCaso, String rol) {
        String sql = "doctor".equals(rol)
            ? "SELECT u.username FROM casos_medicos cm " +
              "JOIN pacientes p ON p.id = cm.id_paciente " +
              "JOIN usuarios u ON u.id = p.id_usuario WHERE cm.id = ?"
            : "SELECT u.username FROM casos_medicos cm " +
              "JOIN doctores d ON d.id = cm.id_doctor " +
              "JOIN usuarios u ON u.id = d.id_usuario WHERE cm.id = ?";

        List<String> filas = jdbcTemplate.queryForList(sql, String.class, idCaso);
        return filas.isEmpty() ? null : filas.get(0);
    }
}
