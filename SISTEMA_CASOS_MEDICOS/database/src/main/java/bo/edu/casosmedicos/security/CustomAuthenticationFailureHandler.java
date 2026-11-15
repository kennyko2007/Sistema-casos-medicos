package bo.edu.casosmedicos.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Cuenta los intentos fallidos. Al llegar a 5 bloquea la cuenta 1 minuto.
 * Mientras siga fallando, cada nuevo intento (pasado el minuto) vuelve a bloquear 1 minuto
 * y se sigue mostrando el aviso de ir al centro médico.
 */
@Component
public class CustomAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final int MAX_INTENTOS = 5;
    private static final long BLOQUEO_MINUTOS = 1;

    private final JdbcTemplate jdbcTemplate;
    private final AuditoriaService auditoria;

    public CustomAuthenticationFailureHandler(JdbcTemplate jdbcTemplate, AuditoriaService auditoria) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditoria = auditoria;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String username = request.getParameter("username");
        String ip = request.getRemoteAddr();

        // Cuenta bloqueada: no se suma otro intento, solo se avisa que espere
        if (exception instanceof LockedException) {
            auditoria.registrarPorUsername(username, "LOGIN_BLOQUEADO", ip);
            response.sendRedirect("/login?bloqueado");
            return;
        }

        // Cuenta deshabilitada por el administrador
        if (exception instanceof DisabledException) {
            auditoria.registrarPorUsername(username, "LOGIN_DESHABILITADO", ip);
            response.sendRedirect("/login?deshabilitado");
            return;
        }

        // Usuario o contraseña incorrectos
        String destino = "/login?error";   // mensaje genérico
        if (username != null && !username.isBlank()) {
            List<Integer> ids = jdbcTemplate.queryForList(
                "SELECT id FROM usuarios WHERE username = ?", Integer.class, username);

            if (!ids.isEmpty()) {
                int id = ids.get(0);
                jdbcTemplate.update(
                    "UPDATE usuarios SET intentos_fallidos = intentos_fallidos + 1 WHERE id = ?", id);
                Integer intentos = jdbcTemplate.queryForObject(
                    "SELECT intentos_fallidos FROM usuarios WHERE id = ?", Integer.class, id);

                if (intentos != null && intentos >= MAX_INTENTOS) {
                    jdbcTemplate.update(
                        "UPDATE usuarios SET bloqueado_hasta = ? WHERE id = ?",
                        Timestamp.valueOf(LocalDateTime.now().plusMinutes(BLOQUEO_MINUTOS)), id);
                    destino = "/login?limite";   // "Vaya al centro médico..."
                }
            }
        }
        auditoria.registrarPorUsername(username, "LOGIN_FALLIDO", ip);
        response.sendRedirect(destino);
    }
}