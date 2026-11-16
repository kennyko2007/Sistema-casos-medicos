package bo.edu.casosmedicos.archivos;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@ControllerAdvice
public class SubidaExcedidaHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String demasiadoGrande(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/chat/adjunto/")) {
            String idCaso = uri.substring("/chat/adjunto/".length());
            String base = request.isUserInRole("doctor") ? "/medico/chat/" : "/paciente/chat/";
            return "redirect:" + base + idCaso + "?error=adjunto";
        }
        return uri.contains("/personal/guardar")
            ? "redirect:/admin/registro_personal?error=cv"
            : "redirect:/admin/directorio?error=cv";
    }
}