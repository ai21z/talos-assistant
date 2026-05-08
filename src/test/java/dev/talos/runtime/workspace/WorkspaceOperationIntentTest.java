package dev.talos.runtime.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceOperationIntentTest {

    @Test
    void naturalMkdirPhrasesDetectMkdirIntent() {
        for (String request : List.of(
                "Create a new dir called workspace-notes.",
                "Create a new folder named audit-output.",
                "Make a new directory reports/daily.",
                "Can you create a folder called docs?")) {
            var intent = WorkspaceOperationIntent.detect(request);

            assertTrue(intent.isPresent(), request);
            assertEquals(WorkspaceOperationIntent.Kind.MKDIR, intent.get().kind(), request);
        }
    }
}
