package dev.talos.core.ingest;

import dev.talos.spi.types.MediaType;
import dev.talos.spi.types.SourceFormat;
import dev.talos.spi.types.SourceIdentity;
import dev.talos.spi.types.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link SourceClassifier#classify(String)}. */
class SourceClassifierTest {

    // ── SourceType mapping ──

    @ParameterizedTest
    @CsvSource({
            "src/main/java/Foo.java,        CODE_FILE",
            "lib/main.py,                   CODE_FILE",
            "index.ts,                      CODE_FILE",
            "app.go,                        CODE_FILE",
            "README.md,                     DOCUMENT",
            "docs/arch.txt,                 DOCUMENT",
            "guide.rst,                     DOCUMENT",
            "config.yaml,                   CONFIG",
            "data.json,                     CONFIG",
            "metrics.csv,                   CONFIG",
            "metrics.tsv,                   CONFIG",
            "app.properties,               CONFIG",
            "build.gradle.kts,             BUILD_FILE",
            "Dockerfile,                    BUILD_FILE",
            "Makefile,                      BUILD_FILE",
    })
    void classify_sourceType(String path, SourceType expected) {
        SourceIdentity id = SourceClassifier.classify(path);
        assertEquals(expected, id.type());
    }

    // ── MediaType mapping ──

    @Test
    void javaFile_isTextual() {
        assertEquals(MediaType.TEXTUAL, SourceClassifier.classify("Foo.java").mediaType());
    }

    @Test
    void yamlFile_isStructured() {
        assertEquals(MediaType.STRUCTURED, SourceClassifier.classify("config.yml").mediaType());
    }

    @Test
    void jsonFile_isStructured() {
        assertEquals(MediaType.STRUCTURED, SourceClassifier.classify("data.json").mediaType());
    }

    @Test
    void markdownFile_isTextual() {
        assertEquals(MediaType.TEXTUAL, SourceClassifier.classify("README.md").mediaType());
    }

    // ── SourceFormat passthrough ──

    @Test
    void classify_preservesFormat() {
        SourceIdentity id = SourceClassifier.classify("src/main/java/Foo.java");
        assertEquals(SourceFormat.JAVA, id.format());
    }

    // ── Path preservation ──

    @Test
    void classify_preservesPath() {
        String path = "src/main/java/Foo.java";
        SourceIdentity id = SourceClassifier.classify(path);
        assertEquals(path, id.path());
    }

    // ── Edge cases ──

    @Test
    void nullPath_returnsUnclassified() {
        SourceIdentity id = SourceClassifier.classify(null);
        assertEquals(SourceType.UNKNOWN, id.type());
        assertEquals(SourceFormat.UNKNOWN, id.format());
        assertEquals(MediaType.UNKNOWN, id.mediaType());
    }

    @Test
    void blankPath_returnsUnclassified() {
        SourceIdentity id = SourceClassifier.classify("   ");
        assertEquals(SourceType.UNKNOWN, id.type());
    }

    @Test
    void unknownExtension_returnsUnknown() {
        SourceIdentity id = SourceClassifier.classify("archive.tar.gz");
        assertEquals(SourceType.UNKNOWN, id.type());
        assertFalse(id.isClassified());
    }

    // ── typeForFormat completeness ──

    @Test
    void nullFormat_returnsUnknown() {
        assertEquals(SourceType.UNKNOWN, SourceClassifier.typeForFormat(null));
    }

    @Test
    void everyFormat_hasMapping() {
        for (SourceFormat f : SourceFormat.values()) {
            assertNotNull(SourceClassifier.typeForFormat(f),
                    "Missing typeForFormat mapping for " + f);
        }
    }
}

