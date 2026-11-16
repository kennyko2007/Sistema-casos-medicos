package bo.edu.casosmedicos.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Fecha y hora oficiales del sistema: America/La_Paz (UTC-4, Bolivia no usa horario de verano).
 * Se calculan en Java para no depender de la zona horaria con la que esté configurado MySQL.
 */
public final class HoraBolivia {

    public static final ZoneId ZONA = ZoneId.of("America/La_Paz");

    /** Mismos valores que el ENUM horarios.dia_semana, en orden de DayOfWeek (lunes = 1). */
    private static final String[] DIAS =
        {"Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"};

    private static final DateTimeFormatter FORMATO_LARGO =
        DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-BO"));

    private HoraBolivia() { }

    public static LocalDate hoy() {
        return LocalDate.now(ZONA);
    }

    public static LocalTime ahora() {
        return LocalTime.now(ZONA).withNano(0);
    }

    public static String diaSemana() {
        return diaSemana(hoy());
    }

    public static String diaSemana(LocalDate fecha) {
        return DIAS[fecha.getDayOfWeek().getValue() - 1];
    }

    public static String hoyTexto() {
        String t = hoy().format(FORMATO_LARGO);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}