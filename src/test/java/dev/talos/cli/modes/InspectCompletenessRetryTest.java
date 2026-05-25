package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InspectCompletenessRetryTest {

    @Test
    void missingReadsIncludesLinkedScriptButSkipsProtectedAndExternalScripts(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <body>
                    <script src="https://cdn.example.invalid/app.js"></script>
                    <script src="//cdn.example.invalid/other.js"></script>
                    <script src=".env.secret.js"></script>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve(".env.secret.js"), "const secret = 'protected';\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('public');\n");
        ToolCallLoop.LoopResult loopResult = loopResult(
                "unused",
                1,
                1,
                List.of("talos.read_file"),
                List.of("index.html"),
                List.of(outcome("talos.read_file", "index.html")));

        List<String> missing = InspectCompletenessRetry.missingReads(workspace, loopResult);

        assertEquals(List.of("script.js"), missing);
    }

    @Test
    void retryMergesOriginalAndRetryReadEvidenceWithoutDuplicatingOriginalSummary(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html><body><script src="script.js"></script></body></html>
                """);
        Files.writeString(workspace.resolve("script.js"), "console.log('script evidence');\n");
        List<ChatMessage> messages = messages("Read the main files and verify the web page.");
        ToolCallLoop.LoopResult original = loopResult(
                "HTML-only answer.",
                1,
                1,
                List.of("talos.read_file"),
                List.of("index.html"),
                List.of(outcome("talos.read_file", "index.html")));
        Context ctx = context(workspace, "Script evidence answer.");
        AtomicReference<List<ChatMessage>> retryMessages = new AtomicReference<>();

        InspectCompletenessRetry.Result result = InspectCompletenessRetry.retryIfNeeded(
                "HTML-only answer.",
                messages,
                plan("Read the main files and verify the web page."),
                original,
                workspace,
                ctx,
                sentMessages -> {
                    retryMessages.set(List.copyOf(sentMessages));
                    return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                            "call_1",
                            "talos.read_file",
                            Map.of("path", "script.js"))));
                });

        assertEquals("Script evidence answer.", result.answer());
        assertNotNull(result.loopResult());
        assertEquals(List.of("index.html", "script.js"), result.loopResult().readPaths());
        assertEquals(List.of("talos.read_file", "talos.read_file"), result.loopResult().toolNames());
        assertEquals(2, result.loopResult().toolsInvoked());
        assertEquals(2, result.loopResult().iterations());
        assertEquals(1, countOccurrences(result.extraSummary(), "[Used "));
        assertTrue(result.extraSummary().contains("[Used 2 tool(s): talos.read_file | 2 iteration(s)]"),
                result.extraSummary());
        String prompt = retryMessages.get().get(3).content();
        assertTrue(prompt.contains("You started diagnosing the workspace"), prompt);
        assertTrue(prompt.contains("Read these files now before answering: script.js"), prompt);
    }

    private static CurrentTurnPlan plan(String request) {
        return CurrentTurnPlan.compatibility(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"),
                List.of("talos.read_file"),
                List.of());
    }

    private static Context context(Path workspace, String finalAnswer) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        return Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(finalAnswer)))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(processor, 5))
                .build();
    }

    private static List<ChatMessage> messages(String request) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static ToolCallLoop.LoopResult loopResult(
            String finalAnswer,
            int iterations,
            int toolsInvoked,
            List<String> toolNames,
            List<String> readPaths,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        return new ToolCallLoop.LoopResult(
                finalAnswer,
                iterations,
                toolsInvoked,
                toolNames,
                List.of(),
                0,
                0,
                false,
                0,
                readPaths,
                0,
                0,
                0,
                0,
                outcomes);
    }

    private static ToolCallLoop.ToolOutcome outcome(String toolName, String target) {
        return new ToolCallLoop.ToolOutcome(toolName, target, true, false, false, "read " + target, "");
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while (value != null && (index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
