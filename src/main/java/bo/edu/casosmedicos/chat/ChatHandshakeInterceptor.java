package bo.edu.casosmedicos.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_CASO = "idCaso";
    public static final String ATTR_ROL = "rol";
    public static final String ATTR_USUARIO = "usuario";

    private static final String SQL_DOCTOR =
        "SELECT COUNT(*) FROM casos_medicos cm " +
        "JOIN doctores d ON d.id = cm.id_doctor JOIN usuarios u ON u.id = d.id_usuario " +
        "WHERE cm.id = ? AND u.username = ?";

    private static final String SQL_PACIENTE =
        "SELECT COUNT(*) FROM casos_medicos cm " +
        "JOIN pacientes p ON p.id = cm.id_paciente JOIN usuarios u ON u.id = p.id_usuario " +
        "WHERE cm.id = ? AND u.username = ?";

    private final JdbcTemplate jdbcTemplate;

    public ChatHandshakeInterceptor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request.getPrincipal() instanceof Authentication auth) || !auth.isAuthenticated()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // El rol sale de la sesión de login, nunca del navegador
        String rol = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.equals("doctor") || a.equals("paciente"))
            .findFirst().orElse(null);
        if (rol == null) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        Long idCaso;
        try {
            String valor = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("caso");
            idCaso = Long.valueOf(valor);
        } catch (NumberFormatException e) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        // El caso debe pertenecer a este usuario (misma regla que los controllers)
        Integer n = jdbcTemplate.queryForObject(
            rol.equals("doctor") ? SQL_DOCTOR : SQL_PACIENTE, Integer.class, idCaso, auth.getName());
        if (n == null || n == 0) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_CASO, idCaso);
        attributes.put(ATTR_ROL, rol);
        attributes.put(ATTR_USUARIO, auth.getName());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nada
    }
}