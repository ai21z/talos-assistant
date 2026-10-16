package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyEvidenceContextTest {
    @TempDir Path workspace;
    @AfterEach void clear() { TurnTaskContractCapture.clear(); }

    static LoopState state(Path workspace) {
        var request = "Read README.md, index.html, script.js and scripts.js. "
                + "Explain the selector failure and calculate BMI. Do not edit any files.";
        var contract = new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                Set.of("README.md", "index.html", "script.js", "scripts.js"), Set.of(), request);
        TurnTaskContractCapture.set(contract);
        return new LoopState("", List.of(), new ArrayList<>(List.of(
                ChatMessage.system("system"), ChatMessage.user(request))), workspace,
                Context.builder(new Config()).build(), null, 10, 0);
    }

    static void read(LoopState state, String path, String body, Map<String, String> params) throws Exception {
        Files.writeString(state.workspace.resolve(path), body);
        String id = "read-" + state.messages.size();
        ToolCall call = new ToolCall("talos.read_file", params);
        state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall(id, call.toolName(), new java.util.LinkedHashMap<>(params)))));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(state, call, path, ToolResult.ok(body));
        state.messages.add(ChatMessage.toolResult(id, ToolCallSupport.formatToolResult(call, ToolResult.ok(body))));
        int index = state.messages.size() - 1;
        state.readEvidence.bindToolResult(call, index, state.messages.get(index));
    }

    static void populate(LoopState state) throws Exception {
        for (String path : List.of("README.md", "index.html", "script.js", "scripts.js"))
            read(state, path, "1 | observed contents of " + path, Map.of("path", path));
        ToolCallSupport.compactOlderToolResultsInPlace(state.messages, state.compactedToolResultOriginals);
    }

    @Test void restoresFourBodiesInTheirOriginalToolRolesAndRecompactsAfterward() throws Exception {
        var state = state(workspace);
        populate(state);
        var before = List.copyOf(state.messages);
        assertTrue(ReadOnlyEvidenceContext.select(state).complete());
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            for (String path : List.of("README.md", "index.html", "script.js", "scripts.js")) {
                assertEquals(1, state.messages.stream().filter(message -> "tool".equals(message.role())
                        && message.content().contains("observed contents of " + path)).count());
                assertFalse(state.messages.stream().filter(message -> "system".equals(message.role()))
                        .anyMatch(message -> message.content().contains("observed contents of " + path)));
            }
        }
        assertEquals(before, state.messages);
    }

    @Test void identicalBodiesRemainBoundToDistinctResultIds() throws Exception {
        var state = state(workspace);
        for (String path : List.of("README.md", "index.html", "script.js", "scripts.js"))
            read(state, path, "1 | identical", Map.of("path", path));
        ToolCallSupport.compactOlderToolResultsInPlace(state.messages, state.compactedToolResultOriginals);
        var selection = ReadOnlyEvidenceContext.select(state);
        assertTrue(selection.complete());
        assertEquals(4, selection.carriers().stream().map(item -> item.message().toolCallId()).distinct().count());
        for (var item : selection.carriers()) assertEquals(item.call().id(), item.message().toolCallId());
    }

    @Test void missingEvidenceIsNotCompleteButAvailableBodiesRemainRestorable() throws Exception {
        var state = state(workspace);
        read(state, "README.md", "1 | readme", Map.of("path", "README.md"));
        var selection = ReadOnlyEvidenceContext.select(state);
        assertTrue(selection.available());
        assertFalse(selection.complete());
        assertEquals(1, selection.carriers().size());
        assertEquals(Set.of("index.html", "script.js", "scripts.js"), Set.copyOf(selection.remainingTargets()));
    }

    @Test void onlyUnobservedTargetsRemainPendingAfterEachValidCompleteRead() throws Exception {
        var state = state(workspace);
        var targets = List.of("README.md", "index.html", "script.js", "scripts.js");
        for (int completed = 0; completed <= targets.size(); completed++) {
            var selection = ReadOnlyEvidenceContext.select(state);
            assertTrue(selection.available(), "Valid missing evidence is distinct from unavailable evidence");
            assertEquals(Set.copyOf(targets.subList(completed, targets.size())), Set.copyOf(selection.remainingTargets()));
            assertEquals(completed, selection.carriers().size());
            assertEquals(completed == targets.size(), selection.complete());
            if (completed < targets.size()) {
                String target = targets.get(completed);
                read(state, target, "1 | observed " + target, Map.of("path", target));
            }
        }
    }

    @Test void rangedOrTruncatedFirstObservationIsUnavailableRatherThanUnobserved() throws Exception {
        for (var params : List.of(Map.of("path", "README.md", "offset", "2"), Map.of("path", "README.md"))) {
            var state = state(workspace);
            String body = params.containsKey("offset") ? "2 | partial read" : "1 | first line\n... (output truncated)";
            read(state, "README.md", body, params);
            var selection = ReadOnlyEvidenceContext.select(state);
            assertFalse(selection.available());
            assertFalse(selection.complete());
            assertTrue(selection.remainingTargets().isEmpty(), "Do not mislabel unsafe evidence as safe pending work");
        }
    }

    @Test void anUnboundReadResultCannotBecomeAnAvailablePendingSelection() throws Exception {
        var state = state(workspace);
        Files.writeString(workspace.resolve("README.md"), "readme");
        var call = new ToolCall("talos.read_file", Map.of("path", "README.md"));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(state, call, "README.md", ToolResult.ok("1 | readme"));
        assertFalse(ReadOnlyEvidenceContext.select(state).available(), "Ledger evidence alone is not a model-visible bound carrier");
    }

    @Test void alteredOriginalNativeCallCannotRebindAnOtherwiseMatchingResultId() throws Exception {
        for (boolean wrongName : List.of(true, false)) {
            var state = state(workspace);
            read(state, "README.md", "1 | readme", Map.of("path", "README.md"));
            var carrier = ReadOnlyEvidenceContext.select(state).carriers().getFirst();
            state.messages.set(carrier.index() - 1, ChatMessage.assistantWithToolCalls("", List.of(
                    new ChatMessage.NativeToolCall(carrier.call().id(), wrongName ? "talos.write_file" : "talos.read_file",
                            Map.of("path", wrongName ? "README.md" : "index.html")))));
            assertFalse(ReadOnlyEvidenceContext.select(state).available(),
                    "The original tool name and target must agree with the current-turn read evidence");
        }
    }

    @Test void mutationAndInvalidationCannotRestoreOldEvidence() throws Exception {
        var state = state(workspace);
        populate(state);
        state.mutationSinceStart = true;
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertTrue(ReadOnlyEvidenceContext.select(state).carriers().isEmpty());
        state.mutationSinceStart = false;
        ReadEvidenceStateAccounting.clearSuccessfulReadCaches(state);
        assertFalse(ReadOnlyEvidenceContext.select(state).available(), "Invalidation is not a fresh never-read target");
        assertFalse(ReadOnlyEvidenceContext.select(state).complete());
        assertTrue(ReadOnlyEvidenceContext.select(state).carriers().isEmpty());
    }

    @Test void withheldAndRedactedOrTruncatedEvidenceCannotEnableSynthesis() throws Exception {
        var state = state(workspace);
        populate(state);
        state.contentWithheldFromModelContext = true;
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertTrue(ReadOnlyEvidenceContext.select(state).carriers().isEmpty());
        state.contentWithheldFromModelContext = false;
        read(state, "README.md", "... (output truncated)", Map.of("path", "README.md"));
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertFalse(ReadOnlyEvidenceContext.select(state).complete());
        read(state, "README.md", dev.talos.runtime.policy.ProtectedContentPolicy.REDACTED_VALUE,
                Map.of("path", "README.md"));
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertFalse(ReadOnlyEvidenceContext.select(state).complete());
    }

    @Test void anyNewExplicitRangeCannotReuseAnEarlierCompleteReadForThisTransition() throws Exception {
        var state = state(workspace);
        populate(state);
        read(state, "index.html", "1 | observed contents of index.html", Map.of("path", "index.html", "offset", "1"));
        assertFalse(ReadOnlyEvidenceContext.select(state).available(),
                "An explicit offset is a ranged observation, even when the sampled bytes match an older full read");
        read(state, "index.html", "2 | different partial contents", Map.of("path", "index.html", "offset", "2"));
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertFalse(ReadOnlyEvidenceContext.select(state).complete());
    }

    @Test void changedCarrierAndDuplicateNativeIdFailClosed() throws Exception {
        var state = state(workspace);
        populate(state);
        int index = ReadOnlyEvidenceContext.select(state).carriers().getFirst().index();
        ChatMessage old = state.messages.get(index);
        state.messages.set(index, ChatMessage.toolResult(old.toolCallId(), "unbound replacement"));
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertFalse(ReadOnlyEvidenceContext.select(state).complete());
        state.messages.set(index, old);
        var call = ReadOnlyEvidenceContext.select(state).carriers().getFirst().call();
        state.messages.set(0, ChatMessage.assistantWithToolCalls("", List.of(call)));
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertFalse(ReadOnlyEvidenceContext.select(state).complete());
    }

    @Test void protectedTargetsAndEvidenceSizeAreBounded() throws Exception {
        var state = state(workspace);
        populate(state);
        var old = TurnTaskContractCapture.get();
        TurnTaskContractCapture.set(new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                Set.of(".env"), Set.of(), "Read .env but do not edit."));
        read(state, ".env", "not a real secret", Map.of("path", ".env"));
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertTrue(ReadOnlyEvidenceContext.select(state).carriers().isEmpty());
        TurnTaskContractCapture.set(old);
        read(state, "README.md", "x".repeat(ReadOnlyEvidenceContext.MAX_EVIDENCE_CHARS),
                Map.of("path", "README.md"));
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertTrue(ReadOnlyEvidenceContext.select(state).carriers().isEmpty());
    }

    @Test void unrelatedTaskTypesDoNotAcquireThisRecovery() throws Exception {
        var state = state(workspace);
        populate(state);
        var old = TurnTaskContractCapture.get();
        TurnTaskContractCapture.set(new TaskContract(TaskType.UNKNOWN, false, false, false,
                old.expectedTargets(), Set.of(), old.originalUserRequest()));
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertTrue(ReadOnlyEvidenceContext.select(state).carriers().isEmpty());
    }

    @Test void sameFileTargetAliasesCannotDuplicateOneNativeCarrier() throws Exception {
        var state = state(workspace);
        populate(state);
        java.nio.file.Files.createLink(workspace.resolve("README-alias.md"), workspace.resolve("README.md"));
        TurnTaskContractCapture.set(new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                Set.of("README.md", "README-alias.md"), Set.of(),
                "Read README.md and README-alias.md. Diagnose without editing."));
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        assertTrue(ReadOnlyEvidenceContext.select(state).carriers().isEmpty(),
                "Two names resolving to one read must not repeat the same provider call ID");
    }

    @Test void modelRestrictionsOnOriginalOrCurrentCarriersPreventRestoration() throws Exception {
        for (boolean originalRestricted : List.of(false, true)) {
            var state = state(workspace);
            read(state, "README.md", "1 | observed readme", Map.of("path", "README.md"));
            int index = state.messages.size() - 1;
            var original = state.messages.get(index);
            var restricted = original.withContentRestrictions(new dev.talos.safety.ContentRestrictions(false, false, false, false));
            state.messages.set(index, restricted);
            if (originalRestricted) {
                state.readEvidence.bindToolResult(new ToolCall("talos.read_file", Map.of("path", "README.md")), index, restricted);
                state.messages.set(index, original);
            }
            assertFalse(ReadOnlyEvidenceContext.select(state).available(), "Neither carrier version may revoke model denial");
        }
    }

    @Test void nonModelRestrictionsSurviveCarrierRestoration() throws Exception {
        var state = state(workspace);
        read(state, "README.md", "1 | observed readme", Map.of("path", "README.md"));
        int index = state.messages.size() - 1;
        var restrictions = new dev.talos.safety.ContentRestrictions(true, false, false, false);
        state.messages.set(index, state.messages.get(index).withContentRestrictions(restrictions));
        assertEquals(restrictions, ReadOnlyEvidenceContext.select(state).carriers().getFirst().message().contentRestrictions());
    }
}
