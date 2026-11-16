package bo.edu.casosmedicos.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Adjuntos del chat: se guardan en la propia fila de mensajes_chat (MEDIUMBLOB),
 * así sobreviven a los reinicios de Render, donde el disco es efímero.
 */
@Service
public class ChatAdjuntosService {

    private static final Logger log = LoggerFactory.getLogger(ChatAdjuntosService.class);

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    public enum Resultado { OK, DATOS, TAMANO, TIPO, CASO }

    public record Envio(Resultado resultado, Long id, Long idCaso,
                        String nombre, String tipo, String fecha) { }

    public record Archivo(String nombre, String tipo, byte[] datos) { }

    private final JdbcTemplate jdbcTemplate;
    private final ChatWebSocketHandler chat;
    private final NotificacionesService notificaciones;
    private final AvisosWebSocketHandler avisos;

    public ChatAdjuntosService(JdbcTemplate jdbcTemplate, ChatWebSocketHandler chat,
                               NotificacionesService notificaciones, AvisosWebSocketHandler avisos) {
        this.jdbcTemplate = jdbcTemplate;
        this.chat = chat;
        this.notificaciones = notificaciones;
        this.avisos = avisos;
    }

    public Envio subir(Long idCaso, String username, String rol, MultipartFile archivo) {
        if (idCaso == null || !esDelCaso(idCaso, username, rol)) {
            return new Envio(Resultado.CASO, null, idCaso, null, null, null);
        }
        if (archivo == null || archivo.isEmpty()) {
            return new Envio(Resultado.DATOS, null, idCaso, null, null, null);
        }
        if (archivo.getSize() > MAX_BYTES) {
            return new Envio(Resultado.TAMANO, null, idCaso, null, null, null);
        }

        byte[] datos;
        try {
            datos = archivo.getBytes();
        } catch (IOException e) {
            log.warn("No se pudo leer el adjunto del caso {}: {}", idCaso, e.getMessage());
            return new Envio(Resultado.DATOS, null, idCaso, null, null, null);
        }

        String tipo = detectarTipo(datos);
        if (tipo == null) {
            return new Envio(Resultado.TIPO, null, idCaso, null, null, null);
        }
        String nombre = limpiarNombre(archivo.getOriginalFilename(), tipo);

        try {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO mensajes_chat (id_caso, remitente, mensaje, adjunto_nombre, adjunto_tipo, adjunto_datos) " +
                    "VALUES (?, ?, '', ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, idCaso);
                ps.setString(2, rol);
                ps.setString(3, nombre);
                ps.setString(4, tipo);
                ps.setBytes(5, datos);
                return ps;
            }, kh);
            long id = kh.getKey().longValue();

            String fecha = jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(fecha_envio, '%Y-%m-%d %H:%i:%s') FROM mensajes_chat WHERE id = ?",
                String.class, id);

            chat.difundirAdjunto(idCaso, id, rol, nombre, tipo, fecha);
            avisos.enviarResumen(notificaciones.usuarioContraparte(idCaso, rol));

            return new Envio(Resultado.OK, id, idCaso, nombre, tipo, fecha);
        } catch (Exception e) {
            log.error("No se pudo guardar el adjunto del caso {}", idCaso, e);
            return new Envio(Resultado.DATOS, null, idCaso, null, null, null);
        }
    }

    /** Devuelve el archivo solo si quien lo pide es el doctor del caso o su paciente. */
    public Archivo buscar(Long id, String username, String rol) {
        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
            "SELECT m.adjunto_nombre, m.adjunto_tipo, m.adjunto_datos, " +
            "du.username AS usuario_doctor, pu.username AS usuario_paciente " +
            "FROM mensajes_chat m " +
            "JOIN casos_medicos cm ON cm.id = m.id_caso " +
            "JOIN doctores d ON d.id = cm.id_doctor " +
            "JOIN usuarios du ON du.id = d.id_usuario " +
            "JOIN pacientes p ON p.id = cm.id_paciente " +
            "JOIN usuarios pu ON pu.id = p.id_usuario " +
            "WHERE m.id = ? AND m.adjunto_datos IS NOT NULL", id);
        if (filas.isEmpty()) {
            return null;
        }

        Map<String, Object> fila = filas.get(0);
        boolean permitido = "doctor".equals(rol)
            ? username.equals(fila.get("usuario_doctor"))
            : "paciente".equals(rol) && username.equals(fila.get("usuario_paciente"));
        if (!permitido) {
            return null;
        }

        Object datos = fila.get("adjunto_datos");
        if (!(datos instanceof byte[] bytes)) {
            return null;
        }
        return new Archivo((String) fila.get("adjunto_nombre"), (String) fila.get("adjunto_tipo"), bytes);
    }

    private boolean esDelCaso(Long idCaso, String username, String rol) {
        String sql;
        if ("doctor".equals(rol)) {
            sql = "SELECT COUNT(*) FROM casos_medicos cm " +
                  "JOIN doctores d ON d.id = cm.id_doctor JOIN usuarios u ON u.id = d.id_usuario " +
                  "WHERE cm.id = ? AND u.username = ?";
        } else if ("paciente".equals(rol)) {
            sql = "SELECT COUNT(*) FROM casos_medicos cm " +
                  "JOIN pacientes p ON p.id = cm.id_paciente JOIN usuarios u ON u.id = p.id_usuario " +
                  "WHERE cm.id = ? AND u.username = ?";
        } else {
            return false;
        }
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, idCaso, username);
        return n != null && n > 0;
    }

    /** El tipo se decide por los bytes del archivo, no por lo que declara el navegador. */
    private static String detectarTipo(byte[] d) {
        if (d.length >= 5 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F' && d[4] == '-') {
            return "application/pdf";
        }
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && (d[4] & 0xFF) == 0x0D && (d[5] & 0xFF) == 0x0A
                && (d[6] & 0xFF) == 0x1A && (d[7] & 0xFF) == 0x0A) {
            return "image/png";
        }
        return null;
    }

    private static String limpiarNombre(String original, String tipo) {
        String base = original == null ? "" : original.replace('\\', '/');
        int corte = base.lastIndexOf('/');
        if (corte >= 0) {
            base = base.substring(corte + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}<>:\"|?*]", "").trim();
        if (base.isEmpty()) {
            base = switch (tipo) {
                case "application/pdf" -> "documento.pdf";
                case "image/png" -> "imagen.png";
                default -> "imagen.jpg";
            };
        }
        return base.length() > 180 ? base.substring(base.length() - 180) : base;
    }
}
