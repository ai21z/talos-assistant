package dev.talos.core.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionService;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.safety.ProtectedWorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexerPolicyMetadataTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void index_missing_metadata_is_treated_dirty() {
        Indexer indexer = new Indexer(new Config(null));

        assertFalse(indexer.isPolicyMetadataCurrent(tempDir));
    }

    @Test
    void indexer_uses_safety_path_policy_version_for_protected_content_ownership() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/talos/core/index/Indexer.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(source.contains("import dev.talos.safety.ProtectedWorkspacePaths;"), source);
        assertTrue(source.contains("ProtectedWorkspacePaths.POLICY_VERSION"), source);
        assertFalse(source.contains("dev.talos.runtime.policy.ProtectedContentPolicy"), source);
        assertFalse(baseline.contains(
                        "core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java"
                                + "|dev.talos.runtime.policy.ProtectedContentPolicy"),
                baseline);
    }

    @Test
    void index_metadata_written_on_reindex() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "public text\n");
        Indexer indexer = new Indexer(new Config(null));

        indexer.index(tempDir, true);

        Path metadata = indexer.policyMetadataFile(tempDir);
        assertTrue(Files.exists(metadata));
        String text = Files.readString(metadata);
        assertTrue(text.contains(ProtectedWorkspacePaths.POLICY_VERSION));
        assertTrue(text.contains(FileCapabilityPolicy.POLICY_VERSION));
        assertTrue(text.contains(DocumentExtractionService.EXTRACTION_POLICY_VERSION));
        assertTrue(indexer.isPolicyMetadataCurrent(tempDir));
    }

    @Test
    void index_old_privacy_policy_version_is_dirty() throws Exception {
        Indexer indexer = new Indexer(new Config(null));
        Path metadata = indexer.policyMetadataFile(tempDir);
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, """
                {"schemaVersion":1,"privacyPolicyVersion":"old","fileCapabilityPolicyVersion":"old","ragConfigHash":"old"}
                """);

        assertFalse(indexer.isPolicyMetadataCurrent(tempDir));
    }

    @Test
    void index_metadata_from_previous_glob_semantics_is_dirty_until_rebuilt() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "public text\n");
        Indexer indexer = new Indexer(new Config(null));
        indexer.index(tempDir, true);
        Path metadata = indexer.policyMetadataFile(tempDir);

        @SuppressWarnings("unchecked")
        Map<String, Object> previous = JSON.readValue(metadata.toFile(), Map.class);
        assertTrue(indexer.isPolicyMetadataCurrent(tempDir));

        previous.put("schemaVersion", 3);
        previous.remove("fileGlobSemanticsVersion");
        JSON.writerWithDefaultPrettyPrinter().writeValue(metadata.toFile(), previous);

        assertFalse(
                indexer.isPolicyMetadataCurrent(tempDir),
                "metadata created before the portable leading-recursive glob semantics must be dirty");

        indexer.index(tempDir, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> rebuilt = JSON.readValue(metadata.toFile(), Map.class);
        assertEquals(4, ((Number) rebuilt.get("schemaVersion")).intValue());
        assertEquals(
                "native-leading-recursive-zero-directory-v1",
                rebuilt.get("fileGlobSemanticsVersion"));
        assertTrue(indexer.isPolicyMetadataCurrent(tempDir));

        indexer.index(tempDir, false);

        assertEquals(
                1,
                indexer.getLastRunStats().getFilesSkipped(),
                "the metadata-driven rebuild must retain hashes for the next incremental run");
    }

    @Test
    void index_metadata_with_wrong_glob_semantics_identity_is_dirty() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "public text\n");
        Indexer indexer = new Indexer(new Config(null));
        indexer.index(tempDir, true);
        Path metadata = indexer.policyMetadataFile(tempDir);

        @SuppressWarnings("unchecked")
        Map<String, Object> wrongSemantics = JSON.readValue(metadata.toFile(), Map.class);
        wrongSemantics.put("schemaVersion", 4);
        wrongSemantics.put("fileGlobSemanticsVersion", "host-native-leading-recursive-v0");
        JSON.writerWithDefaultPrettyPrinter().writeValue(metadata.toFile(), wrongSemantics);

        assertFalse(indexer.isPolicyMetadataCurrent(tempDir));
    }
}
