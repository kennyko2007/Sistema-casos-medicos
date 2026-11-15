package bo.edu.casosmedicos.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** Canal personal de avisos: cada usuario escucha solo sus propios mensajes. */
@Component
public class AvisosHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USUARIO = "usuarioAvisos";
    public static final String ATTR_ROL = "rolAvisos";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request.getPrincipal() instanceof Authentication auth) || !auth.isAuthenticated()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String rol = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.equals("doctor") || a.equals("paciente"))
            .findFirst().orElse(null);

        if (rol == null) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_USUARIO, auth.getName());
        attributes.put(ATTR_ROL, rol);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nada
    }
}
