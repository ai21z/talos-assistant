package dev.talos.runtime.toolcall;

import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReadDisplayWriteContainmentGuardTest {
    @TempDir
    Path workspace;

    @Test
    void writeFileBlocksSameTurnReadDisplayPrefixesBeforeApproval() {
        String request = "Modify helper.py so bar returns 99.";
        LoopState state = loopState(request);
        addReadback(state, "helper.py", """
                1 | def bar():
                2 |     return 1
                """);
        ToolCall write = writeFile("helper.py", """
                1 | def bar():
                2 |     return 99
                """);
        List<String> modelMessages = new ArrayList<>();
        List<ToolResult> emitted = new ArrayList<>();
        ToolCallPreExecutionGuardChain chain = chain(modelMessages, emitted);

        ToolCallPreExecutionGuardChain.Result result = evaluate(chain, state, write, request);

        assertAll(
                () -> assertTrue(result.blocked(), "read-display prefixes must stop before approval"),
                () -> assertEquals(1, result.failuresThisIteration()),
                () -> assertEquals(1, state.totalToolsInvoked, "the chain counts the call before this guard"),
                () -> assertEquals(List.of("talos.write_file"), state.toolNames),
                () -> assertEquals(1, emitted.size()),
                () -> assertFalse(emitted.getFirst().success()),
                () -> assertTrue(emitted.getFirst().errorMessage().contains("read-display line prefixes"),
                        emitted.getFirst().errorMessage()),
                () -> assertTrue(emitted.getFirst().errorMessage().contains("helper.py"),
                        emitted.getFirst().errorMessage()),
                () -> assertEquals(1, state.toolOutcomes.size()),
                () -> assertTrue(state.toolOutcomes.getFirst().mutating()),
                () -> assertFalse(state.toolOutcomes.getFirst().success()),
                () -> assertTrue(modelMessages.getFirst().contains("[tool_result: talos.write_file]")),
                () -> assertTrue(modelMessages.getFirst().contains("Remove the line-number prefixes"),
                        modelMessages.getFirst())
        );
    }

    @Test
    void editFileBlocksSameTurnReadDisplayReplacementBeforeApproval() {
        String request = "Modify helper.py so bar returns 99.";
        LoopState state = loopState(request);
        addReadback(state, "helper.py", """
                1 | def bar():
                2 |     return 1
                """);
        ToolCall edit = new ToolCall("talos.edit_file", Map.of(
                "path", "helper.py",
                "old_string", "def bar():\n    return 1\n",
                "new_string", "1 | def bar():\n2 |     return 99\n"));
        List<String> modelMessages = new ArrayList<>();
        List<ToolResult> emitted = new ArrayList<>();
        ToolCallPreExecutionGuardChain chain = chain(modelMessages, emitted);

        ToolCallPreExecutionGuardChain.Result result = evaluate(chain, state, edit, request);

        assertAll(
                () -> assertTrue(result.blocked(), "edit_file replacement must not carry read-display prefixes"),
                () -> assertEquals(1, emitted.size()),
                () -> assertTrue(emitted.getFirst().errorMessage().contains("read-display line prefixes"),
                        emitted.getFirst().errorMessage()),
                () -> assertTrue(modelMessages.getFirst().contains("[tool_result: talos.edit_file]"))
        );
    }

    @Test
    void ordinarySourceWriteIsAllowedAfterSameTurnReadDisplay() {
        String request = "Modify helper.py so bar returns 99.";
        LoopState state = loopState(request);
        addReadback(state, "helper.py", """
                1 | def bar():
                2 |     return 1
                """);
        ToolCall write = writeFile("helper.py", """
                def bar():
                    return 99
                """);
        ToolCallPreExecutionGuardChain chain = chain(
                new ArrayList<>(),
                new ArrayList<>());

        ToolCallPreExecutionGuardChain.Result result = evaluate(chain, state, write, request);

        assertAll(
                () -> assertFalse(result.blocked()),
                () -> assertEquals(0, result.failuresThisIteration()),
                () -> assertEquals(1, state.totalToolsInvoked)
        );
    }

    @Test
    void literalNumberedPipeTextWithoutSameTurnReadDisplayIsAllowed() {
        String request = "Write this literal numbered table to notes.txt.";
        LoopState state = loopState(request);
        ToolCall write = writeFile("notes.txt", """
                1 | alpha
                2 | beta
                """);
        ToolCallPreExecutionGuardChain chain = chain(
                new ArrayList<>(),
                new ArrayList<>());

        ToolCallPreExecutionGuardChain.Result result = evaluate(chain, state, write, request);

        assertAll(
                () -> assertFalse(result.blocked()),
                () -> assertEquals(0, result.failuresThisIteration()),
                () -> assertEquals(1, state.totalToolsInvoked)
        );
    }

    private LoopState loopState(String request) {
        return new LoopState(
                "",
                List.of(),
                List.of(ChatMessage.user(request)),
                workspace,
                null,
                null,
                5,
                0);
    }

    private static void addReadback(LoopState state, String path, String readback) {
        state.pathsReadThisTurn.add(ToolCallSupport.normalizePath(path));
        state.successfulReadCallBodies.put("talos.read_file:path=" + path + ";", readback);
        state.readFileBodiesThisTurn.put(ToolCallSupport.normalizePath(path), readback);
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
