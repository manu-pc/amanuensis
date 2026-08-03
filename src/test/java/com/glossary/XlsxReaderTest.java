package com.glossary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class XlsxReaderTest {

    // ---------------------------------------------------------------
    // construción de libros de proba
    // ---------------------------------------------------------------

    private static final String RELS = """
            <?xml version="1.0"?>
            <Relationships>
              <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
              <Relationship Id="rId2" Target="sharedStrings.xml"/>
            </Relationships>
            """;

    /** Un libro dunha folla cuxas celas veñen da táboa de cadeas compartidas. */
    private static byte[] book(String sheetName, String... values) {
        StringBuilder shared = new StringBuilder("<?xml version=\"1.0\"?><sst>");
        for (String v : values) {
            shared.append("<si><t>").append(v).append("</t></si>");
        }
        shared.append("</sst>");

        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\"?><worksheet><sheetData><row r=\"1\">");
        for (int i = 0; i < values.length; i++) {
            sheet.append("<c r=\"").append((char) ('A' + i)).append("1\" t=\"s\"><v>")
                    .append(i).append("</v></c>");
        }
        sheet.append("</row></sheetData></worksheet>");

        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/workbook.xml",
                "<?xml version=\"1.0\"?><workbook><sheets>"
                        + "<sheet name=\"" + sheetName + "\" sheetId=\"1\" r:id=\"rId1\"/>"
                        + "</sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", RELS);
        parts.put("xl/sharedStrings.xml", shared.toString());
        parts.put("xl/worksheets/sheet1.xml", sheet.toString());
        return zip(parts, 0L);
    }

    private static byte[] zip(Map<String, String> parts, long time) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(out)) {
                for (Map.Entry<String, String> e : parts.entrySet()) {
                    ZipEntry entry = new ZipEntry(e.getKey());
                    entry.setTime(time);
                    zos.putNextEntry(entry);
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------
    // lectura
    // ---------------------------------------------------------------

    @Test
    void readsSheetNamesAndSharedStringCells() throws IOException {
        XlsxReader.Workbook wb = XlsxReader.read(book("CAPITULO 1", "Bandage", "Venda"));

        assertEquals(java.util.List.of("CAPITULO 1"), wb.sheetNames());
        assertEquals(2, wb.cells().size());
        assertEquals(new XlsxReader.Cell("CAPITULO 1", "A1", "Bandage"), wb.cells().get(0));
        assertEquals(new XlsxReader.Cell("CAPITULO 1", "B1", "Venda"), wb.cells().get(1));
    }

    @Test
    void emptyCellsAreNotContent() throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/workbook.xml",
                "<?xml version=\"1.0\"?><workbook><sheets>"
                        + "<sheet name=\"F\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", RELS);
        parts.put("xl/worksheets/sheet1.xml",
                "<?xml version=\"1.0\"?><worksheet><sheetData><row r=\"1\">"
                        + "<c r=\"A1\"/><c r=\"B1\"><v>7</v></c></row></sheetData></worksheet>");

        XlsxReader.Workbook wb = XlsxReader.read(zip(parts, 0L));
        assertEquals(1, wb.cells().size(), "a cela baleira non conta");
        assertEquals("7", wb.cells().get(0).value(), "os números léense tal cal");
    }

    @Test
    void inlineStringsAreReadToo() throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/workbook.xml",
                "<?xml version=\"1.0\"?><workbook><sheets>"
                        + "<sheet name=\"F\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", RELS);
        parts.put("xl/worksheets/sheet1.xml",
                "<?xml version=\"1.0\"?><worksheet><sheetData><row r=\"1\">"
                        + "<c r=\"A1\" t=\"inlineStr\"><is><t>Ovo</t></is></c>"
                        + "</row></sheetData></worksheet>");

        assertEquals("Ovo", XlsxReader.read(zip(parts, 0L)).cells().get(0).value());
    }

    @Test
    void xmlEntitiesComeBackAsRealCharacters() throws IOException {
        // no glosario real hai entradas como "The &amp;!&amp;? Squad"
        XlsxReader.Workbook wb = XlsxReader.read(book("F", "The &amp;!&amp;? Squad"));
        assertEquals("The &!&? Squad", wb.cells().get(0).value());
    }

    // ---------------------------------------------------------------
    // pegada: o motivo de que exista esta clase
    // ---------------------------------------------------------------

    @Test
    void savingWithoutChangingAnythingKeepsTheSameDigest() {
        // mesmo contido, ZIP reescrito con outras marcas de tempo: é o que fai
        // LibreOffice cada vez que se garda, e non debe contar como cambio
        byte[] first = book("CAPITULO 1", "Bandage", "Venda");
        byte[] second = rezip(book("CAPITULO 1", "Bandage", "Venda"), 1_700_000_000_000L);

        assertNotEquals(java.util.Arrays.hashCode(first), java.util.Arrays.hashCode(second),
                "os bytes teñen que ser distintos, se non a proba non demostra nada");
        assertEquals(XlsxReader.digest(first), XlsxReader.digest(second));
    }

    @Test
    void changingACellChangesTheDigest() {
        assertNotEquals(XlsxReader.digest(book("F", "Bandage", "Venda")),
                XlsxReader.digest(book("F", "Bandage", "Vendaxe")));
    }

    @Test
    void renamingASheetChangesTheDigest() {
        assertNotEquals(XlsxReader.digest(book("CAPITULO 1", "Egg")),
                XlsxReader.digest(book("CAPITULO 5", "Egg")));
    }

    @Test
    void anUnreadableFileStillGivesAStableDigest() {
        byte[] junk = "isto non é un xlsx".getBytes(StandardCharsets.UTF_8);
        assertEquals(XlsxReader.digest(junk), XlsxReader.digest(junk.clone()),
                "determinista aínda que non se poida interpretar");
        assertNotEquals(XlsxReader.digest(junk),
                XlsxReader.digest("outra cousa".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", XlsxReader.digest(null));
    }

    @Test
    void aDoctypeIsRefusedInsteadOfResolved() {
        // un .xlsx pode vir de calquera sitio: non debe poder ler ficheiros locais
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/workbook.xml",
                "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                        + "<workbook><sheets><sheet name=\"&x;\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", RELS);

        // non peta: cae na pegada dos bytes en bruto, sen resolver nada
        String d = XlsxReader.digest(zip(parts, 0L));
        assertFalse(d.isEmpty());
        assertEquals(64, d.length(), "sha-256 en hexadecimal");
    }

    /** Volve empaquetar as mesmas entradas con outra marca de tempo. */
    private static byte[] rezip(byte[] original, long time) {
        try {
            Map<String, String> parts = new LinkedHashMap<>();
            try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(original))) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    parts.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return zip(parts, time);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------
    // o glosario de verdade, se está a man
    // ---------------------------------------------------------------

    @Test
    void theRealGlossaryCanBeRead() throws IOException {
        Path real = Path.of("deltarune-en-galego-DEV", Glossary.REL_PATH);
        if (!Files.isRegularFile(real)) {
            return; // fóra do equipo de desenvolvemento non está: non é un fallo
        }
        XlsxReader.Workbook wb = XlsxReader.read(Files.readAllBytes(real));
        assertTrue(wb.sheetNames().size() >= 3, "o glosario ten unha folla por capítulo");
        assertTrue(wb.cells().size() > 100, "e bastantes termos: " + wb.cells().size());
    }
}
