package dev.talos.core.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link SourceFormat#fromPath(String)}. */
class SourceFormatTest {

    // ── Programming languages ──

    @ParameterizedTest
    @CsvSource({
            "src/main/java/Foo.java,    JAVA",
            "lib/Bar.kt,                KOTLIN",
            "build.gradle.kts,          GRADLE_KTS",
            "app.py,                    PYTHON",
            "index.js,                  JAVASCRIPT",
            "index.mjs,                 JAVASCRIPT",
            "index.cjs,                 JAVASCRIPT",
            "App.tsx,                   TYPESCRIPT",
            "App.ts,                    TYPESCRIPT",
            "Component.jsx,            JAVASCRIPT",
            "main.go,                   GO",
            "lib.rs,                    RUST",
            "util.cpp,                  CPP",
            "util.cc,                   CPP",
            "util.cxx,                  CPP",
            "util.c,                    C",
            "util.h,                    C_HEADER",
            "util.hpp,                  C_HEADER",
            "app.rb,                    RUBY",
            "deploy.sh,                 SHELL",
            "deploy.bash,              SHELL",
            "deploy.zsh,               SHELL",
            "run.bat,                   SHELL",
            "setup.ps1,                 SHELL",
            "App.scala,                 SCALA",
            "App.groovy,                GROOVY",
    })
    void codeFiles(String path, SourceFormat expected) {
        assertEquals(expected, SourceFormat.fromPath(path));
    }

    // ── Markup / documentation ──

    @ParameterizedTest
    @CsvSource({
            "README.md,      MARKDOWN",
            "notes.markdown, MARKDOWN",
            "log.txt,        PLAIN_TEXT",
            "log.text,       PLAIN_TEXT",
            "guide.rst,      RST",
            "guide.adoc,     ADOC",
            "index.html,     HTML",
            "index.htm,      HTML",
    })
    void markupFiles(String path, SourceFormat expected) {
        assertEquals(expected, SourceFormat.fromPath(path));
    }

    // ── Configuration / data ──

    @ParameterizedTest
    @CsvSource({
            "config.yaml,       YAML",
            "config.yml,        YAML",
            "package.json,      JSON",
            "settings.xml,      XML",
            "app.properties,    PROPERTIES",
            "Cargo.toml,        TOML",
            "settings.ini,      INI",
            ".env,              ENV",
            "data.csv,          CSV",
            "data.tsv,          TSV",
            "app.cfg,           INI",
            "app.conf,          INI",
    })
    void configFiles(String path, SourceFormat expected) {
        assertEquals(expected, SourceFormat.fromPath(path));
    }

    // ── Build / infrastructure ──

    @Test
    void gradleKts() {
        assertEquals(SourceFormat.GRADLE_KTS, SourceFormat.fromPath("build.gradle.kts"));
    }

    @Test
    void gradle() {
        assertEquals(SourceFormat.GRADLE, SourceFormat.fromPath("build.gradle"));
    }

    @Test
    void mavenPom() {
        assertEquals(SourceFormat.MAVEN_POM, SourceFormat.fromPath("pom.xml"));
    }

    @Test
    void dockerfile() {
        assertEquals(SourceFormat.DOCKERFILE, SourceFormat.fromPath("Dockerfile"));
    }

    @Test
    void makefile() {
        assertEquals(SourceFormat.MAKEFILE, SourceFormat.fromPath("Makefile"));
    }

    @Test
    void gnuMakefile() {
        assertEquals(SourceFormat.MAKEFILE, SourceFormat.fromPath("GNUmakefile"));
    }

    @Test
    void rakefile() {
        assertEquals(SourceFormat.RUBY, SourceFormat.fromPath("Rakefile"));
    }

    // ── Edge cases ──

    @Test
    void nullPath_returnsUnknown() {
        assertEquals(SourceFormat.UNKNOWN, SourceFormat.fromPath(null));
    }

    @Test
    void blankPath_returnsUnknown() {
        assertEquals(SourceFormat.UNKNOWN, SourceFormat.fromPath("   "));
    }

    @Test
    void unknownExtension_returnsUnknown() {
        assertEquals(SourceFormat.UNKNOWN, SourceFormat.fromPath("data.xyz"));
    }

    @Test
    void noExtension_noKnownName_returnsUnknown() {
        assertEquals(SourceFormat.UNKNOWN, SourceFormat.fromPath("LICENSE"));
    }

    @Test
    void backslashPaths_normalized() {
        assertEquals(SourceFormat.JAVA, SourceFormat.fromPath("src\\main\\java\\Foo.java"));
    }

    @Test
    void nestedMavenPom() {
        assertEquals(SourceFormat.MAVEN_POM, SourceFormat.fromPath("modules/core/pom.xml"));
    }

    @Test
    void nestedDockerfile() {
        assertEquals(SourceFormat.DOCKERFILE, SourceFormat.fromPath("docker/Dockerfile"));
    }
}

