package com.tucanoo.davkit.demo;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeds are hand-built OOXML; a typo means Office shows a repair dialog instead of the demo.
 * This can't prove Office opens them (that stays a manual check), but it catches malformed XML,
 * missing parts, and dangling relationship targets.
 */
class SeedDataTest {

    @Test
    void docxHasItsPartsAndParses() throws Exception {
        Map<String, String> parts = unzip(SeedData.minimalDocx("hello docx"));
        assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels", "word/document.xml");
        parts.forEach(SeedDataTest::assertWellFormed);
        assertThat(parts.get("word/document.xml")).contains("hello docx");
    }

    @Test
    void xlsxHasItsPartsAndParses() throws Exception {
        Map<String, String> parts = unzip(SeedData.minimalXlsx("hello xlsx"));
        assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels",
                "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml");
        parts.forEach(SeedDataTest::assertWellFormed);
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains("hello xlsx");
        assertRelationshipTargetsExist(parts);
    }

    @Test
    void pptxHasItsPartsAndParses() throws Exception {
        Map<String, String> parts = unzip(SeedData.minimalPptx("hello pptx"));
        assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels",
                "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels",
                "ppt/slideMasters/slideMaster1.xml", "ppt/slideMasters/_rels/slideMaster1.xml.rels",
                "ppt/slideLayouts/slideLayout1.xml", "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
                "ppt/slides/slide1.xml", "ppt/slides/_rels/slide1.xml.rels", "ppt/theme/theme1.xml");
        parts.forEach(SeedDataTest::assertWellFormed);
        assertThat(parts.get("ppt/slides/slide1.xml")).contains("hello pptx");
        assertRelationshipTargetsExist(parts);
        // Every part the content-types map points at must exist, or PowerPoint repairs.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("PartName=\"/([^\"]+)\"")
                .matcher(parts.get("[Content_Types].xml"));
        while (m.find()) {
            assertThat(parts).as("override target " + m.group(1)).containsKey(m.group(1));
        }
    }

    /** Resolves every Target of every .rels part against the zip's entry names. */
    private static void assertRelationshipTargetsExist(Map<String, String> parts) {
        parts.forEach((name, xml) -> {
            if (!name.endsWith(".rels")) {
                return;
            }
            String base = name.substring(0, name.lastIndexOf("_rels/")); // rels live beside their source
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Target=\"([^\"]+)\"").matcher(xml);
            while (m.find()) {
                String target = java.nio.file.Path.of(base).resolve(m.group(1)).normalize()
                        .toString().replace('\\', '/');
                assertThat(parts).as(name + " → " + m.group(1)).containsKey(target);
            }
        });
    }

    private static void assertWellFormed(String name, String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new AssertionError(name + " is not well-formed XML: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }
}
