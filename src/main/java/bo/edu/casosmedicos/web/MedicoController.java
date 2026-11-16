package bo.edu.casosmedicos.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import bo.edu.casosmedicos.pdf.AtencionPdfService;
import bo.edu.casosmedicos.chat.AvisosWebSocketHandler;
import bo.edu.casosmedicos.chat.NotificacionesService;
import bo.edu.casosmedicos.citas.CitasService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/medico")
public class MedicoController {

    private static final Logger log = LoggerFactory.getLogger(MedicoController.class);

    /** Especialidades que ya tienen plantilla de registro. */
    private static final Set<String> PLANTILLAS = Set.of("Medicina General", "Cardiología", "Neurología");

    /** Los campos dinámicos del formulario llegan como f_presion, f_pulso, f_temperatura... */
    private static final Pattern CLAVE_FORMULARIO = Pattern.compile("^[A-Za-z0-9_]{1,40}$");

    private static final int MAX_MENSAJE = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final AtencionPdfService pdfService;
    private final NotificacionesService notificaciones;
    private final AvisosWebSocketHandler avisos;
    private final CitasService citas;

    public MedicoController(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                            PlatformTransactionManager txManager, AtencionPdfService pdfService,
                            NotificacionesService notificaciones, AvisosWebSocketHandler avisos,
                            CitasService citas) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.pdfService = pdfService;
        this.notificaciones = notificaciones;
        this.avisos = avisos;
        this.citas = citas;
    }

    // ------------------------------------------------------------------ utilidades

    private Map<String, Object> buscarDoctor(String username) {
        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
            "SELECT d.id, d.id_usuario, d.nombre_completo, d.telefono " +
            "FROM doctores d JOIN usuarios u ON u.id = d.id_usuario WHERE u.username = ?", username);
        return filas.isEmpty() ? null : filas.get(0);
    }

    private int contar(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String limpiar(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String valorEn(MultiValueMap<String, String> params, String clave, int i) {
        List<String> lista = params.get(clave);
        return (lista != null && i < lista.size()) ? lista.get(i) : null;
    }

    private String aJson(Map<String, String> datos) {
        if (datos.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(datos);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el formulario", e);
        }
    }

    private List<Map<String, Object>> conversaciones(Object idDoctor) {
        return jdbcTemplate.queryForList(
            "SELECT cm.id AS id_caso, cm.titulo_caso, p.nombre_completo AS nombre_paciente, " +
            "(SELECT CASE WHEN m.adjunto_nombre IS NOT NULL THEN CONCAT('Adjunto: ', m.adjunto_nombre) " +
            "   ELSE m.mensaje END FROM mensajes_chat m WHERE m.id_caso = cm.id " +
            "   ORDER BY m.fecha_envio DESC, m.id DESC LIMIT 1) AS ultimo_mensaje, " +
            "(SELECT MAX(m.fecha_envio) FROM mensajes_chat m WHERE m.id_caso = cm.id) AS ultima_fecha, " +
            "(SELECT COUNT(*) FROM mensajes_chat m WHERE m.id_caso = cm.id " +
            "   AND m.remitente = 'paciente' AND m.leido = 0) AS no_leidos " +
            "FROM casos_medicos cm JOIN pacientes p ON p.id = cm.id_paciente " +
            "WHERE cm.id_doctor = ? ORDER BY ultima_fecha DESC", idDoctor);
    }

    // ------------------------------------------------------------------ ATENCIÓN

    @GetMapping("/atencion")
    public String atencion(@RequestParam(value = "caso", required = false) Long idCaso,
                           @RequestParam(value = "esp", required = false) Integer idEsp,
                           Authentication authentication, Model model) {
        Map<String, Object> doctor = buscarDoctor(authentication.getName());
        if (doctor == null) {
            model.addAttribute("error", "No se encontró información del médico.");
            return "medico/atencion";
        }
        Object idDoctor = doctor.get("id");

        // Especialidades del doctor que tienen plantilla (siempre incluye Medicina General)
        List<Map<String, Object>> especialidades = jdbcTemplate.queryForList(
            "SELECT e.id, e.nombre FROM doctor_especialidades de " +
            "JOIN especialidades e ON e.id = de.id_especialidad " +
            "WHERE de.id_doctor = ? ORDER BY e.nombre", idDoctor)
            .stream().filter(e -> PLANTILLAS.contains(e.get("nombre"))).toList();

        // Casos activos del doctor
        List<Map<String, Object>> casos = jdbcTemplate.queryForList(
            "SELECT cm.id, cm.titulo_caso, cm.descripcion_sintomas, cm.diagnostico, cm.estado, " +
            "cm.prioridad, cm.fecha_creacion, p.id AS id_paciente, p.nombre_completo AS nombre_paciente, " +
            "p.telefono, p.tipo_sangre, TIMESTAMPDIFF(YEAR, p.fecha_nacimiento, CURDATE()) AS edad " +
            "FROM casos_medicos cm JOIN pacientes p ON p.id = cm.id_paciente " +
            "WHERE cm.id_doctor = ? AND cm.estado <> 'Concluido' ORDER BY cm.fecha_creacion", idDoctor);

        Map<String, Object> casoActual = null;
        for (Map<String, Object> c : casos) {
            if (idCaso != null && ((Number) c.get("id")).longValue() == idCaso) {
                casoActual = c;
                break;
            }
        }
        if (casoActual == null && !casos.isEmpty()) {
            casoActual = casos.get(0);
        }

        Map<String, Object> especialidadActual = null;
        for (Map<String, Object> e : especialidades) {
            if (idEsp != null && ((Number) e.get("id")).intValue() == idEsp) {
                especialidadActual = e;
                break;
            }
        }
        if (especialidadActual == null) {
            for (Map<String, Object> e : especialidades) {
                if ("Medicina General".equals(e.get("nombre"))) {
                    especialidadActual = e;
                    break;
                }
            }
        }
        if (especialidadActual == null && !especialidades.isEmpty()) {
            especialidadActual = especialidades.get(0);
        }

        model.addAttribute("doctor", doctor);
        model.addAttribute("especialidades", especialidades);
        model.addAttribute("especialidadActual", especialidadActual);
        model.addAttribute("casos", casos);
        model.addAttribute("casoActual", casoActual);
        model.addAttribute("citasHoy", citas.citasDeHoy(idDoctor));

        if (casoActual != null) {
            model.addAttribute("atencionesPrevias", jdbcTemplate.queryForList(
                "SELECT a.id, a.fecha_atencion, e.nombre AS especialidad, a.recomendacion, a.pdf_ruta " +
                "FROM atenciones a JOIN especialidades e ON e.id = a.id_especialidad " +
                "WHERE a.id_caso = ? ORDER BY a.fecha_atencion DESC", casoActual.get("id")));
        }

        // Enfermeros asignados (quien toma los signos vitales)
        model.addAttribute("enfermeros", jdbcTemplate.queryForList(
            "SELECT e.id, e.nombre_completo FROM asignacion_enfermero_doctor a " +
            "JOIN enfermeros e ON e.id = a.id_enfermero JOIN usuarios u ON u.id = e.id_usuario " +
            "WHERE a.id_doctor = ? AND a.activo = 1 AND u.activo = 1 ORDER BY e.nombre_completo", idDoctor));

        // Horario del doctor
        model.addAttribute("horarios", jdbcTemplate.queryForList(
            "SELECT dia_semana, TIME_FORMAT(hora_inicio, '%H:%i') AS hora_inicio, " +
            "TIME_FORMAT(hora_fin, '%H:%i') AS hora_fin FROM horarios WHERE id_usuario = ? " +
            "ORDER BY FIELD(dia_semana,'Lunes','Martes','Miércoles','Jueves','Viernes','Sábado','Domingo'), hora_inicio",
            doctor.get("id_usuario")));

        return "medico/atencion";
    }

    /**
     * Registra una atención: datos de la plantilla (JSON), recomendación y medicamentos, todo en una transacción.
     * La generación del PDF (pdf_ruta) se agrega en el paso de PDFs; por ahora queda en NULL.
     */
    @PostMapping("/guardar-atencion")
    public String guardarAtencion(@RequestParam("idCaso") Long idCaso,
                                  @RequestParam("idEspecialidad") Integer idEspecialidad,
                                  @RequestParam(value = "idEnfermero", required = false) Integer idEnfermeroSolicitado,
                                  @RequestParam(value = "diagnostico", required = false) String diagnostico,
                                  @RequestParam(value = "recomendacion", required = false) String recomendacion,
                                  @RequestParam(value = "concluir", defaultValue = "false") boolean concluir,
                                  @RequestParam MultiValueMap<String, String> params,
                                  Authentication authentication) {
        Map<String, Object> doctor = buscarDoctor(authentication.getName());
        if (doctor == null) {
            return "redirect:/medico/atencion?error";
        }
        final Object idDoctor = doctor.get("id");

        // 1) El caso debe ser de este doctor y seguir activo
        if (contar("SELECT COUNT(*) FROM casos_medicos WHERE id = ? AND id_doctor = ? AND estado <> 'Concluido'",
                idCaso, idDoctor) == 0) {
            return "redirect:/medico/atencion?error=caso";
        }

        // 2) La especialidad debe ser del doctor y tener plantilla
        List<String> nombresEsp = jdbcTemplate.queryForList(
            "SELECT e.nombre FROM doctor_especialidades de JOIN especialidades e ON e.id = de.id_especialidad " +
            "WHERE de.id_doctor = ? AND e.id = ?", String.class, idDoctor, idEspecialidad);
        if (nombresEsp.isEmpty() || !PLANTILLAS.contains(nombresEsp.get(0))) {
            return "redirect:/medico/atencion?error=especialidad";
        }

        // 3) El enfermero, si viene, debe estar asignado a este doctor
        final Integer idEnfermero = (idEnfermeroSolicitado != null && contar(
            "SELECT COUNT(*) FROM asignacion_enfermero_doctor WHERE id_enfermero = ? AND id_doctor = ? AND activo = 1",
            idEnfermeroSolicitado, idDoctor) > 0) ? idEnfermeroSolicitado : null;

        // 4) Campos de la plantilla -> JSON
        Map<String, String> datos = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : params.entrySet()) {
            if (!e.getKey().startsWith("f_") || e.getValue().isEmpty()) continue;
            String clave = e.getKey().substring(2);
            String valor = limpiar(e.getValue().get(0), 2000);
            if (valor != null && CLAVE_FORMULARIO.matcher(clave).matches()) {
                datos.put(clave, valor);
            }
        }
        final String json = aJson(datos);
        final String recomendacionFinal = limpiar(recomendacion, 5000);   // null => "Sin recomendaciones"
        final String diagnosticoFinal = limpiar(diagnostico, 5000);
        final String estadoNuevo = concluir ? "Concluido" : "En Tratamiento";

        // 5) Medicamentos (listas paralelas medNombre[], medDosis[], ...)
        final List<String[]> medicamentos = new ArrayList<>();
        List<String> nombres = params.getOrDefault("medNombre", List.of());
        for (int i = 0; i < nombres.size(); i++) {
            String nombre = limpiar(nombres.get(i), 120);
            if (nombre == null) continue;
            medicamentos.add(new String[] {
                nombre,
                limpiar(valorEn(params, "medDosis", i), 60),
                limpiar(valorEn(params, "medFrecuencia", i), 60),
                limpiar(valorEn(params, "medDuracion", i), 60),
                limpiar(valorEn(params, "medIndicaciones", i), 255)
            });
        }

        try {
            Long idAtencion = tx.execute(status -> {
                KeyHolder kh = new GeneratedKeyHolder();
                jdbcTemplate.update(con -> {
                    PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO atenciones (id_caso, id_doctor, id_enfermero, id_especialidad, " +
                        "datos_formulario, recomendacion) VALUES (?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, idCaso);
                    ps.setObject(2, idDoctor);
                    ps.setObject(3, idEnfermero, Types.INTEGER);
                    ps.setInt(4, idEspecialidad);
                    ps.setString(5, json);
                    ps.setString(6, recomendacionFinal);
                    return ps;
                }, kh);
                long id = kh.getKey().longValue();

                for (String[] m : medicamentos) {
                    jdbcTemplate.update(
                        "INSERT INTO medicamentos_recetados (id_atencion, nombre, dosis, frecuencia, duracion, indicaciones) " +
                        "VALUES (?, ?, ?, ?, ?, ?)", id, m[0], m[1], m[2], m[3], m[4]);
                }

                jdbcTemplate.update(
                    "UPDATE casos_medicos SET diagnostico = COALESCE(?, diagnostico), estado = ? WHERE id = ?",
                    diagnosticoFinal, estadoNuevo, idCaso);
                return id;
            });
                        // El PDF se genera después de guardar. Si falla, la atención queda guardada y
            // el PDF se vuelve a intentar al próximo arranque.
            try {
                pdfService.generarYGuardar(idAtencion);
            } catch (Exception e) {
                log.error("La atención {} se guardó, pero no se pudo generar su PDF", idAtencion, e);
            }
            return "redirect:/medico/atencion?exito&atencion=" + idAtencion;
        } catch (Exception e) {
            log.error("Error al guardar la atención del caso {}", idCaso, e);
            return "redirect:/medico/atencion?error=db";
        }
    }

    // ------------------------------------------------------------------ CITAS

    @GetMapping("/citas")
    public String citas(Authentication authentication, Model model) {
        Map<String, Object> doctor = buscarDoctor(authentication.getName());
        if (doctor == null) {
            model.addAttribute("error", "No se encontró información del médico.");
            return "medico/citas";
        }
        model.addAttribute("doctor", doctor);
        model.addAttribute("citas", citas.citasDoctor(doctor.get("id")));
        return "medico/citas";
    }

    @PostMapping("/citas/{id}/estado")
    public String cambiarEstadoCita(@PathVariable Long id,
                                    @RequestParam("estado") String estado,
                                    @RequestParam(value = "observacion", required = false) String observacion,
                                    Authentication authentication) {
        Map<String, Object> doctor = buscarDoctor(authentication.getName());
        if (doctor == null) {
            return "redirect:/medico/atencion";
        }
        boolean actualizada = citas.cambiarEstado(id, doctor.get("id"), estado, observacion);
        return "redirect:/medico/citas?" + (actualizada ? "actualizado" : "error=estado");
    }

    // ------------------------------------------------------------------ HISTÓRICO

    @GetMapping("/historico_pacientes")
    public String historicoPacientes(Authentication authentication, Model model) {
        Map<String, Object> doctor = buscarDoctor(authentication.getName());
        if (doctor == null) {
            model.addAttribute("error", "No se encontró información del médico.");
            return "medico/historico_pacientes";
        }
        model.addAttribute("doctor", doctor);
        model.addAttribute("historico", jdbcTemplate.queryForList(
            "SELECT a.id AS id_atencion, a.fecha_atencion, a.pdf_ruta, " +
            "COALESCE(NULLIF(TRIM(a.recomendacion), ''), 'Sin recomendaciones') AS recomendacion, " +
            "cm.id AS id_caso, cm.titulo_caso, cm.estado, " +
            "p.id AS id_paciente, p.nombre_completo AS nombre_paciente, e.nombre AS especialidad " +
            "FROM atenciones a " +
            "JOIN casos_medicos cm ON cm.id = a.id_caso " +
            "JOIN pacientes p ON p.id = cm.id_paciente " +
            "JOIN especialidades e ON e.id = a.id_especialidad " +
            "WHERE cm.id_doctor = ? ORDER BY a.fecha_atencion DESC", doctor.get("id")));
        return "medico/historico_pacientes";
    }

    // ------------------------------------------------------------------ CHAT (tipo WhatsApp)

    @GetMapping("/chat")
    public String chat(Authentication authentication, Model model) {
        Map<String, Object> doctor = buscarDoctor(authentication.getName());
        if (doctor == null) {
            model.addAttribute("error", "No se encontró información del médico.");
            return "medico/chat";
        }
        model.addAttribute("doctor", doctor);
        model.addAttribute("conversaciones", conversaciones(doctor.get("id")));
        return "medico/chat";
    }

    @GetMapping("/chat/{idCaso}")
    public String chatCaso(@PathVariable Long idCaso,
                           Authentication authentication, Model model) {
        Map<String, Object> doctor = buscarDoctor(authentication.getName());
        if (doctor == null) {
            return "redirect:/medico/atencion";
        }
        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
            "SELECT cm.id, cm.titulo_caso, p.nombre_completo AS nombre_paciente " +
            "FROM casos_medicos cm JOIN pacientes p ON p.id = cm.id_paciente " +
            "WHERE cm.id = ? AND cm.id_doctor = ?", idCaso, doctor.get("id"));
        if (filas.isEmpty()) {
            return "redirect:/medico/chat";
        }

        jdbcTemplate.update(
            "UPDATE mensajes_chat SET leido = 1 WHERE id_caso = ? AND remitente = 'paciente' AND leido = 0",
            idCaso);

        model.addAttribute("doctor", doctor);
        model.addAttribute("caso", filas.get(0));
        model.addAttribute("mensajes", jdbcTemplate.queryForList(
            "SELECT id, remitente, mensaje, fecha_envio, adjunto_nombre, adjunto_tipo FROM mensajes_chat " +
            "WHERE id_caso = ? ORDER BY fecha_envio ASC, id ASC", idCaso));
        model.addAttribute("conversaciones", conversaciones(doctor.get("id")));
        return "medico/chat";
    }

    @PostMapping("/chat/enviar")
    public String enviarMensaje(@RequestParam("idCaso") Long idCaso,
                                @RequestParam("mensaje") String mensaje,
                                Authentication authentication) {
        Map<String, Object> doctor = buscarDoctor(authentication.getName());
        if (doctor == null || contar(
                "SELECT COUNT(*) FROM casos_medicos WHERE id = ? AND id_doctor = ?",
                idCaso, doctor.get("id")) == 0) {
            return "redirect:/medico/chat";
        }
        String texto = limpiar(mensaje, MAX_MENSAJE);
        if (texto != null) {
            jdbcTemplate.update(
                "INSERT INTO mensajes_chat (id_caso, remitente, mensaje) VALUES (?, 'doctor', ?)",
                idCaso, texto);
            avisos.enviarResumen(notificaciones.usuarioContraparte(idCaso, "doctor"));
        }
        return "redirect:/medico/chat/" + idCaso;
    }
}