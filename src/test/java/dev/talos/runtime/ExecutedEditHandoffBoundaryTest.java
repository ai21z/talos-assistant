package dev.talos.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.modes.UnifiedAssistantMode;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.RenderEngine;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.WorkStatusColdProviderFixture;
import dev.talos.core.security.Sandbox;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.intent.WorkStatusRequest;
import dev.talos.runtime.policy.ProjectHandoffPolicy;
import dev.talos.runtime.policy.WorkStatusAnswerPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.ContentRestrictions;
import dev.talos.tools.*;
import dev.talos.tools.impl.FileEditTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real edit/audit/memory/assistant boundary, with a cold provider and deterministic approval fixture. */
class ExecutedEditHandoffBoundaryTest {
    private static final String REQUEST = "Give me a short handoff. What was denied earlier, what actually changed afterwards, "
            + "and what remains unverified? Do not change files or run tests.";
    private static final String BEFORE = "cedar-original-canary";
    private static final String AFTER = "oak-applied-canary";
    @TempDir Path workspace;

    @AfterEach void clear() {
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test void realDenialsThenEditDisplayOnlyLocallyAndDoNotRebaseHistoricalLiterals() throws Exception {
        var fixture = new Fixture();
        fixture.edit(false, ContentRestrictions.UNRESTRICTED);
        fixture.edit(false, ContentRestrictions.UNRESTRICTED);
        var applied = fixture.edit(true, ContentRestrictions.UNRESTRICTED);
        assertTrue(applied.audit().toolCalls().getFirst().executedEditDetail().available());
        assertFalse(WorkStatusAnswerPolicy.render(WorkStatusRequest.parse(REQUEST), fixture.memory, workspace).contains(AFTER));
        assertFalse(new ObjectMapper().writeValueAsString(fixture.memory.workStatusEvidence()).contains(AFTER));
        assertFalse(new ObjectMapper().writeValueAsString(applied.audit()).contains(AFTER));
        assertFalse(fixture.memory.workStatusEvidence().toString().contains(AFTER));
        Files.writeString(workspace.resolve("notes.txt"), "later-unrelated-current-content");
        var result = fixture.handoff(ContentRestrictions.UNRESTRICTED);
        String answer = MemoryUpdateListener.extractText(result.result());
        assertNotNull(answer);
        assertTrue(answer.contains("user approval denied"), answer);
        assertTrue(answer.contains("Turn 1"), answer);
        assertTrue(answer.contains("Turn 2"), answer);
        assertTrue(answer.contains("exact replacement " + ProjectHandoffPolicy.literal(BEFORE)
                + " -> " + ProjectHandoffPolicy.literal(AFTER)), answer);
        assertTrue(answer.contains("read-back matched at execution"), answer);
        assertTrue(answer.length() <= WorkStatusAnswerPolicy.MAX_OPERATION_DETAIL_CHARS);
        assertFalse(answer.contains("later-unrelated-current-content"), answer);
        assertFalse(result.audit().workStatusReceipt().presentationComplete());
        assertFalse(result.audit().contentRestrictions().modelAllowed());
        assertFalse(result.audit().contentRestrictions().historyAllowed());
        assertFalse(result.audit().contentRestrictions().artifactAllowed());
        assertFalse(result.audit().contentRestrictions().indexAllowed());
        assertTrue(result.audit().toolCalls().isEmpty());
        assertEquals("ADVISORY_ONLY", result.audit().localTrace().outcome().classification());
        assertEquals(0, fixture.provider.generations().get());
        assertEquals(0, fixture.provider.preparations().get());
        assertEquals(0, fixture.rawSinkCalls.get(), "No local literal may bypass final answer settlement");
        assertEquals("", fixture.rawOutput.toString());
        var terminalBytes = new ByteArrayOutputStream();
        var renderer = new RenderEngine(fixture.config, new Redactor(),
                new PrintStream(terminalBytes, true, StandardCharsets.UTF_8), false);
        try {
            renderer.render(result.result());
            String terminal = terminalBytes.toString(StandardCharsets.UTF_8);
            String terminalBody = terminal.lines()
                    .filter(line -> line.startsWith("  │ ") || line.startsWith("  | "))
                    .map(line -> line.substring(4)).collect(java.util.stream.Collectors.joining(" "))
                    .replaceAll("\\s+", " ").strip();
            assertTrue(terminalBody.contains("exact replacement " + ProjectHandoffPolicy.literal(BEFORE)
                    + " -> " + ProjectHandoffPolicy.literal(AFTER)), terminalBody);
            assertTrue(terminalBody.contains("read-back matched at execution"), terminalBody);
            assertFalse(terminal.contains("later-unrelated-current-content"), terminal);
            assertNotNull(renderer.lastAnswerDelivery(), "The actual final renderer must own the delivery observation");
            assertEquals(AnswerDeliveryObservation.State.NO_DETECTED_SINK_ERROR, renderer.lastAnswerDelivery().state());
        } finally { renderer.shutdown(); }
        assertFalse(fixture.memory.getTurns().toString().contains(AFTER));
        assertFalse(new ObjectMapper().writeValueAsString(result.audit()).contains(AFTER));
        for (var record : fixture.store.loadTurns(fixture.sessionId)) {
            assertFalse(record.assistantText().contains(AFTER));
            assertTrue(record.toolCalls().stream().noneMatch(call -> call.executedEditDetail().available()));
        }
        try (var files = Files.walk(fixture.logDirectory)) {
            for (var path : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(path).contains(AFTER), path.toString());
            }
        }
    }

