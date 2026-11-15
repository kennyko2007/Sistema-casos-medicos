package bo.edu.casosmedicos.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mantiene una conexión por usuario autenticado y le empuja el resumen de
 * mensajes sin leer cada vez que cambia, aunque no tenga el chat abierto.
 */
@Component
public class AvisosWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AvisosWebSocketHandler.class);

    private final Map<String, Set<WebSocketSession>> sesiones = new ConcurrentHashMap<>();
    private final NotificacionesService notificaciones;
    private final ObjectMapper objectMapper;

    public AvisosWebSocketHandler(NotificacionesService notificaciones, ObjectMapper objectMapper) {
        this.notificaciones = notificaciones;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String usuario = (String) session.getAttributes().get(AvisosHandshakeInterceptor.ATTR_USUARIO);
        String rol = (String) session.getAttributes().get(AvisosHandshakeInterceptor.ATTR_ROL);

        if (usuario == null || rol == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        WebSocketSession segura = new ConcurrentWebSocketSessionDecorator(session, 10_000, 64 * 1024);
        sesiones.computeIfAbsent(usuario, k -> ConcurrentHashMap.newKeySet()).add(segura);
        enviar(segura, usuario, rol);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if ("ping".equals(message.getPayload())) {
            String usuario = (String) session.getAttributes().get(AvisosHandshakeInterceptor.ATTR_USUARIO);
            enviarResumen(usuario);
        }
    }

    /** Refresca la campanita de todas las pestañas abiertas del usuario. */
    public void enviarResumen(String usuario) {
        if (usuario == null) {
            return;
        }
        Set<WebSocketSession> abiertas = sesiones.get(usuario);
        if (abiertas == null) {
            return;
        }
        for (WebSocketSession session : abiertas) {
            if (!session.isOpen()) {
                continue;
            }
            String rol = (String) session.getAttributes().get(AvisosHandshakeInterceptor.ATTR_ROL);
            if (rol != null) {
                enviar(session, usuario, rol);
            }
        }
    }

    private void enviar(WebSocketSession session, String usuario, String rol) {
        try {
            String json = objectMapper.writeValueAsString(notificaciones.resumen(rol, usuario));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.debug("No se pudo enviar el resumen de avisos a {}: {}", usuario, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String usuario = (String) session.getAttributes().get(AvisosHandshakeInterceptor.ATTR_USUARIO);
        if (usuario == null) {
            return;
        }
        Set<WebSocketSession> abiertas = sesiones.get(usuario);
        if (abiertas != null) {
            abiertas.removeIf(s -> s.getId().equals(session.getId()));
            if (abiertas.isEmpty()) {
                sesiones.remove(usuario, abiertas);
            }
        }
    }
}
