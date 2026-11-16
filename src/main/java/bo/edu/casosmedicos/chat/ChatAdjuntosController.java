package bo.edu.casosmedicos.chat;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/** Subida y descarga de los adjuntos que viajan por el chat de un caso clínico. */
@Controller
@RequestMapping("/chat/adjunto")
public class ChatAdjuntosController {

    private final ChatAdjuntosService adjuntos;

    public ChatAdjuntosController(ChatAdjuntosService adjuntos) {
        this.adjuntos = adjuntos;
    }

    @PostMapping("/{idCaso}")
    public String subir(@PathVariable Long idCaso,
                        @RequestParam("archivo") MultipartFile archivo,
                        Authentication authentication) {
        String rol = rol(authentication);
        if (rol == null) {
            return "redirect:/login";
        }
        ChatAdjuntosService.Envio envio = adjuntos.subir(idCaso, authentication.getName(), rol, archivo);
        String destino = "doctor".equals(rol) ? "/medico/chat/" : "/paciente/chat/";
        return "redirect:" + destino + idCaso + (envio.resultado() == ChatAdjuntosService.Resultado.OK
            ? "?adjuntado" : "?error=adjunto");
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> descargar(@PathVariable Long id, Authentication authentication) {
        ChatAdjuntosService.Archivo archivo = adjuntos.buscar(id, authentication.getName(), rol(authentication));
        if (archivo == null) {
            return ResponseEntity.notFound().build();
        }

        MediaType tipo;
        try {
            tipo = MediaType.parseMediaType(archivo.tipo());
        } catch (Exception e) {
            tipo = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
            .contentType(tipo)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(archivo.nombre(), StandardCharsets.UTF_8).build().toString())
            .body(new ByteArrayResource(archivo.datos()));
    }

    private static String rol(Authentication authentication) {
        return authentication == null ? null : authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.equals("doctor") || a.equals("paciente"))
            .findFirst().orElse(null);
    }
}