    @Test void sourceAndCurrentDestinationRestrictionsPreventLiteralAdmission() throws Exception {
        for (int bit = 0; bit < 3; bit++) {
            var restriction = restrictions(bit);
            var source = new Fixture();
            var edited = source.edit(true, restriction);
            assertFalse(edited.audit().toolCalls().getFirst().executedEditDetail().available());
            var fallback = source.handoff(ContentRestrictions.UNRESTRICTED);
            assertFalse(MemoryUpdateListener.extractText(fallback.result()).contains(AFTER));

            var current = new Fixture();
            current.edit(true, ContentRestrictions.UNRESTRICTED);
            var result = current.handoff(restriction);
            assertFalse(MemoryUpdateListener.extractText(result.result()).contains(AFTER));
            assertEquals(0, current.provider.generations().get());
        }
    }

    @Test void activeAuditSameWorkspaceAndExplicitRuntimeScopeAreRequired() throws Exception {
        var fixture = new Fixture();
        fixture.edit(true, ContentRestrictions.UNRESTRICTED);
        var request = WorkStatusRequest.parse(REQUEST);
        assertTrue(WorkStatusAnswerPolicy.renderLocalEditDetails(request, fixture.memory, workspace,
                ContentRestrictions.UNRESTRICTED).isEmpty());
        TurnAuditCapture.begin();
        assertTrue(WorkStatusAnswerPolicy.renderLocalEditDetails(request, fixture.memory, workspace.resolve("other"),
                ContentRestrictions.UNRESTRICTED).isEmpty());
        assertTrue(WorkStatusAnswerPolicy.renderLocalEditDetails(WorkStatusRequest.parse("Give me a handoff. What is known?"),
                fixture.memory, workspace, ContentRestrictions.UNRESTRICTED).isEmpty());
        assertTrue(ContentRestrictionScope.current().modelAllowed());
        var value = WorkStatusAnswerPolicy.renderLocalEditDetails(request, fixture.memory, workspace,
                new ContentRestrictions(true, true, true, false));
        assertTrue(value.isPresent());
        assertFalse(ContentRestrictionScope.current().historyAllowed());
    }

    @Test void restrictionsNarrowedAfterCaptureCannotRecoverSourceDetails() throws Exception {
        var fixture = new Fixture();
        var actual = fixture.edit(true, ContentRestrictions.UNRESTRICTED);
        var call = actual.audit().toolCalls().getFirst();
        assertTrue(call.executedEditDetail().available());
        for (int bit = 0; bit < 3; bit++) {
            var lateAudit = new TurnAudit(List.of(call), 1, 1, 0, actual.audit().policyTrace(),
                    actual.audit().localTrace(), restrictions(bit), List.of(), WorkStatusReceipt.none());
            var projection = WorkStatusEvidence.from(new TurnResult(new Result.Ok("settled"), null, 1,
                    java.time.Duration.ZERO, lateAudit));
            assertFalse(projection.calls().getFirst().executedEditDetail().available());
            assertEquals(1, projection.calls().getFirst().appliedEntries());
        }
    }

