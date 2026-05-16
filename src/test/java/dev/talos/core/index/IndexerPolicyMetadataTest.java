package dev.talos.core.index;

import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionService;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexerPolicyMetadataTest {

    @TempDir
    Path tempDir;

    @Test
    void index_missing_metadata_is_treated_dirty() {
        Indexer indexer = new Indexer(new Config(null));

        assertFalse(indexer.isPolicyMetadataCurrent(tempDir));
    }

    @Test
    void index_metadata_written_on_reindex() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "public text\n");
        Indexer indexer = new Indexer(new Config(null));

        indexer.index(tempDir, true);

        Path metadata = indexer.policyMetadataFile(tempDir);
        assertTrue(Files.exists(metadata));
        String text = Files.readString(metadata);
        assertTrue(text.contains(ProtectedContentPolicy.POLICY_VERSION));
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
}
