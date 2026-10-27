package dev.talos.runtime.toolcall;

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

class NamedTargetExistenceGuardTest {
    @TempDir
    Path workspace;

    @Test
    void writeFileIsBlockedWhenRequestedFunctionTargetIsAbsentFromSameTurnReadback() throws Exception {
        Files.writeString(workspace.resolve("helper.py"), """
                def bar():
                    return 1
                """);
        String request = "Modify foo() in helper.py so it returns 99.";
        LoopState state = loopState(request);
        addReadback(state, "helper.py", """
                1 | def bar():
                2 |     return 1
                """);
        ToolCall write = writeFile("helper.py", """
                def bar():
                    return 99
                """);
        List<String> modelMessages = new ArrayList<>();
        List<ToolResult> emitted = new ArrayList<>();
        ToolCallPreExecutionGuardChain chain = new ToolCallPreExecutionGuardChain(
                false,
                (s, nativePath, callIndex, content, outcome) -> modelMessages.add(content),
                (toolName, result) -> emitted.add(result));

        ToolCallPreExecutionGuardChain.Result result = chain.evaluate(
                state,
                write,
                ToolExecutionPathContext.from(write),
                TaskContractResolver.fromUserRequest(request),
                false,
                0,
                Set.of(),
                Set.of());

        assertAll(
                () -> assertTrue(result.blocked(), "absent named target must stop before execution"),
                () -> assertEquals(1, result.failuresThisIteration()),
                () -> assertEquals(1, state.totalToolsInvoked, "the chain counts the call before this guard"),
                () -> assertEquals(List.of("talos.write_file"), state.toolNames),
                () -> assertEquals(1, emitted.size()),
                () -> assertFalse(emitted.getFirst().success()),
                () -> assertTrue(emitted.getFirst().errorMessage().contains("foo()"), emitted.getFirst().errorMessage()),
                () -> assertTrue(emitted.getFirst().errorMessage().contains("helper.py"), emitted.getFirst().errorMessage()),
                () -> assertEquals(1, state.toolOutcomes.size()),
                () -> assertTrue(state.toolOutcomes.getFirst().mutating()),
                () -> assertFalse(state.toolOutcomes.getFirst().success()),
                () -> assertTrue(modelMessages.getFirst().contains("[tool_result: talos.write_file]")),
                () -> assertTrue(modelMessages.getFirst().contains("Named target `foo()` was not found"))
        );
    }

    @Test
    void realScn14ExistingFunctionPromptIsBlockedWhenTargetIsAbsentFromSameTurnReadback() throws Exception {
        Files.writeString(workspace.resolve("helper.py"), """
                def bar():
                    return 1
                """);
        String request = "Modify the existing function foo() in helper.py so it returns 99. Do not add new functions.";
        LoopState state = loopState(request);
        addReadback(state, "helper.py", """
                1 | def bar():
                2 |     return 1
                """);
        ToolCall write = writeFile("helper.py", """
                def bar():
                    return 1

                def foo():
                    return 99
                """);
        List<String> modelMessages = new ArrayList<>();
        List<ToolResult> emitted = new ArrayList<>();
        ToolCallPreExecutionGuardChain chain = new ToolCallPreExecutionGuardChain(
                false,
                (s, nativePath, callIndex, content, outcome) -> modelMessages.add(content),
                (toolName, result) -> emitted.add(result));

        ToolCallPreExecutionGuardChain.Result result = chain.evaluate(
                state,
                write,
                ToolExecutionPathContext.from(write),
                TaskContractResolver.fromUserRequest(request),
                false,
                0,
                Set.of(),
                Set.of());

        assertAll(
                () -> assertTrue(result.blocked(), "real scn-14 prompt must not slip past the guard"),
                () -> assertEquals(1, emitted.size()),
                () -> assertTrue(emitted.getFirst().errorMessage().contains("foo()"), emitted.getFirst().errorMessage()),
                () -> assertTrue(modelMessages.getFirst().contains("Do not mutate another function"))
        );
    }

    @Test
    void addFunctionRequestIsNotBlockedWhenFunctionDoesNotExistYet() throws Exception {
        Files.writeString(workspace.resolve("helper.py"), """
                def bar():
                    return 1
                """);
        String request = "Add a function foo() to helper.py so it returns 99.";
        LoopState state = loopState(request);
        addReadback(state, "helper.py", """
                1 | def bar():
                2 |     return 1
                """);
        ToolCall write = writeFile("helper.py", """
                def bar():
                    return 1

                def foo():
                    return 99
                """);
        ToolCallPreExecutionGuardChain chain = new ToolCallPreExecutionGuardChain(
                false,
                (s, nativePath, callIndex, content, outcome) -> fail("create/add function request should not trigger named-target guard"),
                (toolName, result) -> fail("create/add function request should not emit a failed result"));

        ToolCallPreExecutionGuardChain.Result result = chain.evaluate(
                state,
                write,
                ToolExecutionPathContext.from(write),
                TaskContractResolver.fromUserRequest(request),
                false,
                0,
                Set.of(),
                Set.of());

        assertFalse(result.blocked());
    }

