package dev.talos.spi.types;

/**
 * Content modality of a source, describing how it should be processed.
 *
 * <p>V1 only deals with {@link #TEXTUAL} and {@link #STRUCTURED} sources.
 * {@link #VISUAL} and {@link #MIXED} are placeholders for post-V1 image
 * and multi-modal support.
 */
public enum MediaType {

    /** Plain text or markup that can be chunked and indexed as-is. */
    TEXTUAL,

    /** Structured data formats (JSON, XML, CSV) that may benefit from schema-aware handling. */
    STRUCTURED,

    /** Image or visual content (screenshots, diagrams). Not V1. */
    VISUAL,

    /** Mixed content (e.g. PDF with embedded images). Not V1. */
    MIXED,

    /** Media type could not be determined. */
    UNKNOWN;

    /**
     * Derive the media type from a {@link SourceFormat}.
     *
     * @param format the source format
     * @return the inferred media type, never null
     */
    public static MediaType forFormat(SourceFormat format) {
        if (format == null) return UNKNOWN;
        return switch (format) {
            // Code and markup are textual
            case JAVA, KOTLIN, PYTHON, JAVASCRIPT, TYPESCRIPT, GO, RUST, CPP, C, C_HEADER,
                 RUBY, SHELL, SCALA, GROOVY,
                 MARKDOWN, PLAIN_TEXT, RST, ADOC, HTML,
                 PROPERTIES, TOML, INI, ENV,
                 GRADLE_KTS, GRADLE, DOCKERFILE, MAKEFILE -> TEXTUAL;

            // Data interchange formats are structured
            case JSON, XML, YAML, CSV, TSV, MAVEN_POM -> STRUCTURED;

            case UNKNOWN -> UNKNOWN;
        };
    }
}

