package bo.edu.casosmedicos.pdf;

import bo.edu.casosmedicos.pdf.CamposFormulario.Campo;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Dibuja el PDF "Historia clínica" de una atención. No usa base de datos ni Spring. */
public final class HistoriaClinicaPdf {

    /** Todo lo que necesita el PDF, ya en texto listo para imprimir. */
    public record Datos(long idAtencion, String fecha, String paciente, String edad, String nacimiento,
                        String telefono, String tipoSangre, String tituloCaso, String sintomas,
                        String diagnostico, String especialidad, String doctor, String enfermero,
                        Map<String, String> campos, String recomendacion, List<String[]> medicamentos) {}

    private static final Color AZUL = new Color(0x12, 0x2B, 0x4A);
    private static final Color DORADO = new Color(0xC9, 0xA2, 0x27);
    private static final Color FONDO = new Color(0xEE, 0xF2, 0xF8);
    private static final Color LINEA = new Color(0xB8, 0xC2, 0xD0);
    private static final Color TEXTO = new Color(0x1F, 0x29, 0x37);
    private static final Color TENUE = new Color(0x5B, 0x67, 0x78);

    private HistoriaClinicaPdf() {}

    public static byte[] generar(Datos d) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 40, 40, 30, 50);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Pie(d.idAtencion()));
            doc.addTitle("Historia clínica - atención " + d.idAtencion());
            doc.addAuthor("Luxe Health");
            doc.open();

            cabecera(doc, d);

            seccion(doc, "FILIACIÓN Y ATENCIÓN");
            grilla(doc, 3, new String[][] {
                {"Paciente", d.paciente()},
                {"Edad", d.edad() == null || d.edad().isBlank() ? "" : d.edad() + " años"},
                {"Fecha de nacimiento", d.nacimiento()},
                {"Teléfono", d.telefono()},
                {"Tipo de sangre", d.tipoSangre()},
                {"Fecha y hora de atención", d.fecha()},
                {"Servicio / especialidad", d.especialidad()},
                {"Médico tratante", d.doctor()},
                {"Enfermero/a", d.enfermero()}});

            seccion(doc, "MOTIVO DE CONSULTA E HISTORIA ACTUAL");
            motivo(doc, d.tituloCaso(), d.sintomas());

            seccion(doc, "SIGNOS VITALES");
            grilla(doc, 3, pares(CamposFormulario.SIGNOS_VITALES, d.campos()));

            List<Campo> propios = CamposFormulario.deEspecialidad(d.especialidad());
            if (!propios.isEmpty()) {
                seccion(doc, "EVALUACIÓN DE " + limpio(d.especialidad()).toUpperCase());
                List<Campo> cortos = new ArrayList<>();
                for (Campo c : propios) {
                    if (!c.largo()) cortos.add(c);
                }
                if (!cortos.isEmpty()) {
                    grilla(doc, 2, pares(cortos, d.campos()));
                }
                for (Campo c : propios) {
                    if (c.largo()) {
                        subtitulo(doc, c.etiqueta());
                        bloque(doc, d.campos().get(c.clave()));
                    }
                }
            }

            seccion(doc, "DIAGNÓSTICO");
            bloque(doc, d.diagnostico());

            seccion(doc, "MEDICAMENTOS RECETADOS");
            medicamentos(doc, d.medicamentos());

            cierre(doc, d);

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el PDF de la atención " + d.idAtencion(), e);
        }
    }

    // ------------------------------------------------------------------ bloques

    private static void cabecera(Document doc, Datos d) {
        PdfPTable t = new PdfPTable(new float[] {3f, 2f});
        t.setWidthPercentage(100);

        PdfPCell izq = sinBorde();
        Paragraph marca = new Paragraph();
        marca.add(new Chunk("LUXE ", fuente(22, Font.BOLD, AZUL)));
        marca.add(new Chunk("HEALTH", fuente(22, Font.BOLD, DORADO)));
        izq.addElement(marca);
        izq.addElement(new Paragraph("Hospital privado", fuente(9, Font.NORMAL, TENUE)));
        t.addCell(izq);

        PdfPCell der = sinBorde();
        Paragraph titulo = new Paragraph("HISTORIA CLÍNICA", fuente(16, Font.BOLD, AZUL));
        titulo.setAlignment(Element.ALIGN_RIGHT);
        Paragraph numero = new Paragraph("Atención N.º " + d.idAtencion(), fuente(9, Font.NORMAL, TENUE));
        numero.setAlignment(Element.ALIGN_RIGHT);
        der.addElement(titulo);
        der.addElement(numero);
        t.addCell(der);
        doc.add(t);

        PdfPTable linea = new PdfPTable(1);
        linea.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase(" ", fuente(2, Font.NORMAL, TEXTO)));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(DORADO);
        c.setBorderWidth(1.6f);
        linea.addCell(c);
        doc.add(linea);
    }

    private static PdfPCell celdaSeccion(String titulo) {
        PdfPCell c = new PdfPCell(new Phrase(titulo, fuente(9.5f, Font.BOLD, Color.WHITE)));
        c.setBackgroundColor(AZUL);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(5);
        c.setPaddingLeft(8);
        return c;
    }

    private static void seccion(Document doc, String titulo) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(8);
        t.addCell(celdaSeccion(titulo));
        doc.add(t);
    }

    private static void subtitulo(Document doc, String texto) {
        Paragraph p = new Paragraph(texto, fuente(8.5f, Font.BOLD, TENUE));
        p.setSpacingBefore(4);
        p.setSpacingAfter(2);
        doc.add(p);
    }

    private static String[][] pares(List<Campo> campos, Map<String, String> valores) {
        String[][] r = new String[campos.size()][2];
        for (int i = 0; i < campos.size(); i++) {
            r[i][0] = campos.get(i).etiqueta();
            r[i][1] = valores.get(campos.get(i).clave());
        }
        return r;
    }

    private static void grilla(Document doc, int columnas, String[][] pares) {
        PdfPTable t = new PdfPTable(columnas);
        t.setWidthPercentage(100);
        for (String[] par : pares) {
            Paragraph p = new Paragraph();
            p.add(new Chunk(par[0].toUpperCase() + "\n", fuente(6.8f, Font.BOLD, TENUE)));
            String valor = limpio(par[1]);
            p.add(new Chunk(valor.isBlank() ? "—" : valor, fuente(10, Font.NORMAL, TEXTO)));
            PdfPCell c = new PdfPCell(p);
            estiloCaja(c);
            t.addCell(c);
        }
        // completa la última fila para que la tabla cierre bien
        int resto = pares.length % columnas;
        if (resto != 0) {
            for (int i = resto; i < columnas; i++) {
                PdfPCell vacia = new PdfPCell(new Phrase(" "));
                estiloCaja(vacia);
                t.addCell(vacia);
            }
        }
        doc.add(t);
    }

    /** Motivo de consulta (título del caso) y síntomas, en una sola caja. */
    private static void motivo(Document doc, String titulo, String sintomas) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        estiloCaja(c);
        c.setPadding(7);

        c.addElement(new Paragraph("MOTIVO DE CONSULTA", fuente(6.8f, Font.BOLD, TENUE)));
        String m = limpio(titulo);
        c.addElement(new Paragraph(m.isBlank() ? "No registrado" : m, fuente(10.5f, Font.BOLD, TEXTO)));

        Paragraph etiqueta = new Paragraph("HISTORIA DE LA ENFERMEDAD ACTUAL", fuente(6.8f, Font.BOLD, TENUE));
        etiqueta.setSpacingBefore(5);
        c.addElement(etiqueta);
        String s = limpio(sintomas);
        Paragraph p = s.isBlank()
            ? new Paragraph("No registrado", fuente(10, Font.ITALIC, TENUE))
            : new Paragraph(s, fuente(10, Font.NORMAL, TEXTO));
        p.setLeading(13);
        c.addElement(p);

        t.addCell(c);
        doc.add(t);
    }

    private static PdfPCell celdaBloque(String texto) {
        String v = limpio(texto);
        Paragraph p = v.isBlank()
            ? new Paragraph("No registrado", fuente(10, Font.ITALIC, TENUE))
            : new Paragraph(v, fuente(10, Font.NORMAL, TEXTO));
        p.setLeading(13);
        PdfPCell c = new PdfPCell(p);
        estiloCaja(c);
        c.setPadding(7);
        return c;
    }

    private static void bloque(Document doc, String texto) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.addCell(celdaBloque(texto));
        doc.add(t);
    }

    private static void medicamentos(Document doc, List<String[]> meds) {
        if (meds == null || meds.isEmpty()) {
            bloque(doc, "Sin medicamentos recetados");
            return;
        }
        PdfPTable t = new PdfPTable(new float[] {2.4f, 1.2f, 1.4f, 1.2f, 2.6f});
        t.setWidthPercentage(100);
        for (String h : new String[] {"MEDICAMENTO", "DOSIS", "FRECUENCIA", "DURACIÓN", "INDICACIONES"}) {
            PdfPCell c = new PdfPCell(new Phrase(h, fuente(7, Font.BOLD, TENUE)));
            estiloCaja(c);
            c.setBackgroundColor(FONDO);
            t.addCell(c);
        }
        t.setHeaderRows(1);
        for (String[] m : meds) {
            for (int i = 0; i < 5; i++) {
                String v = limpio(i < m.length ? m[i] : "");
                PdfPCell c = new PdfPCell(new Phrase(v.isBlank() ? "—" : v,
                    fuente(9.5f, i == 0 ? Font.BOLD : Font.NORMAL, TEXTO)));
                estiloCaja(c);
                t.addCell(c);
            }
        }
        doc.add(t);
    }

    /**
     * Recomendaciones y firmas en un solo bloque que no se parte: si no cabe en la página,
     * pasa completo a la siguiente y las firmas nunca quedan solas.
     */
    private static void cierre(Document doc, Datos d) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(8);
        t.setKeepTogether(true);

        t.addCell(celdaSeccion("RECOMENDACIONES"));
        String rec = limpio(d.recomendacion());
        t.addCell(celdaBloque(rec.isBlank() ? "Sin recomendaciones" : rec));

        PdfPCell firmas = new PdfPCell(tablaFirmas(d));
        firmas.setBorder(Rectangle.NO_BORDER);
        firmas.setPadding(0);
        firmas.setPaddingTop(34);
        t.addCell(firmas);

        doc.add(t);
    }

    private static PdfPTable tablaFirmas(Datos d) {
        PdfPTable t = new PdfPTable(new float[] {5f, 1f, 5f});
        t.setWidthPercentage(100);
        t.addCell(firma(d.doctor(), "Médico tratante · " + limpio(d.especialidad())));
        t.addCell(sinBorde());
        String enf = limpio(d.enfermero());
        t.addCell(enf.isBlank() ? sinBorde() : firma(enf, "Enfermero/a"));
        return t;
    }

    private static PdfPCell firma(String nombre, String detalle) {
        Paragraph p = new Paragraph(limpio(nombre), fuente(10, Font.BOLD, TEXTO));
        p.setAlignment(Element.ALIGN_CENTER);
        Paragraph q = new Paragraph(detalle, fuente(8, Font.NORMAL, TENUE));
        q.setAlignment(Element.ALIGN_CENTER);
        PdfPCell c = new PdfPCell();
        c.addElement(p);
        c.addElement(q);
        c.setBorder(Rectangle.TOP);
        c.setBorderColor(TEXTO);
        c.setBorderWidth(0.8f);
        c.setPaddingTop(4);
        return c;
    }

    // ------------------------------------------------------------------ utilidades

    private static PdfPCell sinBorde() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    private static void estiloCaja(PdfPCell c) {
        c.setBorder(Rectangle.BOX);
        c.setBorderColor(LINEA);
        c.setBorderWidth(0.6f);
        c.setPadding(4);
        c.setPaddingLeft(7);
    }

    private static Font fuente(float tamano, int estilo, Color color) {
        return FontFactory.getFont(FontFactory.HELVETICA, "Cp1252", tamano, estilo, color);
    }

    /** Texto seguro para imprimir: sin nulos ni caracteres de control. */
    private static String limpio(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .trim();
    }

    /** Pie de cada página: confidencialidad y número de página. */
    private static final class Pie extends PdfPageEventHelper {
        private final long idAtencion;

        Pie(long idAtencion) {
            this.idAtencion = idAtencion;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float y = document.bottom() - 20;
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                new Phrase("Luxe Health · Documento confidencial · Atención N.º " + idAtencion,
                    fuente(7.5f, Font.NORMAL, TENUE)),
                document.left(), y, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                new Phrase("Página " + writer.getPageNumber(), fuente(7.5f, Font.NORMAL, TENUE)),
                document.right(), y, 0);
        }
    }
}