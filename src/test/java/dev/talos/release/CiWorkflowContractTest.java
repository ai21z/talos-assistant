package dev.talos.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiWorkflowContractTest {

    @Test
    @DisplayName("CI checkout fetches full history for wiki commit liveness")
    void ciCheckoutFetchesFullHistoryForWikiCommitLiveness() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "beta-dev-ci.yml"));
        long checkoutUses = Pattern.compile("uses:\\s*actions/checkout@").matcher(workflow).results().count();
        long fullHistoryCheckouts = Pattern.compile(
                "uses:\\s*actions/checkout@[^\\r\\n]+\\R\\s+with:\\R\\s+fetch-depth:\\s*0")
                .matcher(workflow)
                .results()
                .count();

        assertTrue(checkoutUses > 0, "workflow must use actions/checkout");
        assertEquals(checkoutUses, fullHistoryCheckouts,
                "every checkout must fetch full history so last_verified_commit git cat-file checks work in CI");
    }
}