    @Test
    void editFileIsBlockedWhenItRetargetsAnotherFunctionAfterRequestedTargetIsAbsent() throws Exception {
        Files.writeString(workspace.resolve("helper.py"), """
                def bar():
                    return 1
                """);
        String request = "Modify foo() in helper.py so it returns 99.";
        LoopState state = loopState(request);
        addReadback(state, "helper.py", """
                1 | def bar():
                2 |     return 1
                """);
        ToolCall edit = new ToolCall("talos.edit_file", Map.of(
                "path", "helper.py",
                "old_string", "def bar():\n    return 1\n",
                "new_string", "def bar():\n    return 99\n"));
        List<ToolResult> emitted = new ArrayList<>();
        ToolCallPreExecutionGuardChain chain = new ToolCallPreExecutionGuardChain(
                false,
                (s, nativePath, callIndex, content, outcome) -> assertTrue(content.contains("Named target `foo()`")),
                (toolName, result) -> emitted.add(result));

        ToolCallPreExecutionGuardChain.Result result = chain.evaluate(
                state,
                edit,
                ToolExecutionPathContext.from(edit),
                TaskContractResolver.fromUserRequest(request),
                false,
                0,
                Set.of(),
                Set.of());

        assertAll(
                () -> assertTrue(result.blocked()),
                () -> assertEquals(1, result.failuresThisIteration()),
                () -> assertEquals(1, emitted.size()),
                () -> assertTrue(emitted.getFirst().errorMessage().contains("foo()")),
                () -> assertTrue(emitted.getFirst().errorMessage().contains("Do not mutate another function"))
        );
    }

    @Test
    void namedTargetMutationRequiresCompleteSameTurnReadbackBeforeApproval() {
        String request = "Modify foo() in helper.py so it returns 99.";
        LoopState state = loopState(request);
        ToolCall write = writeFile("helper.py", """
                def foo():
                    return 99
                """);
        List<ToolResult> emitted = new ArrayList<>();
        ToolCallPreExecutionGuardChain chain = new ToolCallPreExecutionGuardChain(
                false,
                (s, nativePath, callIndex, content, outcome) -> assertTrue(content.contains("requires complete same-turn read evidence")),
                (toolName, result) -> emitted.add(result));

        ToolCallPreExecutionGuardChain.Result result = chain.evaluate(
                state,
                write,
                ToolExecutionPathContext.from(write),
                TaskContractResolver.fromUserRequest(request),
                false,
                0,
                Set.of(),
                Set.of());

        assertAll(
                () -> assertTrue(result.blocked()),
                () -> assertEquals(1, result.failuresThisIteration()),
                () -> assertEquals(1, emitted.size()),
                () -> assertTrue(emitted.getFirst().errorMessage().contains("requires complete same-turn read evidence"))
        );
    }

    @Test
    void writeFileIsAllowedWhenRequestedFunctionTargetExistsInSameTurnReadback() throws Exception {
        Files.writeString(workspace.resolve("helper.py"), """
                def foo():
                    return 1
                """);
        String request = "Modify foo() in helper.py so it returns 99.";
        LoopState state = loopState(request);
        addReadback(state, "helper.py", """
                1 | def foo():
                2 |     return 1
                """);
        ToolCall write = writeFile("helper.py", """
                def foo():
                    return 99
                """);
        ToolCallPreExecutionGuardChain chain = new ToolCallPreExecutionGuardChain(
                false,
                (s, nativePath, callIndex, content, outcome) -> fail("valid named target should not append a block result"),
                (toolName, result) -> fail("valid named target should not emit a failed result"));

        ToolCallPreExecutionGuardChain.Result result = chain.evaluate(
                state,
                write,
                ToolExecutionPathContext.from(write),
                TaskContractResolver.fromUserRequest(request),
                false,
                0,
                Set.of(),
                Set.of());

        assertAll(
                () -> assertFalse(result.blocked()),
                () -> assertEquals(0, result.failuresThisIteration()),
                () -> assertEquals(1, state.totalToolsInvoked),
                () -> assertEquals(List.of("talos.write_file"), state.toolNames)
        );
    }

