package bo.edu.casosmedicos.pdf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** Al arrancar, genera los PDFs de atenciones que aún no tienen archivo. */
@Component
public class PdfsFaltantesRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PdfsFaltantesRunner.class);

    private final AtencionPdfService servicio;

    public PdfsFaltantesRunner(AtencionPdfService servicio) {
        this.servicio = servicio;
    }

    @Override
    public void run(String... args) {
        try {
            int n = servicio.regenerarFaltantes();
            if (n > 0) {
                log.info("PDFs de atenciones generados al arrancar: {}", n);
            }
        } catch (Exception e) {
            log.error("No se pudo revisar los PDFs faltantes: {}", e.getMessage());
        }
    }
}