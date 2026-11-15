package bo.edu.casosmedicos.pdf;

import bo.edu.casosmedicos.archivos.AlmacenArchivos;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Genera el PDF de una atención desde la base de datos y actualiza atenciones.pdf_ruta. */
@Service
public class AtencionPdfService {

    private static final Logger log = LoggerFactory.getLogger(AtencionPdfService.class);

    private static final String SQL_ATENCION =
        "SELECT a.id, DATE_FORMAT(a.fecha_atencion, '%d/%m/%Y %H:%i') AS fecha, " +
        "a.datos_formulario, a.recomendacion, " +
        "c.titulo_caso, c.descripcion_sintomas, c.diagnostico, " +
        "p.nombre_completo AS paciente, DATE_FORMAT(p.fecha_nacimiento, '%d/%m/%Y') AS nacimiento, " +
        "p.telefono, p.tipo_sangre, " +
        "TIMESTAMPDIFF(YEAR, p.fecha_nacimiento, DATE(a.fecha_atencion)) AS edad, " +
        "d.nombre_completo AS doctor, e.nombre AS especialidad, en.nombre_completo AS enfermero " +
        "FROM atenciones a " +
        "JOIN casos_medicos c ON c.id = a.id_caso " +
        "JOIN pacientes p ON p.id = c.id_paciente " +
        "JOIN doctores d ON d.id = a.id_doctor " +
        "JOIN especialidades e ON e.id = a.id_especialidad " +
        "LEFT JOIN enfermeros en ON en.id = a.id_enfermero " +
        "WHERE a.id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AlmacenArchivos almacen;

    public AtencionPdfService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AlmacenArchivos almacen) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.almacen = almacen;
    }

    private static String texto(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private Map<String, String> leerCampos(Object json) {
        Map<String, String> r = new LinkedHashMap<>();
        if (json == null) {
            return r;
        }
        try {
            Map<String, Object> crudo = objectMapper.readValue(
                String.valueOf(json), new TypeReference<Map<String, Object>>() {});
            crudo.forEach((k, v) -> {
                if (v != null) {
                    r.put(k, String.valueOf(v));
                }
            });
        } catch (Exception e) {
            log.warn("datos_formulario ilegible: {}", e.getMessage());
        }
        return r;
    }

    /** Genera (o regenera) el PDF de una atención. Devuelve la ruta relativa guardada. */
    public String generarYGuardar(long idAtencion) throws IOException {
        List<Map<String, Object>> filas = jdbcTemplate.queryForList(SQL_ATENCION, idAtencion);
        if (filas.isEmpty()) {
            throw new IllegalArgumentException("No existe la atención " + idAtencion);
        }
        Map<String, Object> f = filas.get(0);

        List<String[]> medicamentos = jdbcTemplate.query(
            "SELECT nombre, dosis, frecuencia, duracion, indicaciones " +
            "FROM medicamentos_recetados WHERE id_atencion = ? ORDER BY id",
            (rs, i) -> new String[] {
                rs.getString("nombre"), rs.getString("dosis"), rs.getString("frecuencia"),
                rs.getString("duracion"), rs.getString("indicaciones")},
            idAtencion);

        HistoriaClinicaPdf.Datos datos = new HistoriaClinicaPdf.Datos(
            idAtencion,
            texto(f.get("fecha")),
            texto(f.get("paciente")),
            texto(f.get("edad")),
            texto(f.get("nacimiento")),
            texto(f.get("telefono")),
            texto(f.get("tipo_sangre")),
            texto(f.get("titulo_caso")),
            texto(f.get("descripcion_sintomas")),
            texto(f.get("diagnostico")),
            texto(f.get("especialidad")),
            texto(f.get("doctor")),
            texto(f.get("enfermero")),
            leerCampos(f.get("datos_formulario")),
            texto(f.get("recomendacion")),
            medicamentos);

        byte[] pdf = HistoriaClinicaPdf.generar(datos);
        String ruta = almacen.guardarAtencionPdf(idAtencion, pdf);
        jdbcTemplate.update("UPDATE atenciones SET pdf_ruta = ? WHERE id = ?", ruta, idAtencion);
        return ruta;
    }

    /** Genera los PDFs de las atenciones cuyo archivo no existe (por ejemplo, los 2 casos de prueba). */
    public int regenerarFaltantes() {
        int generados = 0;
        List<Map<String, Object>> todas = jdbcTemplate.queryForList("SELECT id, pdf_ruta FROM atenciones ORDER BY id");
        for (Map<String, Object> fila : todas) {
            if (almacen.cargar(texto(fila.get("pdf_ruta"))) != null) {
                continue;
            }
            long id = ((Number) fila.get("id")).longValue();
            try {
                generarYGuardar(id);
                generados++;
            } catch (Exception e) {
                log.error("No se pudo generar el PDF de la atención {}", id, e);
            }
        }
        return generados;
    }
}