    @Test
    void mutationWithoutExplicitNamedFunctionTargetIsNotBlockedByThisGuard() {
        String request = "Fix the bug in helper.py.";
        LoopState state = loopState(request);
        ToolCall write = writeFile("helper.py", """
                def bar():
                    return 99
                """);
        ToolCallPreExecutionGuardChain chain = new ToolCallPreExecutionGuardChain(
                false,
                (s, nativePath, callIndex, content, outcome) -> fail("unnamed target request should not trigger named-target guard"),
                (toolName, result) -> fail("unnamed target request should not emit a failed result"));

        ToolCallPreExecutionGuardChain.Result result = chain.evaluate(
                state,
                write,
                ToolExecutionPathContext.from(write),
                TaskContractResolver.fromUserRequest(request),
                false,
                0,
                Set.of(),
                Set.of());

        assertFalse(result.blocked());
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
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", Map.of("path", path)),
                path,
                ToolResult.ok(readback));
    }

    private static ToolCall writeFile(String path, String content) {
        return new ToolCall("talos.write_file", Map.of("path", path, "content", content));
    }

    private static final String NESTED_NAMED_REQUEST = "Modify foo() in src/helper.py so it returns 99.";

    @Test
    void absoluteSpellingOfTheNamedFileIsBlockedThroughTheChainLikeTheRelativeSpelling() throws Exception {
        Path root = nestedHelper();
        String absolute = root.resolve("src").resolve("helper.py").toString();
        LoopState state = loopStateAt(root, NESTED_NAMED_REQUEST);
        addReadback(state, "src/helper.py", "1 | def bar():\n2 |     return 1\n");
        ToolCall write = writeFile(absolute, "def bar():\n    return 99\n");
        List<String> modelMessages = new ArrayList<>();
        List<ToolResult> emitted = new ArrayList<>();
        ToolCallPreExecutionGuardChain chain = new ToolCallPreExecutionGuardChain(
                false,
                (s, nativePath, callIndex, content, outcome) -> modelMessages.add(content),
                (toolName, result) -> emitted.add(result));

        ToolCallPreExecutionGuardChain.Result result = chain.evaluate(
                state, write, ToolExecutionPathContext.from(write),
                TaskContractResolver.fromUserRequest(NESTED_NAMED_REQUEST),
                false, 0, Set.of(), Set.of());

        assertAll(
                () -> assertTrue(result.blocked(), "the absolute spelling of the named file must stop before execution"),
                () -> assertEquals(1, emitted.size()),
                () -> assertFalse(emitted.getFirst().success()),
                () -> assertTrue(emitted.getFirst().errorMessage()
                        .contains("Named target `foo()` was not found in `src/helper.py`"),
                        emitted.getFirst().errorMessage()),
                () -> assertTrue(modelMessages.getFirst().contains("Do not mutate another function"),
                        modelMessages.toString()));
    }

    @Test
    void absoluteSpellingOfTheNamedFileRequiresReadEvidenceLikeTheRelativeSpelling() throws Exception {
        Path root = nestedHelper();
        String absolute = root.resolve("src").resolve("helper.py").toString();
        LoopState state = loopStateAt(root, NESTED_NAMED_REQUEST);
        var contract = TaskContractResolver.fromUserRequest(NESTED_NAMED_REQUEST);

        String relative = NamedTargetExistenceGuard.diagnostic(
                writeFile("src/helper.py", "def foo():\n    return 99\n"), state, contract, "src/helper.py");
        String absoluteDiagnostic = NamedTargetExistenceGuard.diagnostic(
                writeFile(absolute, "def foo():\n    return 99\n"), state, contract, absolute);

        assertNotNull(relative, "the relative spelling must be held, otherwise the fixture is wrong");
        assertEquals(relative, absoluteDiagnostic);
    }

    @Test
    void sameNamedFileInAnotherFolderIsNotTheNamedTarget() throws Exception {
        Path root = nestedHelper();
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib").resolve("helper.py"), "def bar():\n    return 1\n");
        String sibling = root.resolve("lib").resolve("helper.py").toString();
        LoopState state = loopStateAt(root, NESTED_NAMED_REQUEST);
        addReadback(state, "src/helper.py", "1 | def bar():\n2 |     return 1\n");
        var contract = TaskContractResolver.fromUserRequest(NESTED_NAMED_REQUEST);

        for (String path : List.of("lib/helper.py", sibling)) {
            assertNull(NamedTargetExistenceGuard.diagnostic(
                    writeFile(path, "def bar():\n    return 99\n"), state, contract, path), path);
        }
    }

    private Path nestedHelper() throws Exception {
        Path root = workspace.toRealPath();
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src").resolve("helper.py"), "def bar():\n    return 1\n");
        return root;
    }

    private static LoopState loopStateAt(Path root, String request) {
        return new LoopState("", List.of(), List.of(ChatMessage.user(request)), root, null, null, 5, 0);
    }
}
