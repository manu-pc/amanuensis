package com.glossary;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Lector mínimo de .xlsx: só o necesario para saber <b>que hai nas celas</b>.
 *
 * <p>
 * Existe por unha razón moi concreta: un .xlsx é un ZIP de XML, e LibreOffice
 * (ou Excel) reescribe marcas de tempo e {@code docProps} cada vez que garda.
 * Abrir o glosario, non tocar nada e pechalo xa produce <b>bytes distintos</b>.
 * Se o cambio se medise por bytes, o ficheiro quedaría «modificado» para sempre,
 * {@link com.git.GitRepoService#hasTrackedChanges()} devolvería sempre true e os
 * pull pararían en seco sen que ninguén editase nada. Por iso o que se compara é
 * o contido das celas, non o ficheiro.
 *
 * <p>
 * Non se usa Apache POI a propósito: engadiría uns 10 MB a un fat-jar que xa
 * pesa 13, e para ler celas chega co ZIP e o XML que trae o propio JDK.
 *
 * <p>
 * Clase pura: entra un array de bytes, sae texto. Sen IO nin JavaFX.
 */
public final class XlsxReader {

    /** Unha cela con contido: folla, referencia (ex. {@code B3}) e texto. */
    public record Cell(String sheet, String ref, String value) {
    }

    /** Contido lexible dun libro: nomes de folla en orde e celas non baleiras. */
    public record Workbook(List<String> sheetNames, List<Cell> cells) {
    }

    private XlsxReader() {
    }

    /**
     * Pegada estable do contido. Dous ficheiros co mesmo texto nas mesmas celas
     * dan a mesma pegada aínda que os seus bytes difiran.
     *
     * <p>
     * Se o ficheiro non se pode interpretar como .xlsx (corrupto, ou simplemente
     * outra cousa cunha extensión enganosa) devólvese o sha-256 dos bytes tal
     * cal. Iso é peor —volve depender do formato exacto do ZIP— pero é
     * determinista e nunca di «non cambiou» cando non se sabe.
     */
    public static String digest(byte[] xlsx) {
        if (xlsx == null) {
            return "";
        }
        try {
            return sha256(canonicalText(read(xlsx)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return sha256(xlsx);
        }
    }

    /**
     * Texto canónico do libro: unha liña por folla e outra por cela con contido,
     * ordenadas. A orde fíxase aquí para que un programa que reescriba o XML noutra
     * secuencia, sen cambiar nada do que se ve, siga dando o mesmo resultado.
     */
    public static String canonicalText(Workbook wb) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < wb.sheetNames().size(); i++) {
            // o nome da folla forma parte do contido: renomeala é un cambio real
            lines.add("folla\t" + i + "\t" + wb.sheetNames().get(i));
        }
        for (Cell c : wb.cells()) {
            lines.add("cela\t" + c.sheet() + "\t" + c.ref() + "\t" + c.value());
        }
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    /** Le follas e celas dun .xlsx en memoria. */
    public static Workbook read(byte[] xlsx) throws IOException {
        Map<String, byte[]> parts = unzip(xlsx);

        byte[] workbookXml = parts.get("xl/workbook.xml");
        if (workbookXml == null) {
            throw new IOException("non é un .xlsx: falta xl/workbook.xml");
        }

        Map<String, String> rels = relationships(parts.get("xl/_rels/workbook.xml.rels"));
        List<String> sharedStrings = sharedStrings(parts.get("xl/sharedStrings.xml"));

        List<String> sheetNames = new ArrayList<>();
        List<Cell> cells = new ArrayList<>();

        for (Element sheet : elements(parse(workbookXml), "sheet")) {
            String name = sheet.getAttribute("name");
            sheetNames.add(name);

            String target = rels.get(sheet.getAttribute("r:id"));
            byte[] sheetXml = target == null ? null : parts.get(normalisePart(target));
            if (sheetXml == null) {
                continue; // folla declarada sen ficheiro: nada que ler
            }
            readCells(name, parse(sheetXml), sharedStrings, cells);
        }
        return new Workbook(sheetNames, cells);
    }

    // ---------------------------------------------------------------
    // partes do libro
    // ---------------------------------------------------------------

    private static void readCells(String sheetName, Document doc, List<String> sharedStrings,
            List<Cell> out) {
        for (Element c : elements(doc, "c")) {
            String value = cellValue(c, sharedStrings);
            if (value.isEmpty()) {
                continue; // as celas baleiras non son contido
            }
            out.add(new Cell(sheetName, c.getAttribute("r"), value));
        }
    }

    /**
     * Valor visible dunha cela. {@code t="s"} apunta á táboa de cadeas
     * compartidas, {@code t="inlineStr"} lévao dentro, e o resto (números,
     * resultados de fórmula) está no {@code <v>} tal cal.
     */
    private static String cellValue(Element c, List<String> sharedStrings) {
        String type = c.getAttribute("t");

        if ("inlineStr".equals(type)) {
            return textOf(child(c, "is"));
        }

        Element v = child(c, "v");
        if (v == null) {
            return "";
        }
        String raw = textOf(v);
        if ("s".equals(type)) {
            try {
                int idx = Integer.parseInt(raw.trim());
                return idx >= 0 && idx < sharedStrings.size() ? sharedStrings.get(idx) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return raw;
    }

    private static List<String> sharedStrings(byte[] xml) throws IOException {
        List<String> out = new ArrayList<>();
        if (xml == null) {
            return out;
        }
        for (Element si : elements(parse(xml), "si")) {
            out.add(textOf(si));
        }
        return out;
    }

    private static Map<String, String> relationships(byte[] xml) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (xml == null) {
            return out;
        }
        for (Element rel : elements(parse(xml), "Relationship")) {
            out.put(rel.getAttribute("Id"), rel.getAttribute("Target"));
        }
        return out;
    }

    /** As rutas de {@code workbook.xml.rels} son relativas a {@code xl/}. */
    private static String normalisePart(String target) {
        String t = target.startsWith("/") ? target.substring(1) : "xl/" + target;
        return t.replace("/./", "/");
    }

    // ---------------------------------------------------------------
    // utilidades de XML e ZIP
    // ---------------------------------------------------------------

    /**
     * Analizador sen entidades externas nin DOCTYPE: un .xlsx pode vir de calquera
     * sitio e non ten por que poder abrir ficheiros nin facer peticións de rede.
     */
    private static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false); // os atributos len-se tal cal (ex. "r:id")
            DocumentBuilder b = f.newDocumentBuilder();
            // sen isto o analizador imprime o erro por stderr antes de lanzalo, e
            // enche o rexistro do usuario con ruído que xa se trata aquí arriba
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }

                @Override
                public void warning(org.xml.sax.SAXParseException e) {
                    // irrelevante para ler celas
                }
            });
            return b.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | org.xml.sax.SAXException | IllegalArgumentException e) {
            throw new IOException("XML ilexible dentro do .xlsx", e);
        }
    }

    private static List<Element> elements(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static Element child(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element el && el.getTagName().equals(tag)) {
                return el;
            }
        }
        return null;
    }

    /** Todo o texto que hai debaixo dun nodo, concatenado (ex. {@code <si>} con varios {@code <t>}). */
    private static String textOf(Node node) {
        return node == null ? "" : node.getTextContent();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    out.put(e.getName(), readAll(in));
                }
                in.closeEntry();
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    private static String sha256(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 sempre está dispoñible", e);
        }
    }
}
