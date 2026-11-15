package bo.edu.casosmedicos.archivos;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@ControllerAdvice
public class SubidaExcedidaHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String demasiadoGrande(HttpServletRequest request) {
        return request.getRequestURI().contains("/personal/guardar")
            ? "redirect:/admin/registro_personal?error=cv"
            : "redirect:/admin/directorio?error=cv";
    }
}