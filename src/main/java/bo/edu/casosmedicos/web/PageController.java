package bo.edu.casosmedicos.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String index(Authentication authentication) {
        if (authentication == null) {
            return "redirect:/login";
        }
        for (GrantedAuthority autoridad : authentication.getAuthorities()) {
            switch (autoridad.getAuthority()) {
                case "ceo"       -> { return "redirect:/ceo/dashboard"; }
                case "doctor"    -> { return "redirect:/medico/atencion"; }
                case "paciente"  -> { return "redirect:/paciente/portal"; }
                case "enfermero" -> { return "redirect:/enfermeria/inicio"; }
                case "admin"     -> { return "redirect:/admin/registro_paciente"; }
                default -> { }
            }
        }
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
