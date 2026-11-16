package bo.edu.casosmedicos.citas;

import bo.edu.casosmedicos.web.HoraBolivia;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agenda de citas entre pacientes y doctores.
 * La disponibilidad se valida contra la tabla horarios y la hora oficial de La Paz.
 */
@Service
public class CitasService {

    public static final String SOLICITADA = "Solicitada";
    public static final String CONFIRMADA = "Confirmada";
    public static final String ATENDIDA = "Atendida";
    public static final String CANCELADA = "Cancelada";

    private static final Set<String> ESTADOS = Set.of(SOLICITADA, CONFIRMADA, ATENDIDA, CANCELADA);

    public enum Resultado {
        OK, DATOS, DOCTOR, FECHA, HORARIO, OCUPADO, ESTADO
    }

    public record Solicitud(Resultado resultado, Long idCita) { }

    private final JdbcTemplate jdbcTemplate;

    public CitasService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private int contar(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String limpiar(String texto, int max) {
        if (texto == null) {
            return null;
        }
        String t = texto.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    public List<Map<String, Object>> doctores() {
        return jdbcTemplate.queryForList(
            "SELECT d.id, d.nombre_completo, " +
            "(SELECT GROUP_CONCAT(e.nombre ORDER BY e.nombre SEPARATOR ', ') " +
            "   FROM doctor_especialidades de JOIN especialidades e ON e.id = de.id_especialidad " +
            "   WHERE de.id_doctor = d.id) AS especialidades " +
            "FROM doctores d JOIN usuarios u ON u.id = d.id_usuario " +
            "WHERE u.activo = 1 ORDER BY d.nombre_completo");
    }

    public List<Map<String, Object>> citasPaciente(Object idPaciente) {
        return jdbcTemplate.queryForList(
            "SELECT c.id, DATE_FORMAT(c.fecha_hora, '%d/%m/%Y %H:%i') AS fecha_texto, " +
            "c.fecha_hora, c.motivo, c.estado, c.observaciones, d.nombre_completo AS nombre_doctor " +
            "FROM citas c JOIN doctores d ON d.id = c.id_doctor " +
            "WHERE c.id_paciente = ? " +
            "ORDER BY (c.estado IN ('Solicitada','Confirmada')) DESC, c.fecha_hora DESC",
            idPaciente);
    }

    public List<Map<String, Object>> citasDoctor(Object idDoctor) {
        return jdbcTemplate.queryForList(
            "SELECT c.id, DATE_FORMAT(c.fecha_hora, '%d/%m/%Y %H:%i') AS fecha_texto, " +
            "c.fecha_hora, c.motivo, c.estado, c.observaciones, " +
            "p.nombre_completo AS nombre_paciente, p.telefono, " +
            "TIMESTAMPDIFF(YEAR, p.fecha_nacimiento, CURDATE()) AS edad " +
            "FROM citas c JOIN pacientes p ON p.id = c.id_paciente " +
            "WHERE c.id_doctor = ? " +
            "ORDER BY (c.estado IN ('Solicitada','Confirmada') AND c.fecha_hora >= NOW()) DESC, " +
            "c.fecha_hora ASC",
            idDoctor);
    }

    public List<Map<String, Object>> citasDeHoy(Object idDoctor) {
        return jdbcTemplate.queryForList(
            "SELECT c.id, TIME_FORMAT(c.fecha_hora, '%H:%i') AS hora, c.estado, c.motivo, " +
            "p.nombre_completo AS nombre_paciente " +
            "FROM citas c JOIN pacientes p ON p.id = c.id_paciente " +
            "WHERE c.id_doctor = ? AND DATE(c.fecha_hora) = ? AND c.estado <> 'Cancelada' " +
            "ORDER BY c.fecha_hora",
            idDoctor, java.sql.Date.valueOf(HoraBolivia.hoy()));
    }

    public Solicitud solicitar(Object idPaciente, Long idDoctor, LocalDateTime fechaHora, String motivo) {
        String texto = limpiar(motivo, 500);
        if (idPaciente == null || idDoctor == null || fechaHora == null || texto == null) {
            return new Solicitud(Resultado.DATOS, null);
        }

        LocalDateTime ahora = LocalDateTime.now(HoraBolivia.ZONA).withSecond(0).withNano(0);
        if (fechaHora.isBefore(ahora) || fechaHora.isAfter(ahora.plusDays(120))
                || fechaHora.getMinute() % 15 != 0 || fechaHora.getSecond() != 0) {
            return new Solicitud(Resultado.FECHA, null);
        }

        List<Map<String, Object>> doctor = jdbcTemplate.queryForList(
            "SELECT d.id_usuario FROM doctores d JOIN usuarios u ON u.id = d.id_usuario " +
            "WHERE d.id = ? AND u.activo = 1", idDoctor);
        if (doctor.isEmpty()) {
            return new Solicitud(Resultado.DOCTOR, null);
        }

        String dia = HoraBolivia.diaSemana(fechaHora.toLocalDate());
        Time hora = Time.valueOf(fechaHora.toLocalTime());
        int enHorario = contar(
            "SELECT COUNT(*) FROM horarios WHERE id_usuario = ? AND dia_semana = ? " +
            "AND ? >= hora_inicio AND ? < hora_fin",
            doctor.get(0).get("id_usuario"), dia, hora, hora);
        if (enHorario == 0) {
            return new Solicitud(Resultado.HORARIO, null);
        }

        Timestamp fechaSql = Timestamp.valueOf(fechaHora);
        if (contar("SELECT COUNT(*) FROM citas WHERE id_doctor = ? AND fecha_hora = ? " +
                   "AND estado IN ('Solicitada','Confirmada')", idDoctor, fechaSql) > 0
                || contar("SELECT COUNT(*) FROM citas WHERE id_paciente = ? AND fecha_hora = ? " +
                          "AND estado IN ('Solicitada','Confirmada')", idPaciente, fechaSql) > 0) {
            return new Solicitud(Resultado.OCUPADO, null);
        }

        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO citas (id_paciente, id_doctor, fecha_hora, motivo, estado) " +
                "VALUES (?, ?, ?, ?, 'Solicitada')",
                Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, idPaciente);
            ps.setObject(2, idDoctor);
            ps.setTimestamp(3, fechaSql);
            ps.setString(4, texto);
            return ps;
        }, kh);

