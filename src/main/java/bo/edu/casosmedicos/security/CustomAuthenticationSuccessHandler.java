package bo.edu.casosmedicos.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

@Component
public class CustomAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final JdbcTemplate jdbcTemplate;
    private final AuditoriaService auditoria;

    public CustomAuthenticationSuccessHandler(JdbcTemplate jdbcTemplate, AuditoriaService auditoria) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditoria = auditoria;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String username = authentication.getName();

        // Login correcto: se reinician los intentos y el bloqueo
        jdbcTemplate.update(
            "UPDATE usuarios SET intentos_fallidos = 0, bloqueado_hasta = NULL, ultimo_acceso = ? WHERE username = ?",
            Timestamp.valueOf(LocalDateTime.now()), username);
        auditoria.registrarPorUsername(username, "LOGIN_OK", request.getRemoteAddr());

        String targetUrl = "/login?error";
        for (GrantedAuthority auth : authentication.getAuthorities()) {
            switch (auth.getAuthority()) {
                case "ceo"       -> targetUrl = "/ceo/dashboard";
                case "doctor"    -> targetUrl = "/medico/atencion";
                case "paciente"  -> targetUrl = "/paciente/portal";
                case "enfermero" -> targetUrl = "/enfermeria/inicio";            // por crear
                case "admin"     -> targetUrl = "/admin/registro_paciente";  // pasará a /admin/...
                default -> { }
            }
        }
        response.sendRedirect(targetUrl);
    }
}