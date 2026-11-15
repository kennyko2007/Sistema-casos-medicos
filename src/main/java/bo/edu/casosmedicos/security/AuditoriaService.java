package bo.edu.casosmedicos.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Guarda en auditoria_accesos quién hizo qué (login, accesos a PDFs, etc.).
 */
@Service
public class AuditoriaService {

    private final JdbcTemplate jdbcTemplate;

    public AuditoriaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Registra una acción buscando al usuario por su username (si no existe, id_usuario queda NULL). */
    public void registrarPorUsername(String username, String accion, String ip) {
        jdbcTemplate.update(
            "INSERT INTO auditoria_accesos (id_usuario, accion, ip) " +
            "VALUES ((SELECT id FROM usuarios WHERE username = ?), ?, ?)",
            username, accion, ip
        );
    }

    /** Registra una acción sobre una entidad concreta (ej. VER_PDF sobre una atención). */
    public void registrar(Integer idUsuario, String accion, String entidad, Integer idEntidad, String ip) {
        jdbcTemplate.update(
            "INSERT INTO auditoria_accesos (id_usuario, accion, entidad, id_entidad, ip) VALUES (?, ?, ?, ?, ?)",
            idUsuario, accion, entidad, idEntidad, ip
        );
    }
}