    @Test void evidenceBudgetDropsWholeDetailsWithoutDroppingEffectsAndOutputIsBounded() throws Exception {
        String before = "old literal ".repeat(40);
        String after = "new literal ".repeat(40);
        var fixture = new Fixture(before, after);
        var actual = fixture.edit(true, ContentRestrictions.UNRESTRICTED);
        var call = actual.audit().toolCalls().getFirst();
        assertTrue(call.executedEditDetail().available());
        var audit = new TurnAudit(java.util.Collections.nCopies(20, call), 20, 20, 0,
                actual.audit().policyTrace(), actual.audit().localTrace(), ContentRestrictions.UNRESTRICTED,
                List.of(), WorkStatusReceipt.none());
        var result = new TurnResult(new Result.Ok("settled"), null, 7, java.time.Duration.ZERO, audit);
        var projection = WorkStatusEvidence.from(result);
        assertEquals(20, projection.calls().size());
        assertEquals(4096 / (before.length() + after.length()), projection.calls().stream()
                .filter(value -> value.executedEditDetail().available()).count());
        assertTrue(projection.calls().stream().allMatch(value -> value.appliedEntries() == 1));
        assertTrue(projection.calls().stream().filter(value -> value.executedEditDetail().available())
                .allMatch(value -> value.executedEditDetail().before().equals(before)
                        && value.executedEditDetail().after().equals(after)));
        fixture.memory.recordWorkStatus(result);
        var settled = fixture.handoff(ContentRestrictions.UNRESTRICTED);
        String answer = MemoryUpdateListener.extractText(settled.result());
        assertTrue(answer.length() <= WorkStatusAnswerPolicy.MAX_OPERATION_DETAIL_CHARS, answer);
        assertTrue(answer.contains("output rows omitted"));
        assertTrue(answer.contains("applied call(s) without admitted literal details"));
        assertFalse(settled.audit().workStatusReceipt().presentationComplete());
    }

    @Test void mixedLegacyFailureAndUncertainEffectsAreNotTurnedIntoSuccessOrAbsence() throws Exception {
        var fixture = new Fixture();
        var actual = fixture.edit(true, ContentRestrictions.UNRESTRICTED);
        var call = actual.audit().toolCalls().getFirst();
        var failedApplied = new TurnRecord.ToolCallSummary(call.name(), call.pathHint(), call.pathHints(), false,
                "diagnostic must not be displayed", call.mutationEffects(), TurnRecord.ToolCallDisposition.EXECUTED,
                ToolFailureReason.NONE, call.executedEditDetail());
        var legacy = new TurnRecord.ToolCallSummary(call.name(), call.pathHint(), List.of(), true, "",
                call.mutationEffects(), TurnRecord.ToolCallDisposition.EXECUTED, ToolFailureReason.NONE);
        var uncertain = new TurnRecord.ToolCallSummary(call.name(), call.pathHint(), List.of(), false, "",
                new ToolMutationEffects(List.of(), List.of("notes.txt")), TurnRecord.ToolCallDisposition.EXECUTED,
                ToolFailureReason.NONE);
        var audit = new TurnAudit(List.of(failedApplied, legacy, uncertain), 0, 0, 0, actual.audit().policyTrace(),
                actual.audit().localTrace(), ContentRestrictions.UNRESTRICTED, List.of(), WorkStatusReceipt.none());
        fixture.memory.recordWorkStatus(new TurnResult(new Result.Ok("settled"), null, 9, java.time.Duration.ZERO, audit));
        String answer = MemoryUpdateListener.extractText(fixture.handoff(ContentRestrictions.UNRESTRICTED).result());
        assertTrue(answer.contains("operation reported failure despite the applied replacement"), answer);
        assertTrue(answer.contains("1 applied call(s) without admitted literal details"), answer);
        assertTrue(answer.contains("1 uncertain effect entries"), answer);
        assertFalse(answer.contains("diagnostic must not be displayed"));
    }

