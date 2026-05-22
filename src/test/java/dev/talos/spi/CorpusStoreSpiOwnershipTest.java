package dev.talos.spi;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CorpusStoreSpiOwnershipTest {
    @Test
    void corpusStoreHitExposesSpiOwnedChunkMetadata() {
        Class<?> metadataType = Arrays.stream(CorpusStore.Hit.class.getRecordComponents())
                .filter(component -> component.getName().equals("metadata"))
                .findFirst()
                .orElseThrow()
                .getType();

        assertEquals("dev.talos.spi.types.ChunkMetadata", metadataType.getName());
    }

    @Test
    void baselineDoesNotAcceptCoreMetadataInCorpusStoreSpiContract() throws Exception {
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertFalse(baseline.contains(
                "spi-no-upper-layers|src/main/java/dev/talos/spi/CorpusStore.java|"
                        + "dev.talos.core.ingest.ChunkMetadata"));
    }
}
