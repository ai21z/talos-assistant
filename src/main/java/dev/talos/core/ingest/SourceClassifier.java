package dev.talos.core.ingest;

/**
 * Classifies a file path into a full {@link SourceIdentity} by deriving
 * {@link SourceFormat}, {@link SourceType}, and {@link MediaType} from
 * the path's extension and file name.
 *
 * <p>This is the single entry point for source classification at ingest time.
 * {@link Chunker} calls it to attach identity to every {@link ParsedChunk}.
 *
 * <p>Stateless utility — all methods are static.
 */
public final class SourceClassifier {

    private SourceClassifier() {} // utility

    /**
     * Classify a file path into a {@link SourceIdentity}.
     *
     * @param relPath relative path within the workspace (e.g. "src/main/java/Foo.java")
     * @return a fully-classified identity, never null; unknown paths get {@link SourceType#UNKNOWN}
     */
    public static SourceIdentity classify(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return SourceIdentity.unclassified("");
        }

        SourceFormat format = SourceFormat.fromPath(relPath);
        SourceType type = typeForFormat(format);
        MediaType media = MediaType.forFormat(format);

        return new SourceIdentity(relPath, type, format, media);
    }

    /**
     * Map a {@link SourceFormat} to its semantic {@link SourceType}.
     */
    static SourceType typeForFormat(SourceFormat format) {
        if (format == null) return SourceType.UNKNOWN;
        return switch (format) {
            case JAVA, KOTLIN, PYTHON, JAVASCRIPT, TYPESCRIPT, GO, RUST, CPP, C, C_HEADER,
                 RUBY, SHELL, SCALA, GROOVY -> SourceType.CODE_FILE;

            case MARKDOWN, PLAIN_TEXT, RST, ADOC, HTML -> SourceType.DOCUMENT;

            case YAML, JSON, XML, PROPERTIES, TOML, INI, ENV, CSV -> SourceType.CONFIG;

            case GRADLE_KTS, GRADLE, MAVEN_POM, DOCKERFILE, MAKEFILE -> SourceType.BUILD_FILE;

            case UNKNOWN -> SourceType.UNKNOWN;
        };
    }
}

