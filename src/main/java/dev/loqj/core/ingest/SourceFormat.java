package dev.loqj.core.ingest;

import java.util.Locale;
import java.util.Map;

/**
 * Concrete technical format of a source, typically derived from file extension.
 *
 * <p>V1 covers the formats already handled by {@link Chunker} and
 * {@code ParserUtil}: programming languages, markup, configuration, and
 * build-system files. Additional formats (PDF, DOCX, XLSX, etc.) will be
 * added as parser support lands.
 *
 * @see SourceClassifier
 */
public enum SourceFormat {

    // --- Programming languages ---
    JAVA, KOTLIN, PYTHON, JAVASCRIPT, TYPESCRIPT, GO, RUST, CPP, C, C_HEADER,
    RUBY, SHELL, SCALA, GROOVY,

    // --- Markup / documentation ---
    MARKDOWN, PLAIN_TEXT, RST, ADOC, HTML,

    // --- Configuration / data ---
    YAML, JSON, XML, PROPERTIES, TOML, INI, ENV, CSV,

    // --- Build / infrastructure ---
    GRADLE_KTS, GRADLE, MAVEN_POM, DOCKERFILE, MAKEFILE,

    // --- Fallback ---
    UNKNOWN;

    private static final Map<String, SourceFormat> BY_EXT = Map.ofEntries(
            Map.entry("java",       JAVA),
            Map.entry("kt",         KOTLIN),
            Map.entry("kts",        KOTLIN),
            Map.entry("py",         PYTHON),
            Map.entry("js",         JAVASCRIPT),
            Map.entry("mjs",        JAVASCRIPT),
            Map.entry("cjs",        JAVASCRIPT),
            Map.entry("ts",         TYPESCRIPT),
            Map.entry("tsx",        TYPESCRIPT),
            Map.entry("jsx",        JAVASCRIPT),
            Map.entry("go",         GO),
            Map.entry("rs",         RUST),
            Map.entry("cpp",        CPP),
            Map.entry("cc",         CPP),
            Map.entry("cxx",        CPP),
            Map.entry("c",          C),
            Map.entry("h",          C_HEADER),
            Map.entry("hpp",        C_HEADER),
            Map.entry("rb",         RUBY),
            Map.entry("sh",         SHELL),
            Map.entry("bash",       SHELL),
            Map.entry("zsh",        SHELL),
            Map.entry("bat",        SHELL),
            Map.entry("ps1",        SHELL),
            Map.entry("scala",      SCALA),
            Map.entry("groovy",     GROOVY),
            Map.entry("md",         MARKDOWN),
            Map.entry("markdown",   MARKDOWN),
            Map.entry("txt",        PLAIN_TEXT),
            Map.entry("text",       PLAIN_TEXT),
            Map.entry("rst",        RST),
            Map.entry("adoc",       ADOC),
            Map.entry("html",       HTML),
            Map.entry("htm",        HTML),
            Map.entry("yaml",       YAML),
            Map.entry("yml",        YAML),
            Map.entry("json",       JSON),
            Map.entry("xml",        XML),
            Map.entry("properties", PROPERTIES),
            Map.entry("toml",       TOML),
            Map.entry("ini",        INI),
            Map.entry("env",        ENV),
            Map.entry("csv",        CSV),
            Map.entry("cfg",        INI),
            Map.entry("conf",       INI)
    );

    private static final Map<String, SourceFormat> BY_NAME = Map.of(
            "dockerfile",          DOCKERFILE,
            "makefile",            MAKEFILE,
            "gnumakefile",         MAKEFILE,
            "rakefile",            RUBY
    );

    /**
     * Derive the format from a relative file path or file name.
     *
     * @param path relative path or bare file name (e.g. "src/Main.java")
     * @return the resolved format, never null
     */
    public static SourceFormat fromPath(String path) {
        if (path == null || path.isBlank()) return UNKNOWN;

        String normalized = path.replace('\\', '/');

        // Handle compound names before generic extension lookup
        if (normalized.endsWith(".gradle.kts")) return GRADLE_KTS;
        if (normalized.endsWith(".gradle"))     return GRADLE;
        if (normalized.endsWith("pom.xml"))     return MAVEN_POM;

        // Try extension
        int dot = normalized.lastIndexOf('.');
        if (dot >= 0 && dot < normalized.length() - 1) {
            String ext = normalized.substring(dot + 1).toLowerCase(Locale.ROOT);
            SourceFormat f = BY_EXT.get(ext);
            if (f != null) return f;
        }

        // Try well-known file names (Dockerfile, Makefile, etc.)
        int slash = normalized.lastIndexOf('/');
        String fileName = (slash >= 0 ? normalized.substring(slash + 1) : normalized)
                .toLowerCase(Locale.ROOT);
        SourceFormat byName = BY_NAME.get(fileName);
        if (byName != null) return byName;

        return UNKNOWN;
    }
}

