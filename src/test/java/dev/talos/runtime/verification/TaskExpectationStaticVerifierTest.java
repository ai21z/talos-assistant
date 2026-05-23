package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExpectationStaticVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void literalExpectationResultAndTraceStayRedacted() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "AFTER");
        LocalTurnTraceCapture.begin(
                "trc-t387-literal",
                "session-test",
                1,
                "2026-05-23T00:00:00Z",
                "workspace-hash",
                "auto",
                "ollama",
                "qwen2.5-coder:14b",
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");

        try {
            TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                    TaskContractResolver.fromUserRequest(
                            "Overwrite index.html with exactly AFTER. Use talos.write_file."),
                    workspace,
                    List.of(successfulWrite("index.html", VerificationStatus.PASS)),
                    true);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(result.verifiedAny());
            assertFalse(result.replacementRequired());
            assertFalse(result.appendLineRequired());
            assertFalse(result.bulletCountRequired());
            assertTrue(result.problems().isEmpty(), result.problems().toString());
            assertEquals(
                    List.of("index.html: literal content matched requested exact content."),
                    result.facts());

            var event = trace.events().stream()
                    .filter(e -> e.type().equals("EXPECTATION_VERIFIED"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("LITERAL_CONTENT", event.data().get("kind"));
            assertEquals("PASSED", event.data().get("status"));
            assertEquals("index.html", event.data().get("pathHint"));
            assertTrue(event.data().containsKey("expectedHash"));
            assertTrue(event.data().containsKey("observedHash"));
            assertFalse(event.data().containsValue("AFTER"));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    private static ToolCallLoop.ToolOutcome successfulWrite(String path, VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file", path, true, true, false,
                "wrote " + path, "", verificationStatus);
    }
}
