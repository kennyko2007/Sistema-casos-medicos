package bo.edu.casosmedicos.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseUserDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<UserDetails> usuarios = jdbcTemplate.query(
            "SELECT username, password, rol, activo, bloqueado_hasta FROM usuarios WHERE username = ?",
            (rs, i) -> {
                Timestamp bloqueadoHasta = rs.getTimestamp("bloqueado_hasta");
                boolean bloqueado = bloqueadoHasta != null
                        && bloqueadoHasta.toLocalDateTime().isAfter(LocalDateTime.now());

                return User.withUsername(rs.getString("username"))
                        .password(rs.getString("password"))      // hash BCrypt
                        .authorities(rs.getString("rol"))        // admin, ceo, doctor, enfermero, paciente
                        .disabled(!rs.getBoolean("activo"))      // deshabilitado por el administrador
                        .accountLocked(bloqueado)                // bloqueo temporal por intentos fallidos
                        .build();
            },
            username
        );

        if (usuarios.isEmpty()) {
            throw new UsernameNotFoundException("Usuario no encontrado");
        }
        return usuarios.get(0);
    }
}