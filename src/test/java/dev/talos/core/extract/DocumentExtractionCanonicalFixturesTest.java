package dev.talos.core.extract;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractionCanonicalFixturesTest {

    @Test
    void checkedInCanonicalPdfExtractsKnownText() throws Exception {
        Path fixture = fixture("canonical-text.pdf");

        DocumentExtractionResult result = new DocumentExtractionService(extractionEnabled("pdf"))
                .extract(DocumentExtractionRequest.read(fixture, fixture.getParent()));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertExpectedLinesPresent("canonical-text.expected.txt", result.safeText());
    }

    @Test
    void checkedInCanonicalDocxExtractsKnownText() throws Exception {
        Path fixture = fixture("canonical-report.docx");

        DocumentExtractionResult result = new DocumentExtractionService(extractionEnabled("word"))
                .extract(DocumentExtractionRequest.read(fixture, fixture.getParent()));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertExpectedLinesPresent("canonical-report.expected.txt", result.safeText());
    }

    @Test
    void checkedInCanonicalXlsxExtractsKnownCells() throws Exception {
        Path fixture = fixture("canonical-workbook.xlsx");

        DocumentExtractionResult result = new DocumentExtractionService(extractionEnabled("excel"))
                .extract(DocumentExtractionRequest.read(fixture, fixture.getParent()));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertExpectedLinesPresent("canonical-workbook.expected.txt", result.safeText());
    }

    private static Path fixture(String name) throws URISyntaxException {
        URL url = DocumentExtractionCanonicalFixturesTest.class
                .getResource("/document-fixtures/" + name);
        assertNotNull(url, "missing checked-in fixture: " + name);
        return Path.of(url.toURI());
    }

    private static void assertExpectedLinesPresent(String expectedName, String actual) throws Exception {
        String expected = Files.readString(fixture(expectedName));
        for (String line : expected.lines().map(String::strip).filter(s -> !s.isBlank()).toList()) {
            assertTrue(actual.contains(line), () -> "missing expected fixture line: " + line + "\nActual:\n" + actual);
        }
    }

    private static Config extractionEnabled(String family) {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        Map<String, Object> familyCfg = new LinkedHashMap<>();
        familyCfg.put("enabled", Boolean.TRUE);
        documentExtraction.put(family, familyCfg);
        cfg.data.put("document_extraction", documentExtraction);
        return cfg;
    }
}
