package bo.edu.casosmedicos.web;

import bo.edu.casosmedicos.chat.NotificacionesService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Respaldo HTTP de la campanita cuando el WebSocket no está disponible. */
@RestController
public class NotificacionesController {

    private final NotificacionesService notificaciones;

    public NotificacionesController(NotificacionesService notificaciones) {
        this.notificaciones = notificaciones;
    }

    @GetMapping("/avisos/resumen")
    public Map<String, Object> resumen(Authentication authentication) {
        String rol = rolDe(authentication);
        if (rol == null) {
            return Map.of("tipo", "resumen", "total", 0, "conversaciones", List.of());
        }
        return notificaciones.resumen(rol, authentication.getName());
    }

    private static String rolDe(Authentication auth) {
        if (auth == null) {
            return null;
        }
        for (GrantedAuthority autoridad : auth.getAuthorities()) {
            String valor = autoridad.getAuthority();
            if ("doctor".equals(valor) || "paciente".equals(valor)) {
                return valor;
            }
        }
        return null;
    }
}
