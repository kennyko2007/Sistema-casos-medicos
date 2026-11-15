package bo.edu.casosmedicos.pdf;

import java.util.List;
import java.util.Map;

/**
 * Campos del formulario de atención (claves f_xxx del formulario, guardadas sin el prefijo en
 * atenciones.datos_formulario) y su etiqueta en el PDF. Es la única definición compartida.
 */
public final class CamposFormulario {

    /** clave = nombre del campo sin "f_"; largo = se muestra como bloque de texto. */
    public record Campo(String clave, String etiqueta, boolean largo) {}

    /** Signos vitales: comunes a todas las especialidades. */
    public static final List<Campo> SIGNOS_VITALES = List.of(
        new Campo("presion", "Presión arterial (mmHg)", false),
        new Campo("pulso", "Pulso (lpm)", false),
        new Campo("temperatura", "Temperatura (°C)", false),
        new Campo("saturacion", "Saturación O2 (%)", false),
        new Campo("peso", "Peso (kg)", false),
        new Campo("talla", "Talla (cm)", false));

    private static final Map<String, List<Campo>> POR_ESPECIALIDAD = Map.of(
        "Medicina General", List.of(
            new Campo("antecedentes", "Antecedentes relevantes", true),
            new Campo("examen_fisico", "Examen físico", true)),
        "Cardiología", List.of(
            new Campo("ritmo", "Ritmo cardíaco", false),
            new Campo("soplo", "Soplos", false),
            new Campo("dolor_toracico", "Dolor torácico", false),
            new Campo("edema", "Edema en extremidades", false),
            new Campo("ecg", "Hallazgos de electrocardiograma", true)),
        "Neurología", List.of(
            new Campo("glasgow", "Escala de Glasgow (3-15)", false),
            new Campo("cefalea", "Intensidad de cefalea (0-10)", false),
            new Campo("pupilas", "Pupilas", false),
            new Campo("fuerza", "Fuerza muscular (0-5)", false),
            new Campo("reflejos", "Reflejos", false),
            new Campo("marcha", "Marcha", false),
            new Campo("hallazgos", "Hallazgos neurológicos", true)));

    private CamposFormulario() {}

    /** Campos propios de una especialidad; lista vacía si no tiene plantilla. */
    public static List<Campo> deEspecialidad(String nombre) {
        return POR_ESPECIALIDAD.getOrDefault(nombre, List.of());
    }
}