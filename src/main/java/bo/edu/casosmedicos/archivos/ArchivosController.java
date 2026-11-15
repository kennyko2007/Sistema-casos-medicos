package bo.edu.casosmedicos.archivos;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import java.util.Map;
import java.util.List;

/** Sirve los PDFs guardados fuera de static. El permiso se define en SecurityConfig. */
@Controller
@RequestMapping("/documentos")
public class ArchivosController {

    private static final Logger log = LoggerFactory.getLogger(ArchivosController.class);

    private final JdbcTemplate jdbcTemplate;
    private final AlmacenArchivos almacen;

    public ArchivosController(JdbcTemplate jdbcTemplate, AlmacenArchivos almacen) {
        this.jdbcTemplate = jdbcTemplate;
        this.almacen = almacen;
    }

    /** CV de un doctor. {idUsuario} es usuarios.id, el mismo que usan directorio y CEO. */
    @GetMapping("/cv/{idUsuario}")
    public ResponseEntity<Resource> verCv(@PathVariable("idUsuario") Long idUsuario,
                                          Authentication authentication, HttpServletRequest request) {
        List<byte[]> cvs = jdbcTemplate.queryForList(
            "SELECT cv_pdf_data FROM doctores WHERE id_usuario = ? AND cv_pdf_data IS NOT NULL " +
            "UNION ALL " +
            "SELECT cv_pdf_data FROM enfermeros WHERE id_usuario = ? AND cv_pdf_data IS NOT NULL",
            byte[].class, idUsuario, idUsuario);
        if (cvs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Resource pdf = new ByteArrayResource(cvs.get(0));
        auditar(authentication.getName(), "VER_CV", "usuarios", idUsuario, request);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"cv.pdf\"")
            .body(pdf);
    }

        /**
     * PDF de la historia clínica de una atención. El acceso depende del rol y de a quién pertenece el caso:
     * CEO: todos · doctor: sus casos · paciente: los suyos · enfermero/a: casos de su doctor asignado.
     * Si no tiene permiso se responde 404, igual que si no existiera.
     */
    @GetMapping("/atencion/{idAtencion}")
    public ResponseEntity<Resource> verAtencion(@PathVariable("idAtencion") Long idAtencion,
                                                Authentication authentication, HttpServletRequest request) {
        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
            "SELECT a.pdf_ruta, c.id_doctor, du.username AS usuario_doctor, pu.username AS usuario_paciente " +
            "FROM atenciones a " +
            "JOIN casos_medicos c ON c.id = a.id_caso " +
            "JOIN doctores d ON d.id = c.id_doctor JOIN usuarios du ON du.id = d.id_usuario " +
            "JOIN pacientes p ON p.id = c.id_paciente JOIN usuarios pu ON pu.id = p.id_usuario " +
            "WHERE a.id = ? AND a.pdf_ruta IS NOT NULL", idAtencion);
        if (filas.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> f = filas.get(0);
        String usuario = authentication.getName();

        boolean permitido;
        if (tieneRol(authentication, "ceo")) {
            permitido = true;
        } else if (tieneRol(authentication, "doctor")) {
            permitido = usuario.equals(f.get("usuario_doctor"));
        } else if (tieneRol(authentication, "paciente")) {
            permitido = usuario.equals(f.get("usuario_paciente"));
        } else if (tieneRol(authentication, "enfermero")) {
            permitido = enfermeroAsignado(usuario, f.get("id_doctor"));
        } else {
            permitido = false;
        }
        if (!permitido) {
            return ResponseEntity.notFound().build();
        }

        Resource pdf = almacen.cargar((String) f.get("pdf_ruta"));
        if (pdf == null) {
            return ResponseEntity.notFound().build();
        }
        auditar(usuario, "VER_PDF", "atenciones", idAtencion, request);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"historia-clinica-" + idAtencion + ".pdf\"")
            .body(pdf);
    }

    private static boolean tieneRol(Authentication authentication, String rol) {
        return authentication.getAuthorities().stream().anyMatch(g -> rol.equals(g.getAuthority()));
    }

    private boolean enfermeroAsignado(String username, Object idDoctor) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM asignacion_enfermero_doctor a " +
            "JOIN enfermeros e ON e.id = a.id_enfermero JOIN usuarios u ON u.id = e.id_usuario " +
            "WHERE u.username = ? AND a.id_doctor = ? AND a.activo = 1", Integer.class, username, idDoctor);
        return n != null && n > 0;
    }

    private void auditar(String actor, String accion, String entidad, Long idEntidad, HttpServletRequest request) {
        try {
            jdbcTemplate.update(
                "INSERT INTO auditoria_accesos (id_usuario, accion, entidad, id_entidad, ip) " +
                "VALUES ((SELECT id FROM usuarios WHERE username = ?), ?, ?, ?, ?)",
                actor, accion, entidad, idEntidad, request.getRemoteAddr());
        } catch (Exception e) {
            log.warn("No se pudo registrar la auditoría {}: {}", accion, e.getMessage());
        }
    }
}