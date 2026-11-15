package bo.edu.casosmedicos.archivos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Guarda y entrega PDFs desde uploads/pdfs (fuera de static, nunca accesible por URL directa).
 * Las rutas que se guardan en la base de datos son relativas a esa carpeta: "cv/xxxx.pdf".
 */
@Service
public class AlmacenArchivos {

    public static final long MAX_CV_BYTES = 5L * 1024 * 1024;
    private static final byte[] FIRMA_PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    /** Archivo rechazado por no cumplir las reglas (vacío, tamaño, tipo). */
    public static class ArchivoInvalidoException extends Exception {
        public ArchivoInvalidoException(String mensaje) {
            super(mensaje);
        }
    }

    private final Path base;

    public AlmacenArchivos(@Value("${app.uploads.dir:uploads}") String dir) throws IOException {
        this.base = Path.of(dir, "pdfs").toAbsolutePath().normalize();
        Files.createDirectories(base.resolve("cv"));
        Files.createDirectories(base.resolve("atenciones"));
    }

    /** Valida y guarda un CV. Devuelve la ruta relativa, por ejemplo "cv/3f2a....pdf". */
    public String guardarCv(MultipartFile archivo) throws ArchivoInvalidoException, IOException {
        if (archivo == null || archivo.isEmpty()) {
            throw new ArchivoInvalidoException("El archivo está vacío");
        }
        if (archivo.getSize() > MAX_CV_BYTES) {
            throw new ArchivoInvalidoException("El archivo supera 5 MB");
        }
        String nombre = archivo.getOriginalFilename();
        if (nombre == null || !nombre.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ArchivoInvalidoException("La extensión debe ser .pdf");
        }
        try (InputStream in = archivo.getInputStream()) {
            byte[] cabecera = in.readNBytes(FIRMA_PDF.length);
            if (!Arrays.equals(cabecera, FIRMA_PDF)) {
                throw new ArchivoInvalidoException("El contenido no es un PDF");
            }
        }
        String relativa = "cv/" + UUID.randomUUID() + ".pdf";
        archivo.transferTo(base.resolve(relativa));
        return relativa;
    }
        /** Guarda el PDF generado de una atención. Devuelve la ruta relativa "atenciones/....pdf". */
    public String guardarAtencionPdf(long idAtencion, byte[] contenido) throws IOException {
        String relativa = "atenciones/atencion-" + idAtencion + "-" + UUID.randomUUID() + ".pdf";
        Files.write(base.resolve(relativa), contenido);
        return relativa;
    }

    /** Devuelve el archivo o null si no existe o la ruta intenta salirse de la carpeta. */
    public Resource cargar(String relativa) {
        if (relativa == null || relativa.isBlank()) {
            return null;
        }
        try {
            Path destino = base.resolve(relativa).normalize();
            if (!destino.startsWith(base) || !Files.isRegularFile(destino)) {
                return null;
            }
            return new PathResource(destino);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Limpieza si falla la base de datos después de guardar el archivo. */
    public void eliminarSilencioso(String relativa) {
        if (relativa == null) {
            return;
        }
        try {
            Path destino = base.resolve(relativa).normalize();
            if (destino.startsWith(base)) {
                Files.deleteIfExists(destino);
            }
        } catch (Exception ignorado) {
            // no es crítico
        }
    }
}