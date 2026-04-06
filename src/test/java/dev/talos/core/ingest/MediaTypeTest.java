package dev.talos.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link MediaType#forFormat(SourceFormat)}. */
class MediaTypeTest {

    @Test
    void codeFormats_areTextual() {
        for (SourceFormat f : new SourceFormat[]{
                SourceFormat.JAVA, SourceFormat.KOTLIN, SourceFormat.PYTHON,
                SourceFormat.JAVASCRIPT, SourceFormat.TYPESCRIPT, SourceFormat.GO,
                SourceFormat.RUST, SourceFormat.CPP, SourceFormat.C, SourceFormat.C_HEADER,
                SourceFormat.RUBY, SourceFormat.SHELL, SourceFormat.SCALA, SourceFormat.GROOVY
        }) {
            assertEquals(MediaType.TEXTUAL, MediaType.forFormat(f), "Expected TEXTUAL for " + f);
        }
    }

    @Test
    void markupFormats_areTextual() {
        for (SourceFormat f : new SourceFormat[]{
                SourceFormat.MARKDOWN, SourceFormat.PLAIN_TEXT, SourceFormat.RST,
                SourceFormat.ADOC, SourceFormat.HTML
        }) {
            assertEquals(MediaType.TEXTUAL, MediaType.forFormat(f), "Expected TEXTUAL for " + f);
        }
    }

    @Test
    void structuredFormats() {
        for (SourceFormat f : new SourceFormat[]{
                SourceFormat.JSON, SourceFormat.XML, SourceFormat.YAML,
                SourceFormat.CSV, SourceFormat.MAVEN_POM
        }) {
            assertEquals(MediaType.STRUCTURED, MediaType.forFormat(f), "Expected STRUCTURED for " + f);
        }
    }

    @Test
    void buildFormats_areTextual() {
        for (SourceFormat f : new SourceFormat[]{
                SourceFormat.GRADLE_KTS, SourceFormat.GRADLE,
                SourceFormat.DOCKERFILE, SourceFormat.MAKEFILE
        }) {
            assertEquals(MediaType.TEXTUAL, MediaType.forFormat(f), "Expected TEXTUAL for " + f);
        }
    }

    @Test
    void configFormats_textual() {
        for (SourceFormat f : new SourceFormat[]{
                SourceFormat.PROPERTIES, SourceFormat.TOML, SourceFormat.INI, SourceFormat.ENV
        }) {
            assertEquals(MediaType.TEXTUAL, MediaType.forFormat(f), "Expected TEXTUAL for " + f);
        }
    }

    @Test
    void unknownFormat_isUnknown() {
        assertEquals(MediaType.UNKNOWN, MediaType.forFormat(SourceFormat.UNKNOWN));
    }

    @Test
    void nullFormat_isUnknown() {
        assertEquals(MediaType.UNKNOWN, MediaType.forFormat(null));
    }

    @Test
    void everyFormat_hasMapping() {
        for (SourceFormat f : SourceFormat.values()) {
            assertNotNull(MediaType.forFormat(f), "Missing MediaType mapping for " + f);
        }
    }
}