        Number id = kh.getKey();
        return new Solicitud(Resultado.OK, id == null ? null : id.longValue());
    }

    /** El doctor confirma, atiende o cancela una cita propia. */
    public boolean cambiarEstado(Long idCita, Object idDoctor, String nuevoEstado, String observacion) {
        String nuevo = limpiar(nuevoEstado, 20);
        if (idCita == null || idDoctor == null || nuevo == null || !ESTADOS.contains(nuevo)) {
            return false;
        }

        List<String> filas = jdbcTemplate.queryForList(
            "SELECT estado FROM citas WHERE id = ? AND id_doctor = ?", String.class, idCita, idDoctor);
        if (filas.isEmpty()) {
            return false;
        }
        String actual = filas.get(0);
        boolean permitido = switch (actual) {
            case SOLICITADA -> CONFIRMADA.equals(nuevo) || CANCELADA.equals(nuevo);
            case CONFIRMADA -> ATENDIDA.equals(nuevo) || CANCELADA.equals(nuevo);
            default -> false;
        };
        if (!permitido) {
            return false;
        }

        return jdbcTemplate.update(
            "UPDATE citas SET estado = ?, observaciones = COALESCE(?, observaciones) " +
            "WHERE id = ? AND id_doctor = ?",
            nuevo, limpiar(observacion, 500), idCita, idDoctor) > 0;
    }

    /** El paciente solo puede cancelar sus propias citas pendientes. */
    public boolean cancelarPaciente(Long idCita, Object idPaciente) {
        if (idCita == null || idPaciente == null) {
            return false;
        }
        return jdbcTemplate.update(
            "UPDATE citas SET estado = 'Cancelada' " +
            "WHERE id = ? AND id_paciente = ? AND estado IN ('Solicitada','Confirmada')",
            idCita, idPaciente) > 0;
    }
}
