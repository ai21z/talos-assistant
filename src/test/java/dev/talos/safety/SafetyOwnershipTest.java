package dev.talos.safety;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyOwnershipTest {
    private static final Path MAIN_SAFETY_DIR = Path.of("src/main/java/dev/talos/safety");
    private static final List<String> SAFE_LOG_CALL_SITES = List.of(
            "src/main/java/dev/talos/core/embed/EmbeddingsClient.java",
            "src/main/java/dev/talos/core/index/Indexer.java",
            "src/main/java/dev/talos/core/index/LuceneStore.java",
            "src/main/java/dev/talos/core/rag/RagService.java",
            "src/main/java/dev/talos/engine/compat/CompatChatClient.java",
            "src/main/java/dev/talos/engine/ollama/OllamaChatClient.java",
            "src/main/java/dev/talos/tools/impl/ContentVerifier.java",
            "src/main/java/dev/talos/tools/impl/FileEditTool.java",
            "src/main/java/dev/talos/tools/impl/FileWriteTool.java");

    @Test
    void sinkSafetyPackageOwnsSafeLogFormatterAndPurePrimitives() throws Exception {
        assertTrue(Files.exists(MAIN_SAFETY_DIR.resolve("SafeLogFormatter.java")));
        assertTrue(Files.exists(MAIN_SAFETY_DIR.resolve("ProtectedContentSanitizer.java")));
        assertTrue(Files.exists(MAIN_SAFETY_DIR.resolve("ProtectedPathTokens.java")));
        assertTrue(Files.exists(MAIN_SAFETY_DIR.resolve("ProtectedWorkspacePaths.java")));
        assertTrue(Files.exists(MAIN_SAFETY_DIR.resolve("ProtectedContentMessages.java")));
        assertFalse(Files.exists(Path.of("src/main/java/dev/talos/runtime/policy/SafeLogFormatter.java")));
    }

    @Test
    void safetyPackageDoesNotImportTalosLayers() throws Exception {
        assertTrue(Files.exists(MAIN_SAFETY_DIR), "missing dev.talos.safety package");
        try (var paths = Files.walk(MAIN_SAFETY_DIR)) {
            var offenders = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> {
                        try {
                            return Files.readAllLines(path).stream()
                                    .map(String::strip)
                                    .filter(line -> line.startsWith("import dev.talos."))
                                    .map(line -> path + ": " + line);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
            assertTrue(offenders.isEmpty(), offenders.toString());
        }
    }

    @Test
    void lowerLayerSinkSafeCallSitesUseNeutralSafetyFormatter() throws Exception {
        for (String path : SAFE_LOG_CALL_SITES) {
            String source = Files.readString(Path.of(path));
            assertTrue(source.contains("import dev.talos.safety.SafeLogFormatter;"), path);
            assertFalse(source.contains("dev.talos.runtime.policy.SafeLogFormatter"), path);
        }

        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));
        assertFalse(baseline.contains("dev.talos.runtime.policy.SafeLogFormatter"), baseline);
    }
}
