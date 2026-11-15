package bo.edu.casosmedicos.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.core.NestedExceptionUtils;

/**
 * Si la base de datos no tiene NINGÚN administrador, crea el primero.
 * La contraseña inicial se toma de la variable de entorno ADMIN_INITIAL_PASSWORD
 * (no se deja escrita en el código).
 */
@Component
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        try {
            Integer admins = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usuarios WHERE rol = 'admin'", Integer.class);
            if (admins != null && admins > 0) {
                return;   // ya existe al menos un administrador
            }

            String initialPassword = System.getenv("ADMIN_INITIAL_PASSWORD");
            if (initialPassword == null || initialPassword.isBlank()) {
                log.warn("No hay administradores y falta la variable ADMIN_INITIAL_PASSWORD: no se creó ninguno.");
                return;
            }

            jdbcTemplate.update(
                "INSERT INTO usuarios (username, correo, password, rol) VALUES (?, ?, ?, 'admin')",
                "admin", "admin@luxehealth.com", passwordEncoder.encode(initialPassword));
            jdbcTemplate.update(
                "INSERT INTO personal_administrativo (id_usuario, nombre_completo, cargo) " +
                "VALUES ((SELECT id FROM usuarios WHERE username = 'admin'), 'Administrador General', 'Administrador')");
            log.info("Administrador inicial creado (usuario: admin).");
        } catch (Exception e) {
            Throwable causa = NestedExceptionUtils.getMostSpecificCause(e);
            log.error("AdminBootstrap no pudo ejecutarse. Causa real: {}", causa.getMessage(), e);
        }
    }
}