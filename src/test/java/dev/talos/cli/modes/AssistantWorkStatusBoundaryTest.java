package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AssistantWorkStatusBoundaryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedHandoffKeepsUsefulEffectsWithoutCertifyingUnknownMeaning(boolean unsupported) throws Exception {
        var memory = new SessionMemory();
        var listener = new dev.talos.runtime.MemoryUpdateListener(new dev.talos.core.context.ConversationManager(
                memory, new dev.talos.core.context.TokenBudget()), null, memory);
        var trace = dev.talos.runtime.trace.LocalTurnTrace.builder("captured-handoff", "fixture", 5,
                        "2026-10-16T00:00:00Z")
                .workspaceHash(dev.talos.runtime.JsonSessionStore.sessionIdFor(workspace))
                .verification("READBACK_ONLY", "", List.of()).build();
        var call = new dev.talos.runtime.TurnRecord.ToolCallSummary("talos.write_file", "harbor-story.txt", List.of(),
                true, "", dev.talos.tools.ToolMutationEffects.applied("harbor-story.txt"),
                dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED, dev.talos.tools.ToolFailureReason.NONE);
        var audit = new dev.talos.runtime.TurnAudit(List.of(call), 1, 1, 0,
                dev.talos.runtime.TurnPolicyTrace.empty(), trace, dev.talos.safety.ContentRestrictions.UNRESTRICTED,
                List.of(), dev.talos.runtime.WorkStatusReceipt.none());
        listener.onTurnComplete(new dev.talos.runtime.TurnResult(new dev.talos.runtime.Result.Ok(
                "The story is perfect and all tests passed."), null, 5, java.time.Duration.ZERO, audit), "Save the story");
        String request = "Give me a short handoff. What is saved and verified, and what hasn't been checked? Don't change any files."
                + (unsupported ? " Why was the lighthouse abandoned?" : "");
        var fixture = dev.talos.core.llm.WorkStatusColdProviderFixture.create();
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context)).withControlledContracts((frame, task) -> {
            var region = new dev.talos.runtime.intent.RequestedResultInventory.SourceRegion(0, request.length());
            var results = new ArrayList<dev.talos.runtime.intent.RequestedResultInventory.RequestedResult>();
            for (var kind : List.of(dev.talos.runtime.intent.RequestedResultInventory.Kind.RETAINED_UNCHECKED,
                    dev.talos.runtime.intent.RequestedResultInventory.Kind.RETAINED_SAVES,
                    dev.talos.runtime.intent.RequestedResultInventory.Kind.RETAINED_VERIFICATION)) {
                results.add(new dev.talos.runtime.intent.RequestedResultInventory.RequestedResult("slot-" + kind.name(),
                        region, kind, "active workspace", "retained conversation",
                        dev.talos.runtime.intent.RequestedResultInventory.EvidenceBasis.RETAINED_RUNTIME_RECORDS));
            }
            if (unsupported) results.add(new dev.talos.runtime.intent.RequestedResultInventory.RequestedResult("unknown-q",
                    region, dev.talos.runtime.intent.RequestedResultInventory.Kind.UNSUPPORTED, "active workspace",
                    "current turn", dev.talos.runtime.intent.RequestedResultInventory.EvidenceBasis.NONE));
            return new dev.talos.runtime.observation.TurnRequestFrame.Meaning(
                    new dev.talos.runtime.intent.RequestedResultInventory(request, results, List.of()), Set.of(),
                    dev.talos.runtime.observation.TurnRequestFrame.Publication.ALL_NAMES, false, false);
        });
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), request,
                Context.builder(cfg).memory(memory).llm(fixture.client()).build());
        String answer = text(settled.result());
        assertTrue(answer.contains("harbor-story.txt"), answer);
        assertTrue(answer.contains("READBACK_ONLY") && answer.contains("not per-file"), answer);
        assertTrue(answer.contains("Current contents") && answer.contains("not established"), answer);
        assertFalse(answer.contains("Runtime activity and verification") || answer.contains("Inspection calls:"), answer);
        assertFalse(answer.contains("story is perfect") || answer.contains("all tests passed"), answer);
        if (unsupported) assertTrue(answer.contains("unresolved"), answer);
        assertTrue(answer.length() < 1100, answer);
        assertTrue(settled.audit().toolCalls().isEmpty());
        assertEquals(0, fixture.generations().get());
        assertEquals(0, fixture.preparations().get());
        assertFalse(settled.audit().workStatusReceipt().presentationComplete());
        assertTrue(settled.audit().workStatusReceipt().matches(request, answer));
        var decisions = settled.audit().workStatusReceipt().resultDecisions();
        assertEquals(unsupported ? 4 : 3, decisions.size());
        assertEquals("RETAINED_UNCHECKED", decisions.getFirst().kind());
        assertEquals(dev.talos.core.util.Hash.sha256Hex("slot-RETAINED_UNCHECKED".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                decisions.getFirst().idHash());
        var row = new dev.talos.runtime.TurnRecord(settled.turnNumber(), java.time.Instant.parse("2026-10-16T00:00:00Z"),
                0, request, answer, List.of(), 0, 0, 0, "", "ok", dev.talos.runtime.TurnPolicyTrace.empty(),
                settled.audit().localTrace().traceId());
        String sources = dev.talos.runtime.policy.ProjectHandoffSourceAccess.render(row, settled.audit().localTrace()).orElseThrow();
        assertTrue(sources.contains("unavailable"), sources);
        assertFalse(sources.contains("story is perfect"), sources);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Give me a short handoff. What is saved and verified, and what has not been checked? Don't change any files.",
            "Give me a short handoff. What was saved and why was the lighthouse abandoned? Don't change files."
    })
    void partialHandoffPresentsRetainedOperationsWithoutCertifyingTheirMeaning(String request) throws Exception {
        var handoff = settleRetainedHandoff(request);
        var settled = handoff.settled();
        var fixture = handoff.fixture();
        String answer = text(settled.result());
        assertTrue(answer.contains("harbor-story.txt"), answer);
        assertTrue(answer.contains("Turn 5") && answer.contains("Turn 7"), answer);
        assertTrue(answer.contains("READBACK_ONLY") && answer.contains("PASSED"), answer);
        assertTrue(answer.contains("turn-level") && answer.contains("not per-file"), answer);
        assertTrue(answer.contains("Request coverage remains incomplete"), answer);
        assertTrue(answer.contains("No fresh"), answer);
        assertFalse(answer.contains("Original project question"), answer);
        assertFalse(answer.contains("story is perfect") || answer.contains("full test suite passed"), answer);
        assertTrue(answer.length() < 2000, answer);
        // A partially parsed handoff with an explicit frame is answered concisely, not as a runtime ledger.
        assertTrue(answer.contains("Saved effects"), answer);
        assertFalse(answer.contains("Inspection calls:") || answer.contains("Target limits:"), answer);
        assertTrue(answer.length() < 1200, answer);
        assertEquals(dev.talos.runtime.intent.WorkStatusRequest.Coverage.PARTIAL,
                settled.audit().workStatusReceipt().coverage());
        assertFalse(settled.audit().workStatusReceipt().presentationComplete());
        assertTrue(settled.audit().workStatusReceipt().matches(request, answer));
        assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
        assertTrue(settled.audit().toolCalls().isEmpty());
        assertEquals(0, fixture.generations().get());
        assertEquals(0, fixture.preparations().get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Give me a short handoff. What is saved? Don't mention file names.",
            "Give me a short handoff. What is saved and verified? Keep the file names private."
    })
    void restrictedPartialHandoffWithholdsTargetNames(String request) throws Exception {
        var handoff = settleRetainedHandoff(request);
        String answer = text(handoff.settled().result());
        assertFalse(answer.contains("harbor-story"), answer);
        assertTrue(answer.contains("Saved effects"), answer);
        assertTrue(answer.contains("Request coverage remains incomplete"), answer);
        assertEquals(dev.talos.runtime.intent.WorkStatusRequest.Coverage.PARTIAL,
                handoff.settled().audit().workStatusReceipt().coverage());
        assertEquals("ADVISORY_ONLY", handoff.settled().audit().localTrace().outcome().classification());
        assertTrue(handoff.settled().audit().toolCalls().isEmpty());
        assertEquals(0, handoff.fixture().generations().get());
    }

    @Test void partialHandoffNamesRejectedOperationsAndListsOnlyRecordedVerification() throws Exception {
        var memory = new SessionMemory();
        var listener = new dev.talos.runtime.MemoryUpdateListener(new dev.talos.core.context.ConversationManager(
                memory, new dev.talos.core.context.TokenBudget()), null, memory);
        // Shaped like the b8f585ea Qwen run: a rejected edit without a typed reason, a write and unverified turns.
        for (int turn = 1; turn <= 4; turn++) {
            var calls = new ArrayList<dev.talos.runtime.TurnRecord.ToolCallSummary>();
            if (turn == 2) calls.add(new dev.talos.runtime.TurnRecord.ToolCallSummary("talos.edit_file",
                    "harbor-story.txt", List.of(), false, "Invalid talos.edit_file call: target file not found",
                    dev.talos.tools.ToolMutationEffects.none(), dev.talos.runtime.TurnRecord.ToolCallDisposition.BLOCKED,
                    dev.talos.tools.ToolFailureReason.NONE));
            if (turn == 3) calls.add(new dev.talos.runtime.TurnRecord.ToolCallSummary("talos.write_file",
                    "harbor-story.txt", List.of(), true, "", dev.talos.tools.ToolMutationEffects.applied("harbor-story.txt"),
                    dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED, dev.talos.tools.ToolFailureReason.NONE));
            var trace = dev.talos.runtime.trace.LocalTurnTrace.builder("rejected-" + turn, "fixture", turn,
                            "2026-10-16T00:00:00Z")
                    .workspaceHash(dev.talos.runtime.JsonSessionStore.sessionIdFor(workspace))
                    .verification(turn == 3 ? "READBACK_ONLY" : "NOT_RUN", "", List.of()).build();
            var audit = new dev.talos.runtime.TurnAudit(calls, 0, 0, 0,
                    dev.talos.runtime.TurnPolicyTrace.empty(), trace, dev.talos.safety.ContentRestrictions.UNRESTRICTED,
                    List.of(), dev.talos.runtime.WorkStatusReceipt.none());
            listener.onTurnComplete(new dev.talos.runtime.TurnResult(new dev.talos.runtime.Result.Ok("Earlier answer"),
                    null, turn, java.time.Duration.ZERO, audit), "Earlier request");
        }
        var fixture = dev.talos.core.llm.WorkStatusColdProviderFixture.create();
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg),
                "Give me a short handoff. What is saved and verified, and what hasn't been checked? Don't change any files.",
                Context.builder(cfg).memory(memory).llm(fixture.client()).build());
        String answer = text(settled.result());
        assertTrue(answer.contains("Turn 2: talos.edit_file was rejected before it ran."), answer);
        assertFalse(answer.contains("NONE"), answer);
        assertFalse(answer.contains("Invalid talos.edit_file call"), answer);
        assertTrue(answer.contains("Turn 3 READBACK_ONLY"), answer);
        assertFalse(answer.contains("NOT_RUN"), answer);
        assertTrue(answer.contains("Other retained turns recorded no verification."), answer);
        assertTrue(answer.contains("turn-level, not per-file proof"), answer);
        assertEquals(0, fixture.generations().get());
    }

    private record SettledHandoff(dev.talos.runtime.TurnResult settled,
                                  dev.talos.core.llm.WorkStatusColdProviderFixture fixture) {}

    /** Two retained writes, with readback-only and passed turn verification, precede the request. */
    private SettledHandoff settleRetainedHandoff(String request) throws Exception {
        var memory = new SessionMemory();
        var listener = new dev.talos.runtime.MemoryUpdateListener(new dev.talos.core.context.ConversationManager(
                memory, new dev.talos.core.context.TokenBudget()), null, memory);
        // Synthetic retained events are passed through the production memory and finalization boundary.
        for (int turn : List.of(5, 7)) {
            var trace = dev.talos.runtime.trace.LocalTurnTrace.builder("partial-handoff-" + turn, "fixture", turn,
                            "2026-10-10T00:00:00Z")
                    .workspaceHash(dev.talos.runtime.JsonSessionStore.sessionIdFor(workspace))
                    .verification(turn == 5 ? "READBACK_ONLY" : "PASSED", "", List.of()).build();
            var call = new dev.talos.runtime.TurnRecord.ToolCallSummary("talos.write_file", "harbor-story.txt", List.of(),
                    true, "", dev.talos.tools.ToolMutationEffects.applied("harbor-story.txt"),
                    dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED, dev.talos.tools.ToolFailureReason.NONE);
            var audit = new dev.talos.runtime.TurnAudit(List.of(call), 0, 0, 0,
                    dev.talos.runtime.TurnPolicyTrace.empty(), trace, dev.talos.safety.ContentRestrictions.UNRESTRICTED,
                    List.of(), dev.talos.runtime.WorkStatusReceipt.none());
            listener.onTurnComplete(new dev.talos.runtime.TurnResult(new dev.talos.runtime.Result.Ok(
                    "The story is perfect and the full test suite passed."), null, turn, java.time.Duration.ZERO, audit),
                    "Save the story");
        }
        var fixture = dev.talos.core.llm.WorkStatusColdProviderFixture.create();
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), request,
                Context.builder(cfg).memory(memory).llm(fixture.client()).build());
        return new SettledHandoff(settled, fixture);
    }

    @Test
    void retainedRuntimeHandoffKeepsDenialAndEffectsButMissingDetailsRemainAdvisory() throws Exception {
        String request = "Give me a short handoff. What was denied earlier, what actually changed afterwards, "
                + "and what remains unverified? Do not change files or run tests.";
        var memory = new SessionMemory();
        var listener = new dev.talos.runtime.MemoryUpdateListener(
                new dev.talos.core.context.ConversationManager(memory, new dev.talos.core.context.TokenBudget()),
                null, memory);
        // Runtime metadata is constructed here. Real approval and file effects are exercised by the E2E suite.
        var denial = new dev.talos.runtime.TurnRecord.ToolCallSummary("talos.edit_file", "index.html", List.of(),
                false, "Constructed diagnostic must not determine the cause",
                dev.talos.tools.ToolMutationEffects.none(), dev.talos.runtime.TurnRecord.ToolCallDisposition.BLOCKED,
                dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED);
        var secondDenial = new dev.talos.runtime.TurnRecord.ToolCallSummary("talos.edit_file", "style.css", List.of(),
                false, "Constructed diagnostic must not determine the cause",
                dev.talos.tools.ToolMutationEffects.none(), dev.talos.runtime.TurnRecord.ToolCallDisposition.BLOCKED,
                dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED);
        var applied = new dev.talos.runtime.TurnRecord.ToolCallSummary("talos.edit_file", "index.html", List.of(),
                true, "", dev.talos.tools.ToolMutationEffects.applied("index.html"),
                dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED, dev.talos.tools.ToolFailureReason.NONE);
        var secondApplied = new dev.talos.runtime.TurnRecord.ToolCallSummary("talos.edit_file", "style.css", List.of(),
                true, "", dev.talos.tools.ToolMutationEffects.applied("style.css"),
                dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED, dev.talos.tools.ToolFailureReason.NONE);
        for (int turn = 1; turn <= 2; turn++) {
            var trace = dev.talos.runtime.trace.LocalTurnTrace.builder("retained-targets-" + turn, "fixture", turn,
                            "2026-10-06T00:00:00Z")
                    .workspaceHash(dev.talos.runtime.JsonSessionStore.sessionIdFor(workspace))
                    .verification(turn == 1 ? "NOT_RUN" : "READBACK_ONLY", "", List.of()).build();
            var audit = new dev.talos.runtime.TurnAudit(turn == 1 ? List.of(denial, secondDenial) : List.of(applied, secondApplied),
                    0, 0, 0, dev.talos.runtime.TurnPolicyTrace.empty(), trace,
                    dev.talos.safety.ContentRestrictions.UNRESTRICTED, List.of(),
                    dev.talos.runtime.WorkStatusReceipt.none());
            listener.onTurnComplete(new dev.talos.runtime.TurnResult(
                    new dev.talos.runtime.Result.Ok("Constructed prior answer"), null, turn,
                    java.time.Duration.ZERO, audit), "Constructed prior request");
        }
        var cfg = new Config(null);
        var fixture = dev.talos.core.llm.WorkStatusColdProviderFixture.create();
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), request,
                Context.builder(cfg).memory(memory).llm(fixture.client()).build());
        String answer = text(settled.result());
        assertTrue(answer.contains("Turn 1: 2 user approval denial(s)"), answer);
        assertTrue(answer.contains("Turn 2 file effect entries: 2 applied, 0 uncertain"), answer);
        for (String target : List.of("index.html", "style.css")) {
            String literal = "`" + target + "`";
            assertTrue(answer.contains("Turn 1 talos.edit_file [BLOCKED, unsuccessful, USER_APPROVAL_DENIED]: declared " + literal), answer);
            assertTrue(answer.contains("Turn 2 talos.edit_file [EXECUTED, reported success, NONE]: declared " + literal
                    + " applied [" + literal + "]"), answer);
        }
        assertFalse(answer.contains("Turn 1: 1 permission policy denial(s)"), answer);
        assertTrue(answer.contains("Applied does not mean independently verified"), answer);
        assertTrue(settled.audit().workStatusReceipt().active());
        assertTrue(settled.audit().toolCalls().isEmpty());
        assertEquals("NOT_RUN", settled.audit().localTrace().verification().status());
        assertEquals(0, fixture.generations().get());
        assertEquals(0, fixture.preparations().get());
        assertFalse(answer.contains("Original project question"), answer);
        assertTrue(answer.contains("Operation details remain unresolved"), answer);
        assertFalse(settled.audit().workStatusReceipt().presentationComplete());
        assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
        assertTrue(answer.length() < 3000, answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Give me a short handoff. What actually changed? What remains unverified?",
            "Give me a short handoff. What is saved and verified, and what has not been checked? Don't change any files."
    })
    void runtimeHandoffWithRestrictedOrDifferentWorkspaceTargetsStaysColdAndAdvisory(String request) throws Exception {
        for (int variant = 0; variant < 5; variant++) {
            var memory = new SessionMemory();
            var restrictions = switch (variant) {
                case 0 -> new dev.talos.safety.ContentRestrictions(false, true, true, true);
                case 1 -> new dev.talos.safety.ContentRestrictions(true, false, true, true);
                case 2 -> new dev.talos.safety.ContentRestrictions(true, true, false, true);
                default -> dev.talos.safety.ContentRestrictions.UNRESTRICTED;
            };
            String hash = variant == 3 ? dev.talos.runtime.JsonSessionStore.sessionIdFor(workspace.resolve("other"))
                    : variant == 4 ? "" : dev.talos.runtime.JsonSessionStore.sessionIdFor(workspace);
            var trace = dev.talos.runtime.trace.LocalTurnTrace.builder("restricted-targets", "fixture", 1,
                    "2026-10-06T00:00:00Z").workspaceHash(hash).build();
            var call = new dev.talos.runtime.TurnRecord.ToolCallSummary("talos.edit_file", "runtime-path-canary.md", List.of(),
                    true, "private diagnostic", dev.talos.tools.ToolMutationEffects.applied("runtime-path-canary.md"),
                    dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED);
            var audit = new dev.talos.runtime.TurnAudit(List.of(call), 0, 0, 0,
                    dev.talos.runtime.TurnPolicyTrace.empty(), trace, restrictions, List.of(), dev.talos.runtime.WorkStatusReceipt.none());
            var listener = new dev.talos.runtime.MemoryUpdateListener(new dev.talos.core.context.ConversationManager(
                    memory, new dev.talos.core.context.TokenBudget()), null, memory);
            listener.onTurnComplete(new dev.talos.runtime.TurnResult(new dev.talos.runtime.Result.Ok("Earlier answer"),
                    null, 1, java.time.Duration.ZERO, audit), "Earlier request");
            var fixture = dev.talos.core.llm.WorkStatusColdProviderFixture.create();
            var cfg = new Config(null);
            var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                    new UnifiedAssistantMode().handle(raw, ws, (Context) context));
            var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg),
                    request,
                    Context.builder(cfg).memory(memory).llm(fixture.client()).build());
            String answer = text(settled.result());
            assertFalse(answer.contains("runtime-path-canary"), answer);
            assertFalse(answer.contains("private diagnostic"), answer);
            if (request.contains("has not been checked")) {
                // The partially parsed handoff frame uses the concise renderer. Effects stay countable,
                // restricted names stay withheld, and foreign or unbound records are reported as incomplete.
                assertTrue(variant < 3 ? answer.contains("applied effect entries for withheld targets")
                        : answer.contains("Retained or presented records are incomplete"), answer);
                assertFalse(answer.contains("Target limits:"), answer);
            } else {
                assertTrue(answer.contains("1 applied, 0 uncertain"), answer);
                assertTrue(answer.contains("Target limits:"), answer);
            }
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
            assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
            assertTrue(settled.audit().toolCalls().isEmpty());
            assertEquals(0, fixture.generations().get());
            assertEquals(0, fixture.preparations().get());
        }
    }
    static final String HANDOFF = "Let's stop here. Give me a short handoff. What is known, what remains "
            + "unverified, and what should happen next? Can this be called tested and ready to ship? "
            + "Keep proposed work separate from anything actually done in this conversation. "
            + "Don't change or run anything.";
    @TempDir Path workspace;

    @Test void requestedProjectResultsRetainFourSourcesAndANewClarificationWithoutCertification() throws Exception {
        var fixture = selectionFixture("""
                {"request-1":{"summary":[{"text":"The reported changes and withdrawn suggestion remain historical.","sources":["reference-2","reference-4","reference-7","reference-9"]}],"proposal":[],"gap":""},
                 "request-2":{"summary":[{"text":"The operator reported changes and a withdrawal.","sources":["reference-2","reference-4","reference-7"]}],"proposal":[],"gap":""},
                 "request-3":{"summary":[{"text":"Packaging remains pending.","sources":["reference-9"]}],"proposal":[],"gap":"Current acceptance is not recorded."},
                 "request-4":{"summary":[{"text":"Packaging remains pending.","sources":["reference-9"]}],"proposal":[{"text":"Clarify packaging ownership and acceptance before a new procedure is proposed.","sources":["reference-9"]}],"gap":""},
                 "request-7":{"summary":[{"text":"The original cedar suggestion was withdrawn.","sources":["reference-7"]}],"proposal":[],"gap":""}}
                """);
        var memory = new SessionMemory();
        memory.update("What should be changed?", "Cedar was proposed, not applied.");
        memory.update("The setting was changed since that answer.", "The code returns oak, the old expectation was cedar.");
        memory.update("The documented expectation was updated too.", "The files agree on oak.");
        memory.update("Is the original suggestion still needed?", "The original cedar suggestion is no longer needed.");
        memory.update("Who reviews this and what is pending?", "Review owner: Mara. Pending item: Packaging.");
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), HANDOFF,
                Context.builder(cfg).memory(memory).llm(fixture.client()).build());
        String answer = text(settled.result());
        assertTrue(answer.contains(dev.talos.runtime.policy.ProjectHandoffPolicy.literal("The setting was changed since that answer.")), answer);
        assertTrue(answer.contains(dev.talos.runtime.policy.ProjectHandoffPolicy.literal("The documented expectation was updated too.")), answer);
        assertTrue(answer.contains(dev.talos.runtime.policy.ProjectHandoffPolicy.literal("The original cedar suggestion is no longer needed.")), answer);
        assertTrue(answer.contains(dev.talos.runtime.policy.ProjectHandoffPolicy.literal("Review owner: Mara. Pending item: Packaging.")), answer);
        assertEquals(1, answer.split("reference-9. Earlier assistant statement", -1).length - 1, "Repeated mappings do not repeat previews");
        assertTrue(answer.contains("New advisory proposal"), answer);
        assertTrue(answer.contains("Clarify packaging ownership and acceptance before a new procedure is proposed"), answer);
        assertFalse(answer.contains("Mara must package"), answer);
        assertTrue(answer.contains("Testing: not established"), answer);
        assertTrue(answer.contains("Release acceptance: not established"), answer);
        assertEquals(1, fixture.requests().size());
        assertTrue(settled.audit().toolCalls().isEmpty());
        assertFalse(settled.audit().workStatusReceipt().presentationComplete());
        assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
    }

    @AfterEach void clear() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnTraceCapture.clear();
        dev.talos.cli.prompt.LastPromptCapture.clear();
        dev.talos.spi.types.PromptDebugCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test void projectHandoffIncludesAttributedFactsWithoutCertifyingCompletion() throws Exception {
        var fixture = ScriptedNativeLlmClient.recordingWithJsonSchemaCapability(
                List.of(new LlmClient.StreamResult(compositionSelection("reference-5", "reference-7"), List.of())),
                16_384, true);
        var memory = new SessionMemory();
        memory.update("What should be changed?", "A change from pine to cedar was proposed.");
        memory.update("What happened next?", "The files were changed by the operator, not Talos.");
        memory.update("Do the retained facts agree?", "README, settings and label.py now agree on oak.");
        memory.update("What about packaging?", "Review owner: Mara. Packaging remains pending.");
        var cfg = new Config(null);
        var ctx = Context.builder(cfg).memory(memory).llm(fixture.client()).build();
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), HANDOFF, ctx);
        String answer = text(settled.result());
        assertEquals(1, fixture.requests().size());
        var dispatch = fixture.requests().getFirst();
        assertTrue(dispatch.tools.isEmpty());
        assertEquals(dev.talos.spi.types.ToolChoiceMode.NONE, dispatch.controls.toolChoice());
        assertEquals(dev.talos.spi.types.ProviderResponseCapture.ToolAuthorityMode.NONE, dispatch.controls.toolAuthorityMode());
        assertTrue(dispatch.controls.singleAttempt());
        assertEquals(512, dispatch.controls.maxOutputTokens());
        assertEquals(dev.talos.spi.types.ResponseFormatMode.JSON_SCHEMA, dispatch.controls.responseFormat());
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(dispatch.controls.jsonSchema());
        assertEquals(5, schema.path("required").size());
        assertEquals(16, schema.at("/properties/request-4/properties/summary/items/properties/sources/maxItems").asInt());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(8, schema.at("/properties/request-4/properties/summary/items/properties/sources/items/enum").size());
        assertEquals(HANDOFF, new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(dispatch.messages.getLast().content()).path("current_request").asText());
        assertTrue(answer.contains(dev.talos.runtime.policy.ProjectHandoffPolicy.literal("README, settings and label.py now agree on oak.")), answer);
        assertTrue(answer.contains(dev.talos.runtime.policy.ProjectHandoffPolicy.literal("Review owner: Mara. Packaging remains pending.")), answer);
        assertTrue(answer.contains("Testing: not established"), answer);
        assertTrue(answer.contains("Release acceptance: not established"), answer);
        assertTrue(settled.audit().toolCalls().isEmpty());
        assertFalse(settled.audit().workStatusReceipt().presentationComplete());
        assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
    }

    @Test void currentInputMetadataBlocksGenerationWithoutAnActiveTurnScope() {
        var fixture = selectionFixture(compositionSelection("reference-1"));
        var memory = new SessionMemory();
        memory.update("Known?", "Packaging remains pending.");
        var ctx = Context.builder(new Config(null)).memory(memory).llm(fixture.client()).build();
        var output = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.user(HANDOFF)
                .withContentRestrictions(new dev.talos.safety.ContentRestrictions(false, true, true, true)))),
                workspace, ctx, new AssistantTurnExecutor.Options());
        assertTrue(fixture.requests().isEmpty());
        assertTrue(output.text().contains("Project handoff: useful admitted"));
        assertNull(TurnTaskContractCapture.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"selected", "empty-history", "unsupported-schema", "model-disallowed", "missing-client"})
    void currentInputDestinationRestrictionsSurviveActualSettlement(String path) throws Exception {
        var fixture = ScriptedNativeLlmClient.recordingWithJsonSchemaCapability(
                List.of(new LlmClient.StreamResult(compositionSelection("reference-1"), List.of())),
                16_384, !"unsupported-schema".equals(path));
        var memory = new SessionMemory();
        if (!"empty-history".equals(path)) memory.update("Pending?", "Packaging remains pending.");
        var restrictions = new dev.talos.safety.ContentRestrictions(!"model-disallowed".equals(path), false, false, false);
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) -> {
            var output = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.user(raw)
                    .withContentRestrictions(restrictions))), ws, (Context) context,
                    new AssistantTurnExecutor.Options());
            return java.util.Optional.of(new dev.talos.runtime.Result.Ok(output.text()));
        });
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), HANDOFF,
                Context.builder(cfg).memory(memory).llm("missing-client".equals(path) ? null : fixture.client()).build());
        assertEquals("selected".equals(path) ? 1 : 0, fixture.requests().size());
        assertEquals(restrictions, settled.audit().contentRestrictions());
        if ("selected".equals(path))
            assertEquals(restrictions, fixture.requests().getFirst().messages.getLast().contentRestrictions());
        else {
            String answer = text(settled.result());
            assertEquals(5, answer.split("No historical material was selected for this result", -1).length - 1, path);
            assertTrue(answer.contains("What is known"), answer);
            assertTrue(answer.contains("and what should happen next"), answer);
            assertFalse(answer.contains("Packaging remains pending."), answer);
            assertFalse(answer.contains("New clarification proposal"), answer);
        }
        assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
        assertFalse(settled.audit().workStatusReceipt().presentationComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialHandoffPreservesCurrentInputRestrictionsThroughMemorySettlement(boolean modelAllowed) throws Exception {
        String request = "Give me a short handoff. What is saved and verified?";
        var fixture = dev.talos.core.llm.WorkStatusColdProviderFixture.create();
        var memory = new SessionMemory();
        var restrictions = new dev.talos.safety.ContentRestrictions(modelAllowed, false, false, false);
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) -> {
            var output = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.user(raw)
                    .withContentRestrictions(restrictions))), ws, (Context) context,
                    new AssistantTurnExecutor.Options());
            return java.util.Optional.of(new dev.talos.runtime.Result.Ok(output.text()));
        });
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), request,
                Context.builder(cfg).memory(memory).llm(fixture.client()).build());
        assertEquals(restrictions, settled.audit().contentRestrictions());
        assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
        assertFalse(settled.audit().workStatusReceipt().presentationComplete());
        assertTrue(settled.audit().toolCalls().isEmpty());
        assertEquals(0, fixture.generations().get());
        assertEquals(0, fixture.preparations().get());
        var listener = new dev.talos.runtime.MemoryUpdateListener(new dev.talos.core.context.ConversationManager(
                memory, new dev.talos.core.context.TokenBudget()), null, memory);
        listener.onTurnComplete(settled, request);
        assertEquals(dev.talos.safety.ContentRestrictions.WITHHELD, memory.getTurns().getLast().content());
    }

    @ParameterizedTest
    @ValueSource(strings = {"assist", "ask", "plan", "rag"})
    void projectReferencesAreComposedWithoutWorkspaceRetrievalInEveryMode(String mode) throws Exception {
        var fixture = selectionFixture(compositionSelection("reference-1"));
        var memory = new SessionMemory();
        memory.update("What is pending?", "Packaging remains pending with Mara.");
        var cfg = new Config(null);
        cfg.data.put("privacy", java.util.Map.of("mode", "developer"));
        cfg.data.put("embed", java.util.Map.of("provider", "disabled", "model", "disabled"));
        cfg.data.put("rag", java.util.Map.of("vectors", java.util.Map.of("enabled", false)));
        var rag = new dev.talos.core.rag.RagService(cfg) {
            @Override public Prepared prepare(Path path, String query, Integer topK) {
                throw new AssertionError("Project handoff must not retrieve or index");
            }
        };
        var ctx = Context.builder(cfg).memory(memory).llm(fixture.client()).rag(rag).build();
        Mode handler = switch (mode) {
            case "ask" -> new AskMode();
            case "plan" -> new PlanMode();
            case "rag" -> new RagMode();
            default -> new UnifiedAssistantMode();
        };
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                handler.handle(raw, ws, (Context) context));
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), HANDOFF, ctx);
        assertEquals(1, fixture.requests().size());
        assertTrue(text(settled.result()).contains(dev.talos.runtime.policy.ProjectHandoffPolicy.literal("Packaging remains pending with Mara.")), text(settled.result()));
        assertTrue(settled.audit().toolCalls().isEmpty());
        assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
        assertNull(TurnTaskContractCapture.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Give me a work status.", "Summarize the work status.",
            "Is the work tested and ready to ship?", "Is the work verified?",
            "Give me a handoff. Is this tested and ready to ship?"})
    void pureRuntimeStatusRemainsCompleteAndProviderFree(String request) throws Exception {
        var fixture = dev.talos.core.llm.WorkStatusColdProviderFixture.create();
        var memory = new SessionMemory();
        memory.update("Earlier work?", "All tests passed and everything was changed.");
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        try (var llm = fixture.client()) {
            var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), request,
                    Context.builder(cfg).memory(memory).llm(llm).build());
            assertEquals(0, fixture.generations().get());
            assertEquals(0, fixture.preparations().get());
            assertTrue(settled.audit().workStatusReceipt().presentationComplete());
            assertEquals("READ_ONLY_ANSWERED", settled.audit().localTrace().outcome().classification());
            if (request.contains("tested")) assertTrue(text(settled.result()).contains("Testing: not established"));
            if (request.contains("ship")) assertTrue(text(settled.result()).contains("Release acceptance: not established"));
            assertFalse(text(settled.result()).contains("All tests passed"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"What was done in this conversation?",
            "Give me a handoff. What was rejected?", "Give me a handoff. What was denied?",
            "Give me a handoff. What actually changed?"})
    void requestedOperationDetailsStayIncompleteWithoutInventedObjectsOrProviderWork(String request) throws Exception {
        var fixture = dev.talos.core.llm.WorkStatusColdProviderFixture.create();
        var memory = new SessionMemory();
        memory.update("Earlier work?", "All files were changed and all tests passed.");
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        try (var llm = fixture.client()) {
            var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), request,
                    Context.builder(cfg).memory(memory).llm(llm).build());
            String answer = text(settled.result());
            assertEquals(0, fixture.generations().get());
            assertEquals(0, fixture.preparations().get());
            assertTrue(settled.audit().toolCalls().isEmpty());
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
            assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
            assertTrue(answer.contains("Operation details remain unresolved"), answer);
            assertTrue(answer.contains("No typed denial or rejection was recorded"), answer);
            assertTrue(answer.contains("Runtime status records are unavailable"), answer);
            assertFalse(answer.contains("All files were changed"), answer);
        }
    }

    @Test void nativeToolAttemptIsWithheldWithoutExecutingOrRetrying() throws Exception {
        var fixture = selectionFixture(List.of(
                new LlmClient.StreamResult("Shipment is approved.", List.of(new ChatMessage.NativeToolCall(
                        "native-call", "run_command", java.util.Map.of("command", "test"))))));
        var memory = new SessionMemory();
        memory.update("Known?", "Packaging is pending.");
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), HANDOFF,
                Context.builder(cfg).memory(memory).llm(fixture.client()).build());
        assertEquals(1, fixture.requests().size());
        assertFalse(text(settled.result()).contains("Shipment is approved."));
        assertTrue(text(settled.result()).contains("Project handoff: useful admitted"));
        assertTrue(settled.audit().toolCalls().isEmpty());
        assertFalse(settled.audit().workStatusReceipt().presentationComplete());
    }

    @Test void typedRuntimeAbortAndUnrecognizedStopsCannotBeAccepted() {
        var references = dev.talos.runtime.policy.ProjectHandoffPolicy.capture(List.of(
                ChatMessage.user("Known?"), ChatMessage.assistant("Packaging remains pending.")),
                dev.talos.runtime.intent.WorkStatusRequest.parse(HANDOFF), dev.talos.safety.ContentRestrictions.UNRESTRICTED);
        String valid = legacyCompositionSelection("reference-1");
        assertNotEquals(dev.talos.runtime.policy.ProjectHandoffPolicy.unavailable(
                dev.talos.runtime.intent.WorkStatusRequest.parse(HANDOFF)),
                AssistantTurnExecutor.selectedProjectReferences(references, new LlmClient.StreamResult(valid, List.of(), "stop")));
        for (var response : List.of(
                new LlmClient.StreamResult(valid, List.of(), "stop", "deadline"),
                new LlmClient.StreamResult(valid, List.of(), "content_filter"))) {
            assertEquals(dev.talos.runtime.policy.ProjectHandoffPolicy.unavailable(
                            dev.talos.runtime.intent.WorkStatusRequest.parse(HANDOFF)),
                    AssistantTurnExecutor.selectedProjectReferences(references, response));
        }
    }

    @Test void privateReferencesDoNotReachGenerationOrPresentation() throws Exception {
        var fixture = selectionFixture(compositionSelection("reference-1"));
        var memory = new SessionMemory();
        dev.talos.safety.ContentRestrictionScope.callWithRestrictions(
                new dev.talos.safety.ContentRestrictions(false, false, true, false), () -> {
                    memory.update("PRIVATE_HANDOFF_QUESTION", "PRIVATE_HANDOFF_FACT");
                    return null;
                });
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), HANDOFF,
                Context.builder(cfg).memory(memory).llm(fixture.client()).build());
        assertTrue(fixture.requests().isEmpty());
        assertFalse(text(settled.result()).contains("PRIVATE_HANDOFF_"));
        assertFalse(settled.audit().localTrace().toString().contains("PRIVATE_HANDOFF_"));
        assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
    }

    @ParameterizedTest
    @ValueSource(strings = { HANDOFF,
            "Give me a short handoff. What is saved and verified, and what has not been checked? Don't change any files." })
    void mixedIdentityAndHandoffCannotBecomeCompleteAfterCompositionLoss(String handoff) throws Exception {
        String request = "Who are you? " + handoff;
        var fixture = selectionFixture(compositionSelection("reference-1"));
        var memory = new SessionMemory();
        memory.update("Pending?", "Packaging remains pending.");
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) -> {
            var out = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.user(raw))),
                    ws, (Context) context, new AssistantTurnExecutor.Options().responseMaxChars(100));
            return java.util.Optional.of(new dev.talos.runtime.Result.Ok(out.text()));
        });
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), request,
                Context.builder(cfg).memory(memory).llm(fixture.client()).build());
        assertTrue(settled.audit().workStatusReceipt().active());
        assertFalse(settled.audit().workStatusReceipt().presentationComplete());
        assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
        assertTrue(settled.audit().toolCalls().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "blank", "prose", "reasoning-limit", "fence"})
    void failedSelectionNeverReachesACompleteHandoff(String failure) throws Exception {
        var response = switch (failure) {
            case "length" -> new LlmClient.StreamResult(compositionSelection("reference-1"), List.of(), "length");
            case "aborted" -> new LlmClient.StreamResult(compositionSelection("reference-1") + "\n[turn aborted: deadline]", List.of());
            case "blank" -> new LlmClient.StreamResult("", List.of());
            case "reasoning-limit" -> new LlmClient.StreamResult("", List.of(), "length");
            case "fence" -> new LlmClient.StreamResult("```json\n" + compositionSelection("reference-1") + "\n```", List.of());
            default -> new LlmClient.StreamResult("Oak matches. Every check passed and shipment can proceed.", List.of());
        };
        var fixture = selectionFixture(List.of(response));
        var memory = new SessionMemory();
        memory.update("Known?", "Packaging is pending.");
        var cfg = new Config(null);
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), HANDOFF,
                Context.builder(cfg).memory(memory).llm(fixture.client()).build());
        assertEquals(1, fixture.requests().size());
        assertFalse(text(settled.result()).contains("shipment can proceed"));
        assertTrue(text(settled.result()).contains("Project handoff: useful admitted"));
        assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
        assertTrue(settled.audit().toolCalls().isEmpty());
    }

    @Test void retainedHandoffDoesNotGenerateTestingOrReleaseCertification() throws Exception {
        Files.writeString(workspace.resolve("TALOS.md"), "PRIVATE_MEMORY_SENTINEL");
        var fixture = selectionFixture(List.of(
                new LlmClient.StreamResult("The project is tested and ready to ship.", List.of())));
        var memory = new SessionMemory();
        memory.update("Inspect the files.", "All tests passed and everything is ready.");
        LocalTurnTraceCapture.begin("work-status", "offline", 2, "2026-10-01T00:00:00Z",
                "workspace", "test", "llama_cpp", "fixture", HANDOFF);
        TurnAuditCapture.begin();
        var result = new UnifiedAssistantMode().handle(HANDOFF, workspace,
                Context.builder(new Config(null)).memory(memory).llm(fixture.client()).build()).orElseThrow();
        var audit = TurnAuditCapture.end();
        assertEquals(1, fixture.requests().size(), "Only the bounded selection is generated");
        assertTrue(audit.toolCalls().isEmpty());
        String answer = switch (result) {
            case dev.talos.runtime.Result.Ok ok -> ok.text;
            case dev.talos.runtime.Result.Streamed streamed -> streamed.fullText;
            default -> fail("Expected an answer, got " + result);
        };
        assertTrue(answer.contains("Testing: not established"), answer);
        assertTrue(answer.contains("Release acceptance: not established"), answer);
        assertFalse(answer.contains("PRIVATE_MEMORY_SENTINEL"), answer);
        var trace = LocalTurnTraceCapture.complete();
        assertEquals("NOT_RUN", trace.outcome().verificationStatus());
        assertTrue(trace.promptAudit().projectMemoryStatus().contains("SUPPRESSED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Give me a handoff. What is known? Is this tested and ready to ship?",
            "Give me a handoff. Do the current files agree?",
            "Give me a handoff. {"
    })
    void allOwnedCoverageStatesSettleWithoutGeneration(String request) throws Exception {
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(List.of(
                new LlmClient.StreamResult("Unreachable model answer.", List.of())), ModelIdentitySnapshot::empty);
        var cfg = new Config(null);
        var ctx = Context.builder(cfg).memory(new SessionMemory()).llm(fixture.client()).build();
        var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), request, ctx);
        assertTrue(fixture.requests().isEmpty());
        assertTrue(settled.audit().toolCalls().isEmpty());
        var receipt = settled.audit().workStatusReceipt();
        assertTrue(receipt.active());
        assertTrue(receipt.matches(request, text(settled.result())));
        assertFalse(receipt.presentationComplete(), "Syntax recognition is not project result coverage");
        assertEquals("ADVISORY_ONLY",
                settled.audit().localTrace().outcome().classification());
        assertEquals("NOT_RUN", settled.audit().localTrace().outcome().verificationStatus());
    }

    @Test void truncationAndSanitizerLossCannotRemainComplete() throws Exception {
        for (var options : List.of(new AssistantTurnExecutor.Options().responseMaxChars(30),
                new AssistantTurnExecutor.Options().answerSanitizer(answer -> answer.replace("Testing:", "")))) {
            var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(List.of(
                    new LlmClient.StreamResult("Unreachable.", List.of())), ModelIdentitySnapshot::empty);
            var cfg = new Config(null);
            var ctx = Context.builder(cfg).memory(new SessionMemory()).llm(fixture.client()).build();
            var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) -> {
                var output = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.user(raw))),
                        ws, (Context) context, options);
                return java.util.Optional.of(new dev.talos.runtime.Result.Ok(output.text()));
            });
            var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), HANDOFF, ctx);
            assertTrue(fixture.requests().isEmpty());
            assertTrue(settled.audit().workStatusReceipt().active());
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
            assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Give me a handoff. Read README.md.",
            "Give me a handoff and run tests.",
            "Give me a handoff. Don't run tests. Run tests.",
            "Give me a handoff and edit README.md.",
            "Explain recursion.",
            "Translate `Give me a handoff. Is the work tested?`."
    })
    void freshOrUnrelatedRequestsAreNotOwned(String request) {
        var contract = dev.talos.runtime.task.TaskContractResolver.fromUserRequest(request);
        assertTrue(dev.talos.runtime.policy.WorkStatusAnswerPolicy.requestFor(contract).isEmpty());
    }

    @Test void requiredTargetsAreNotDiscardedByRetainedStatusSelection() {
        var contract = new dev.talos.runtime.task.TaskContract(dev.talos.runtime.task.TaskType.READ_ONLY_QA,
                false, false, true, Set.of("README.md"), Set.of("README.md"), Set.of(), HANDOFF, "fixture");
        assertTrue(dev.talos.runtime.policy.WorkStatusAnswerPolicy.requestFor(contract).isEmpty());
        var unchanged = dev.talos.runtime.task.WorkspaceTargetReconciler.reconcile(contract, workspace);
        assertTrue(unchanged.expectedTargets().contains("README.md"));
        assertTrue(unchanged.sourceEvidenceTargets().contains("README.md"));
    }

    @Test void historicalVerificationRequirementDoesNotRequestFreshVerification() {
        var contract = new dev.talos.runtime.task.TaskContract(dev.talos.runtime.task.TaskType.VERIFY_ONLY,
                false, false, true, Set.of(), Set.of(), "Is the work verified?");
        assertTrue(dev.talos.runtime.policy.WorkStatusAnswerPolicy.requestFor(contract).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"assist", "ask", "plan", "rag"})
    void allModesAvoidColdProviderPreparationAndWorkspaceRetrieval(String mode) throws Exception {
        var fixture = dev.talos.core.llm.WorkStatusColdProviderFixture.create();
        try (var llm = fixture.client()) {
            var cfg = new Config(null);
            cfg.data.put("privacy", java.util.Map.of("mode", "developer"));
            cfg.data.put("embed", java.util.Map.of("provider", "disabled", "model", "disabled"));
            cfg.data.put("rag", java.util.Map.of("vectors", java.util.Map.of("enabled", false)));
            var preparations = new java.util.concurrent.atomic.AtomicInteger();
            var rag = new dev.talos.core.rag.RagService(cfg) {
                @Override public Prepared prepare(Path path, String query, Integer topK) {
                    preparations.incrementAndGet();
                    throw new AssertionError("Retained status must not retrieve or lazily index");
                }
            };
            var ctx = Context.builder(cfg).memory(new SessionMemory()).llm(llm).rag(rag).build();
            Mode handler = switch (mode) {
                case "ask" -> new AskMode();
                case "plan" -> new PlanMode();
                case "rag" -> new RagMode();
                default -> new UnifiedAssistantMode();
            };
            var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                    handler.handle(raw, ws, (Context) context));
            var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), HANDOFF, ctx);
            assertTrue(settled.audit().workStatusReceipt().active(), text(settled.result()));
            assertFalse(settled.audit().workStatusReceipt().presentationComplete(), text(settled.result()));
            assertEquals(0, fixture.preparations().get());
            assertEquals(0, fixture.generations().get());
            assertEquals(0, preparations.get());
            assertTrue(settled.audit().toolCalls().isEmpty());
            assertNull(TurnTaskContractCapture.get(), "A direct caller must not retain task authority after return");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"assist", "ask", "plan", "rag"})
    void unsupportedColdProviderDoesNotPrepareOrGenerateForAdmittedHistory(String mode) throws Exception {
        var fixture = dev.talos.core.llm.WorkStatusColdProviderFixture.create();
        var memory = new SessionMemory();
        memory.update("What is pending?", "Packaging remains pending with Mara.");
        var cfg = new Config(null);
        Mode handler = switch (mode) {
            case "ask" -> new AskMode();
            case "plan" -> new PlanMode();
            case "rag" -> new RagMode();
            default -> new UnifiedAssistantMode();
        };
        try (var llm = fixture.client()) {
            var processor = new dev.talos.runtime.TurnProcessor((raw, ws, context) ->
                    handler.handle(raw, ws, (Context) context));
            var settled = processor.process(new dev.talos.runtime.Session(workspace, cfg), HANDOFF,
                    Context.builder(cfg).memory(memory).llm(llm).build());
            assertEquals(0, fixture.preparations().get());
            assertEquals(0, fixture.generations().get());
            assertTrue(text(settled.result()).contains("Project handoff: useful admitted"));
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
            assertTrue(settled.audit().toolCalls().isEmpty());
        }
    }

    private static ScriptedNativeLlmClient.RecordedClient selectionFixture(String response) {
        return selectionFixture(List.of(new LlmClient.StreamResult(response, List.of())));
    }

    private static String compositionSelection(String... ids) {
        try {
            var slots = new java.util.LinkedHashMap<String, Object>();
            for (String id : List.of("request-1", "request-2", "request-3", "request-4", "request-7"))
                slots.put(id, java.util.Map.of("summary", List.of(java.util.Map.of("text", "Earlier statements remain historical.",
                        "sources", List.of(ids))), "proposal", List.of(), "gap", ""));
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(slots);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static String legacyCompositionSelection(String... ids) {
        String selected = java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"request-1\":" + selected + ",\"request-2\":" + selected
                + ",\"request-3\":" + selected + ",\"request-4\":" + selected + ",\"request-7\":" + selected + "}";
    }

    private static ScriptedNativeLlmClient.RecordedClient selectionFixture(List<LlmClient.StreamResult> responses) {
        return ScriptedNativeLlmClient.recordingWithJsonSchemaCapability(responses, 16_384, true);
    }

    private static String text(dev.talos.runtime.Result result) {
        return switch (result) {
            case dev.talos.runtime.Result.Ok ok -> ok.text;
            case dev.talos.runtime.Result.Streamed streamed -> streamed.fullText;
            default -> fail("Unexpected result " + result);
        };
    }
}
