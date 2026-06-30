package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppendLinePreApprovalGuardTest {
    @TempDir
    Path workspace;

    @Test
    void invalidAppendLineWriteReturnsExactDiagnostic() {
        String request = "Read README.md, then append exactly this line to README.md: Release gate note";
        LoopState state = loopState(request);
        addReadback(state, "README.md", "1 | # Demo\n");
        ToolCall badWrite = writeFile("README.md", "Existing content from README.md\n\nRelease gate note");

        String diagnostic = AppendLinePreApprovalGuard.diagnostic(
                badWrite,
                state,
                TaskContractResolver.fromUserRequest(request),
                "README.md");

        assertEquals(
                "append-line write_file for README.md does not preserve the complete same-turn readback "
                        + "and append exactly `Release gate note`.",
                diagnostic);
    }

    @Test
    void validAppendLineWriteReturnsNoDiagnostic() {
        String request = "Read README.md, then append exactly this line to README.md: Release gate note";
        LoopState state = loopState(request);
        addReadback(state, "README.md", "1 | # Demo\n");
        ToolCall validWrite = writeFile("README.md", "# Demo\nRelease gate note\n");

        String diagnostic = AppendLinePreApprovalGuard.diagnostic(
                validWrite,
                state,
                TaskContractResolver.fromUserRequest(request),
                "README.md");

        assertNull(diagnostic);
    }

    @Test
    void validAppendLineWriteCanBeSteeredToEditFile() {
        String request = "Read README.md, then append exactly this line to README.md: Release gate note";
        LoopState state = loopState(request);
        addReadback(state, "README.md", "1 | # Demo\n");
        ToolCall validWrite = writeFile("README.md", "# Demo\nRelease gate note\n");

        ToolCall steered = AppendLinePreApprovalGuard.steeredEditFile(
                validWrite,
                state,
                TaskContractResolver.fromUserRequest(request),
                "README.md");

        assertNotNull(steered);
        assertEquals("talos.edit_file", steered.toolName());
        assertEquals("README.md", steered.param("path"));
        assertEquals("# Demo\n", steered.param("old_string"));
        assertEquals("# Demo\nRelease gate note\n", steered.param("new_string"));
    }

    @Test
    void genuineRewriteWriteIsNotSteeredToEditFile() {
        String request = "Rewrite README.md with a concise product overview.";
        LoopState state = loopState(request);
        addReadback(state, "README.md", "1 | # Demo\n");
        ToolCall rewrite = writeFile("README.md", "# New Product Overview\n");

        ToolCall steered = AppendLinePreApprovalGuard.steeredEditFile(
                rewrite,
                state,
                TaskContractResolver.fromUserRequest(request),
                "README.md");

        assertNull(steered);
    }

    @Test
    void newFileWriteIsNotSteeredToEditFile() {
        String request = "Create notes.md with one line: Release gate note";
        LoopState state = loopState(request);
        ToolCall newFile = writeFile("notes.md", "Release gate note\n");

        ToolCall steered = AppendLinePreApprovalGuard.steeredEditFile(
                newFile,
                state,
                TaskContractResolver.fromUserRequest(request),
                "notes.md");

        assertNull(steered);
    }

    @Test
    void validAppendLineWriteMayOmitTerminalNewline() {
        String request = "Read README.md, then append exactly this line to README.md: Release gate note";
        LoopState state = loopState(request);
        addReadback(state, "README.md", "1 | # Demo\n");
        ToolCall validWrite = writeFile("README.md", "# Demo\nRelease gate note");

        String diagnostic = AppendLinePreApprovalGuard.diagnostic(
                validWrite,
                state,
                TaskContractResolver.fromUserRequest(request),
                "README.md");

        assertNull(diagnostic);
    }

    @Test
    void canonicalWriteFileAliasIsAccepted() {
        String request = "Read README.md, then append exactly this line to README.md: Release gate note";
        LoopState state = loopState(request);
        addReadback(state, "README.md", "1 | # Demo\n");
        ToolCall validWrite = new ToolCall("write_file", Map.of(
                "path", "README.md",
                "content", "# Demo\nRelease gate note\n"));

        String diagnostic = AppendLinePreApprovalGuard.diagnostic(
                validWrite,
                state,
                TaskContractResolver.fromUserRequest(request),
                "README.md");

        assertNull(diagnostic);
    }

    @Test
    void appendLineWriteWithoutPriorReadReturnsMissingReadDiagnostic() {
        String request = "Read README.md, then append exactly this line to README.md: Release gate note";
        LoopState state = loopState(request);
        ToolCall write = writeFile("README.md", "# Demo\nRelease gate note\n");

        String diagnostic = AppendLinePreApprovalGuard.diagnostic(
                write,
                state,
                TaskContractResolver.fromUserRequest(request),
                "README.md");

        assertEquals(
                "append-line write_file for README.md requires complete same-turn read evidence before approval.",
                diagnostic);
    }

    @Test
    void nonWriteFileCallsReturnNoDiagnostic() {
        String request = "Read README.md, then append exactly this line to README.md: Release gate note";
        LoopState state = loopState(request);
        addReadback(state, "README.md", "1 | # Demo\n");
        ToolCall editCall = new ToolCall(
                "talos.edit_file",
                Map.of("path", "README.md", "old_string", "# Demo", "new_string", "# Demo\nRelease gate note"));

        String diagnostic = AppendLinePreApprovalGuard.diagnostic(
                editCall,
                state,
                TaskContractResolver.fromUserRequest(request),
                "README.md");

        assertNull(diagnostic);
    }

    @Test
    void executionStageDelegatesAppendLineDiagnosticSelectionToGuard() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallPreExecutionGuardChain.java"));

        assertTrue(source.contains("AppendLinePreApprovalGuard.diagnostic"), source);
        assertFalse(source.contains("private static String appendLinePreApprovalDiagnostic"), source);
        assertFalse(source.contains("private static AppendLineExpectation appendLineExpectationForPath"), source);
        assertFalse(source.contains("private static boolean appendLineContentPreservesReadback"), source);
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
        state.successfulReadCallBodies.put("talos.read_file:path=" + path + ";", readback);
    }

    private static ToolCall writeFile(String path, String content) {
        return new ToolCall("talos.write_file", Map.of("path", path, "content", content));
    }
}
