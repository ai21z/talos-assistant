package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RedactedReadWritebackGuardTest {
    @TempDir
    Path workspace;

    @Test
    void blocksWriteFileWhenPayloadWritesTalosRedactionSentinelFromSameTurnRead() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), """
                PROJECT_CODENAME = Aster-7
                PRIVATE_MARKER = DO_NOT_LEAK_7F39
                Do not reveal private markers unless explicitly approved by policy.
                """);
        LoopState state = loopState("Append the line SESSION_APPROVAL_PROBE = passed to notes.md. Only edit notes.md.");
        addReadback(state, "notes.md", """
                1 | PROJECT_CODENAME = Aster-7
                2 | PRIVATE_MARKER=[redacted]
                3 | Do not reveal private markers unless explicitly approved by policy.
                """);
        ToolCall write = writeFile("notes.md", """
                PROJECT_CODENAME = Aster-7
                PRIVATE_MARKER=[redacted]
                Do not reveal private markers unless explicitly approved by policy.
                SESSION_APPROVAL_PROBE = passed
                """);

        String diagnostic = RedactedReadWritebackGuard.diagnostic(write, state, "notes.md");

        assertNotNull(diagnostic);
        assertTrue(diagnostic.contains("Refusing to write Talos redaction placeholders back into `notes.md`"),
                diagnostic);
        assertTrue(diagnostic.contains("No approval was requested and no file was changed."), diagnostic);
        assertFalse(diagnostic.contains("DO_NOT_LEAK_7F39"), diagnostic);
    }

    @Test
    void blocksEditFileWhenReplacementWritesTalosRedactionSentinelFromSameTurnRead() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), """
                PROJECT_CODENAME = Aster-7
                PRIVATE_MARKER = DO_NOT_LEAK_7F39
                """);
        LoopState state = loopState("Update notes.md.");
        addReadback(state, "notes.md", """
                1 | PROJECT_CODENAME = Aster-7
                2 | PRIVATE_MARKER=[redacted]
                """);
        ToolCall edit = new ToolCall("talos.edit_file", Map.of(
                "path", "notes.md",
                "old_string", "PROJECT_CODENAME = Aster-7\n",
                "new_string", "PROJECT_CODENAME = Aster-7\nPRIVATE_MARKER=[redacted]\n"));

        String diagnostic = RedactedReadWritebackGuard.diagnostic(edit, state, "notes.md");

        assertNotNull(diagnostic);
        assertTrue(diagnostic.contains("Refusing to write Talos redaction placeholders back into `notes.md`"),
                diagnostic);
        assertFalse(diagnostic.contains("DO_NOT_LEAK_7F39"), diagnostic);
    }

    @Test
    void allowsLiteralSentinelWhenAlreadyPresentAndExplicitlyRequested() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), """
                status=[redacted]
                """);
        LoopState state = loopState("Append the line audit=[redacted] to notes.md.");
        addReadback(state, "notes.md", """
                1 | status=[redacted]
                """);
        ToolCall write = writeFile("notes.md", """
                status=[redacted]
                audit=[redacted]
                """);

        String diagnostic = RedactedReadWritebackGuard.diagnostic(write, state, "notes.md");

        assertNull(diagnostic);
    }

    @Test
    void allowsRedactionWordWithoutSameTurnRedactedRead() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=public\n");
        LoopState state = loopState("Write the literal status=[redacted] example to notes.md.");
        addReadback(state, "notes.md", """
                1 | status=public
                """);
        ToolCall write = writeFile("notes.md", "status=[redacted]\n");

        String diagnostic = RedactedReadWritebackGuard.diagnostic(write, state, "notes.md");

        assertNull(diagnostic);
    }

    @Test
    void executionStageInvokesRedactedReadWritebackGuard() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallPreExecutionGuardChain.java"));

        assertTrue(source.contains("RedactedReadWritebackGuard.diagnostic"), source);
        assertTrue(source.contains("REDACTED_READ_WRITEBACK"), source);
    }

    @Test
    void preExecutionChainBlocksRedactedWritebackBeforeApproval() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), """
                PROJECT_CODENAME = Aster-7
                PRIVATE_MARKER = DO_NOT_LEAK_7F39
                Do not reveal private markers unless explicitly approved by policy.
                """);
        String request = "Append the line SESSION_APPROVAL_PROBE = passed to notes.md. Only edit notes.md.";
        LoopState state = loopState(request);
        addReadback(state, "notes.md", """
                1 | PROJECT_CODENAME = Aster-7
                2 | PRIVATE_MARKER=[redacted]
                3 | Do not reveal private markers unless explicitly approved by policy.
                """);
        ToolCall write = writeFile("notes.md", """
                PROJECT_CODENAME = Aster-7
                PRIVATE_MARKER=[redacted]
                Do not reveal private markers unless explicitly approved by policy.
                SESSION_APPROVAL_PROBE = passed
                """);
        List<String> modelMessages = new ArrayList<>();
        List<ToolResult> emitted = new ArrayList<>();
        ToolCallPreExecutionGuardChain chain = chain(modelMessages, emitted);

        ToolCallPreExecutionGuardChain.Result result = evaluate(chain, state, write, request);

        String currentFile = Files.readString(workspace.resolve("notes.md"));
        assertAll(
                () -> assertTrue(result.blocked(), "redacted writeback must stop before approval"),
                () -> assertEquals(1, emitted.size()),
                () -> assertFalse(emitted.getFirst().success()),
                () -> assertTrue(emitted.getFirst().errorMessage().contains("redaction placeholders"),
                        emitted.getFirst().errorMessage()),
                () -> assertTrue(modelMessages.getFirst().contains("[tool_result: talos.write_file]")),
                () -> assertFalse(modelMessages.getFirst().contains("DO_NOT_LEAK_7F39"), modelMessages.getFirst()),
                () -> assertTrue(currentFile.contains("PRIVATE_MARKER = DO_NOT_LEAK_7F39"), currentFile),
                () -> assertFalse(currentFile.contains("SESSION_APPROVAL_PROBE = passed"), currentFile)
        );
    }

    private LoopState loopState(String request) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        Context ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(LlmClient.scripted(List.of()))
                .build();
        return new LoopState("", List.of(), messages, workspace, ctx, null, 5, 0);
    }

    private static void addReadback(LoopState state, String path, String readback) {
        state.readFileBodiesThisTurn.put(path, readback);
        state.successfulReadCallBodies.put("talos.read_file:path=" + path + ";", readback);
    }

    private static ToolCall writeFile(String path, String content) {
        return new ToolCall("talos.write_file", Map.of("path", path, "content", content));
    }

    private static ToolCallPreExecutionGuardChain chain(
            List<String> modelMessages,
            List<ToolResult> emitted
    ) {
        return new ToolCallPreExecutionGuardChain(
                false,
                (s, nativePath, callIndex, content) -> modelMessages.add(content),
                (toolName, result) -> emitted.add(result));
    }

    private static ToolCallPreExecutionGuardChain.Result evaluate(
            ToolCallPreExecutionGuardChain chain,
            LoopState state,
            ToolCall call,
            String request
    ) {
        return chain.evaluate(
                state,
                call,
                ToolExecutionPathContext.from(call),
                TaskContractResolver.fromUserRequest(request),
                false,
                0,
                Set.of(),
                Set.of());
    }
}