    @Test void admittedMarkupIsEncodedAsLiteralDataNotActiveFormatting() throws Exception {
        String before = "<h1>`before`</h1>";
        String after = "<h1>[after](https://example.invalid)</h1>";
        var fixture = new Fixture(before, after);
        fixture.edit(true, ContentRestrictions.UNRESTRICTED);
        String answer = MemoryUpdateListener.extractText(fixture.handoff(ContentRestrictions.UNRESTRICTED).result());
        assertTrue(answer.contains(ProjectHandoffPolicy.literal(before)), answer);
        assertTrue(answer.contains(ProjectHandoffPolicy.literal(after)), answer);
        assertFalse(answer.contains("<h1>"));
        assertFalse(answer.contains("[after]("));
    }

    private static ContentRestrictions restrictions(int bit) {
        return new ContentRestrictions(bit != 0, bit != 1, bit != 2, true);
    }

    private final class Fixture {
        final Config config = new Config(null);
        final Session session = new Session(workspace, config);
        final SessionMemory memory = new SessionMemory();
        final MemoryUpdateListener memoryListener = new MemoryUpdateListener(new ConversationManager(memory, new TokenBudget()), null, memory);
        final WorkStatusColdProviderFixture provider = WorkStatusColdProviderFixture.create();
        final Path logDirectory;
        final JsonSessionStore store;
        final String sessionId = JsonSessionStore.sessionIdFor(workspace);
        final AtomicBoolean approved = new AtomicBoolean();
        final AtomicInteger rawSinkCalls = new AtomicInteger();
        final StringBuilder rawOutput = new StringBuilder();
        final TurnProcessor toolProcessor;
        final String before;
        final String after;

        Fixture() throws Exception { this(BEFORE, AFTER); }

        Fixture(String before, String after) throws Exception {
            this.before = before;
            this.after = after;
            Files.writeString(workspace.resolve("notes.txt"), before);
            logDirectory = Files.createTempDirectory(workspace, "handoff-log-");
            store = new JsonSessionStore(logDirectory);
            var registry = new ToolRegistry();
            registry.register(new FileEditTool());
            toolProcessor = new TurnProcessor((raw, ws, ctx) -> Optional.of(new Result.Ok("unused")),
                    (description, detail) -> approved.get(), registry);
        }

        TurnResult edit(boolean permit, ContentRestrictions restrictions) throws Exception {
            approved.set(permit);
            var processor = new TurnProcessor((raw, ws, ctx) -> {
                ContentRestrictionScope.restrict(restrictions);
                var result = toolProcessor.executeTool(session, new ToolCall("talos.edit_file", Map.of(
                        "path", "notes.txt", "old_string", before, "new_string", after)), ctx);
                assertEquals(permit, result.success(), result.errorMessage());
                return Optional.of(new Result.Ok("Prior operation settled."));
            });
            processor.addListener(memoryListener);
            processor.addListener(new JsonTurnLogAppender(store, sessionId));
            return processor.process(session, "Edit notes.txt and replace the requested text.",
                    Context.builder(config).memory(memory).sandbox(new Sandbox(workspace, null)).build());
        }

        TurnResult handoff(ContentRestrictions restrictions) throws Exception {
            var processor = new TurnProcessor((raw, ws, ctx) -> {
                ContentRestrictionScope.restrict(restrictions);
                var result = new UnifiedAssistantMode().handle(raw, ws, (Context) ctx);
                assertEquals(0, rawSinkCalls.get(), "The answer sink must remain empty before settlement");
                assertEquals("", rawOutput.toString());
                if (result.isPresent() && MemoryUpdateListener.extractText(result.get()).contains(AFTER))
                    assertEquals(new ContentRestrictions(false, false, false, false), ContentRestrictionScope.current());
                return result;
            });
            processor.addListener(memoryListener);
            processor.addListener(new JsonTurnLogAppender(store, sessionId));
            return processor.process(session, REQUEST, Context.builder(config).memory(memory).llm(provider.client())
                    .streamSink(chunk -> {
                        rawSinkCalls.incrementAndGet();
                        rawOutput.append(chunk);
                    }).build());
        }
    }
}
