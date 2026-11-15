package bo.edu.casosmedicos.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private static final int MAX_MENSAJE = 1000;
    private static final int MAX_PAYLOAD = 4096;

    /** Sala = un caso clínico. Contiene las conexiones abiertas (paciente y doctor). */
    private final Map<Long, Set<WebSocketSession>> salas = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final NotificacionesService notificaciones;
    private final AvisosWebSocketHandler avisos;

    public ChatWebSocketHandler(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                NotificacionesService notificaciones, AvisosWebSocketHandler avisos) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.notificaciones = notificaciones;
        this.avisos = avisos;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long idCaso = idCaso(session);
        // El decorador evita errores si dos hilos envían a la misma conexión a la vez
        WebSocketSession segura = new ConcurrentWebSocketSessionDecorator(session, 10_000, 64 * 1024);
        salas.computeIfAbsent(idCaso, k -> ConcurrentHashMap.newKeySet()).add(segura);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (message.getPayloadLength() > MAX_PAYLOAD) {
            return;
        }
        JsonNode nodo;
        try {
            nodo = objectMapper.readTree(message.getPayload());
        } catch (JsonProcessingException e) {
            return;
        }

        Long idCaso = idCaso(session);
        String rol = (String) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_ROL);

        try {
            switch (nodo.path("tipo").asText("")) {
                case "mensaje" -> {
                    guardarYDifundir(idCaso, rol, nodo.path("mensaje").asText(""));
                    avisarContraparte(idCaso, rol);
                }
                case "leido" -> {
                    marcarLeidos(idCaso, rol);
                    avisos.enviarResumen(usuarioDe(session));
                }
                case "escribiendo" -> avisarEscribiendo(idCaso, rol);
                default -> { /* se ignora */ }
            }
        } catch (Exception e) {
            log.error("Error en el chat del caso {}", idCaso, e);
        }
    }

    private void guardarYDifundir(Long idCaso, String rol, String bruto) throws JsonProcessingException {
        String texto = bruto == null ? "" : bruto.trim();
        if (texto.isEmpty()) {
            return;
        }
        if (texto.length() > MAX_MENSAJE) {
            texto = texto.substring(0, MAX_MENSAJE);
        }
        final String textoFinal = texto;

        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO mensajes_chat (id_caso, remitente, mensaje) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, idCaso);
            ps.setString(2, rol);          // 'doctor' o 'paciente', deducido de la sesión
            ps.setString(3, textoFinal);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();

        String fecha = jdbcTemplate.queryForObject(
            "SELECT DATE_FORMAT(fecha_envio, '%Y-%m-%d %H:%i:%s') FROM mensajes_chat WHERE id = ?",
            String.class, id);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tipo", "mensaje");
        payload.put("id", id);
        payload.put("remitente", rol);
        payload.put("mensaje", textoFinal);
        payload.put("fecha", fecha);
        difundir(idCaso, objectMapper.writeValueAsString(payload));
    }

    /** Marca como leídos los mensajes de la otra parte. */
    private void marcarLeidos(Long idCaso, String rol) {
        String otro = rol.equals("doctor") ? "paciente" : "doctor";
        jdbcTemplate.update(
            "UPDATE mensajes_chat SET leido = 1 WHERE id_caso = ? AND remitente = ? AND leido = 0",
            idCaso, otro);
    }

    /** La campanita del destinatario se actualiza aunque no tenga el chat abierto. */
    private void avisarContraparte(Long idCaso, String rol) {
        avisos.enviarResumen(notificaciones.usuarioContraparte(idCaso, rol));
    }

    /** "Escribiendo…" solo se difunde; no se guarda en la base de datos. */
    private void avisarEscribiendo(Long idCaso, String rol) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tipo", "escribiendo");
        payload.put("remitente", rol);
        difundir(idCaso, objectMapper.writeValueAsString(payload));
    }

    private void difundir(Long idCaso, String json) {
        Set<WebSocketSession> sala = salas.get(idCaso);
        if (sala == null) {
            return;
        }
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : sala) {
            try {
                if (s.isOpen()) {
                    s.sendMessage(msg);
                }
            } catch (Exception e) {
                log.warn("No se pudo enviar a la sesión {}: {}", s.getId(), e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Set<WebSocketSession> sala = salas.get(idCaso(session));
        if (sala != null) {
            sala.removeIf(s -> s.getId().equals(session.getId()));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.debug("Error de transporte en {}: {}", session.getId(), exception.getMessage());
        session.close(CloseStatus.SERVER_ERROR);
    }

    private static Long idCaso(WebSocketSession session) {
        return (Long) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_CASO);
    }

    private static String usuarioDe(WebSocketSession session) {
        return (String) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_USUARIO);
    }
}