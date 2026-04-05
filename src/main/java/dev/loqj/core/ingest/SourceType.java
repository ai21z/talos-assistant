package dev.loqj.core.ingest;

/**
 * Semantic category of a source within a workspace.
 *
 * <p>V1 scope covers code, text documents, configuration, and build files.
 * Additional types (REPOSITORY, EMAIL_THREAD, WEBPAGE, IMAGE, etc.) will be
 * added in later phases as source support expands.
 *
 * @see SourceClassifier
 */
public enum SourceType {

    /** Source code file (Java, Python, JS, etc.). */
    CODE_FILE,

    /** Text document (Markdown, plain text, reStructuredText, AsciiDoc). */
    DOCUMENT,

    /** Configuration or data file (YAML, JSON, XML, properties, TOML). */
    CONFIG,

    /** Build/infrastructure file (Dockerfile, Gradle, Maven POM, Makefile). */
    BUILD_FILE,

    /** Source type could not be determined. */
    UNKNOWN
}

