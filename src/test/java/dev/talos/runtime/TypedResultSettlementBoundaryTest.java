package dev.talos.runtime;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.runtime.verification.ReadOnlyAnswerComponents;
import dev.talos.runtime.verification.ReadOnlyAnswerInterpretation;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.ContentRestrictions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted producer assessments traverse real terminalization and memory publication. */
class TypedResultSettlementBoundaryTest {
    private static final String REQUEST = "Explain both requested results without running anything.";
    private static final String HASH = "a".repeat(64);
    @TempDir Path workspace;

    @Test void unresolvedResultsAndSharedReferencesRemainSeparate() throws Exception {
        var components = components(ReadOnlyAnswerComponents.Support.ATTRIBUTED_INTERPRETATION,
                ReadOnlyAnswerComponents.Support.INSUFFICIENT_EVIDENCE);
        assertFalse(components.complete());
        var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        var retained = run.result.audit().typedResults();
        assertEquals(TypedResultSettlement.Loss.NONE, retained.loss());
        assertTrue(retained.presentationIntact());
        assertTrue(retained.historyProjectionIntact());
        assertEquals(components.coverageOrigin(), retained.coverageOrigin());
        assertTrue(retained.results().stream().allMatch(entry -> !entry.reasonRetained()
                && entry.reason().isEmpty()));
        assertEquals(List.of(ReadOnlyAnswerComponents.Support.ATTRIBUTED_INTERPRETATION,
                ReadOnlyAnswerComponents.Support.INSUFFICIENT_EVIDENCE),
                retained.results().stream().map(TypedResultSettlement.Entry::support).toList());
        assertEquals(2, retained.results().size());
        assertNotEquals(retained.results().getFirst().requirementKey(), retained.results().getLast().requirementKey());
        assertEquals(retained.results().getFirst().references(), retained.results().getLast().references());
        assertSame(retained, run.memory.typedResultHistory().turns().getFirst().settlement());
        assertFalse(run.memory.typedResultHistory().scopeEstablished());
    }

    @Test void truncatedPresentationDoesNotRetainUndeliveredContent() throws Exception {
        var components = components(ReadOnlyAnswerComponents.Support.ESTABLISHED);
        var run = run(components, "[Answer incomplete]", ContentRestrictions.UNRESTRICTED);
        assertWithheld(run.result.audit().typedResults(), TypedResultSettlement.Loss.PRESENTATION_CHANGED, 1);
    }

    @Test void secretNormalizationDoesNotRetainRawComponentText() throws Exception {
        var secret = "sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz";
        var components = new ReadOnlyAnswerComponents(List.of(component("r1",
                ReadOnlyAnswerComponents.Support.ESTABLISHED, "api_key=" + secret)), true);
        var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        assertWithheld(run.result.audit().typedResults(), TypedResultSettlement.Loss.PRESENTATION_CHANGED, 1);
        assertFalse(run.memory.get().contains(secret));
    }

    @Test void historyAndArtifactRestrictionsWithholdPayload() throws Exception {
        for (var restrictions : List.of(new ContentRestrictions(true, true, false, true),
                new ContentRestrictions(true, false, true, true))) {
            var components = components(ReadOnlyAnswerComponents.Support.ESTABLISHED);
            var run = run(components, components.render(), restrictions);
            var retained = run.result.audit().typedResults();
            assertWithheld(retained, TypedResultSettlement.Loss.CONTENT_WITHHELD, 1);
            assertEquals("", retained.requestHash());
            assertEquals(restrictions, retained.contentRestrictions());
        }
    }

    @Test void modelRestrictionIsRetainedWithoutGrantingModelPermission() throws Exception {
        var restrictions = new ContentRestrictions(false, true, true, true);
        var components = components(ReadOnlyAnswerComponents.Support.ESTABLISHED);
        var run = run(components, components.render(), restrictions);
        assertEquals(TypedResultSettlement.Loss.NONE, run.result.audit().typedResults().loss());
        assertFalse(run.memory.typedResultHistory().turns().getFirst().settlement().contentRestrictions().modelAllowed());
    }

    @Test void everySupportStatusIsRetainedWithoutInventingScope() throws Exception {
        var components = components(ReadOnlyAnswerComponents.Support.values());
        var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        assertEquals(List.of(ReadOnlyAnswerComponents.Support.values()),
                run.result.audit().typedResults().results().stream().map(TypedResultSettlement.Entry::support).toList());
        assertFalse(run.result.audit().typedResults().requestCovered());
        assertFalse(run.memory.typedResultHistory().scopeEstablished());
    }

    @Test void eachInterpretationDecisionRemainsSeparateFromUnsupportedEvidence() throws Exception {
        for (var decision : ReadOnlyAnswerInterpretation.Decision.values()) {
            var component = new ReadOnlyAnswerComponents.Component("region-1", "UNKNOWN",
                    ReadOnlyAnswerComponents.Support.UNSUPPORTED, "UNRESOLVED_REQUEST_MEANING",
                    "The requested meaning remains unresolved.", List.of(), "", null, decision);
            var components = new ReadOnlyAnswerComponents(List.of(component), false);
            var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
            var entry = run.result.audit().typedResults().results().getFirst();
            assertEquals(decision, entry.interpretationDecision());
            assertEquals(ReadOnlyAnswerComponents.Support.UNSUPPORTED, entry.support());
            assertFalse(entry.reasonRetained());
            assertFalse(run.result.audit().typedResults().requestCovered());
        }
    }

    @Test void undisplayedFreeFormReasonIsUnavailableRatherThanCopied() throws Exception {
        var secret = "sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz";
        var component = new ReadOnlyAnswerComponents.Component("r1", "DIAGNOSIS",
                ReadOnlyAnswerComponents.Support.INSUFFICIENT_EVIDENCE, "api_key=" + secret,
                "The evidence remains insufficient.", List.of(), "");
        var components = new ReadOnlyAnswerComponents(List.of(component), false);
        assertFalse(components.render().contains(secret));
        var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        var entry = run.result.audit().typedResults().results().getFirst();
        assertFalse(entry.reasonRetained());
        assertEquals("", entry.reason());
        assertTrue(entry.textRetained());
        assertEquals(component.text(), entry.text());
        assertFalse(run.memory.get().contains(secret));
    }

    @Test void withheldPayloadDoesNotChangeFiniteInterpretationDecision() throws Exception {
        var component = new ReadOnlyAnswerComponents.Component("region-1", "UNKNOWN",
                ReadOnlyAnswerComponents.Support.UNSUPPORTED, "UNRESOLVED_REQUEST_MEANING",
                "The requested meaning remains unresolved.", List.of(), "", null,
                ReadOnlyAnswerInterpretation.Decision.MISSING);
        var components = new ReadOnlyAnswerComponents(List.of(component), false);
        var run = run(components, components.render(), new ContentRestrictions(true, true, false, true));
        assertWithheld(run.result.audit().typedResults(), TypedResultSettlement.Loss.CONTENT_WITHHELD, 1);
        assertEquals(ReadOnlyAnswerInterpretation.Decision.MISSING,
                run.result.audit().typedResults().results().getFirst().interpretationDecision());
    }

    @Test void codeShapedHiddenPayloadIsNotAdmittedAsAReason() throws Exception {
        var component = new ReadOnlyAnswerComponents.Component("r1", "DIAGNOSIS",
                ReadOnlyAnswerComponents.Support.UNSUPPORTED, "PRIVATE_TOKEN_ABC123",
                "The evidence remains insufficient.", List.of(), "");
        var components = new ReadOnlyAnswerComponents(List.of(component), false);
        var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        var retained = run.result.audit().typedResults().results().getFirst();
        assertFalse(retained.reasonRetained());
        assertEquals("", retained.reason());
    }

    @Test void displayedReasonHasIndependentAvailability() throws Exception {
        var component = new ReadOnlyAnswerComponents.Component("r1", "DIAGNOSIS",
                ReadOnlyAnswerComponents.Support.UNSUPPORTED, "EVIDENCE_UNAVAILABLE",
                "EVIDENCE_UNAVAILABLE was reported by the bounded producer.", List.of(), "");
        var components = new ReadOnlyAnswerComponents(List.of(component), false);
        var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        var retained = run.result.audit().typedResults().results().getFirst();
        assertTrue(retained.reasonRetained());
        assertEquals(component.reason(), retained.reason());
    }

    @Test void hiddenDeduplicatedComponentTextIsNotRetained() throws Exception {
        var group = new ReadOnlyAnswerComponents.ObservationPresentation(HASH, "DIAGNOSIS", "r1", "A displayed observation.");
        var secondGroup = new ReadOnlyAnswerComponents.ObservationPresentation(HASH, "DIAGNOSIS", "r2", "A displayed observation.");
        var reference = List.of(new ReadOnlyAnswerComponents.Reference("observed-1", HASH, 0, 1));
        var components = new ReadOnlyAnswerComponents(List.of(
                new ReadOnlyAnswerComponents.Component("r1", "OBSERVATION", ReadOnlyAnswerComponents.Support.ATTRIBUTED_INTERPRETATION,
                        "NOT_ESTABLISHED", "Hidden first payload.", reference, "", group, null),
                new ReadOnlyAnswerComponents.Component("r2", "OBSERVATION", ReadOnlyAnswerComponents.Support.ATTRIBUTED_INTERPRETATION,
                        "NOT_ESTABLISHED", "Hidden second payload.", reference, "", secondGroup, null),
                component("r3", ReadOnlyAnswerComponents.Support.ESTABLISHED, "An unrelated displayed result.")), false);
        assertFalse(components.render().contains("Hidden first payload"));
        var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        var retained = run.result.audit().typedResults();
        assertEquals(TypedResultSettlement.Loss.NONE, retained.loss());
        assertEquals(3, retained.results().size());
        assertFalse(retained.results().getFirst().textRetained());
        assertEquals("", retained.results().getFirst().text());
        assertEquals(reference, retained.results().getFirst().references());
        assertEquals("r1", retained.results().getFirst().requirementId());
        assertTrue(retained.results().getLast().textRetained());
        assertEquals("An unrelated displayed result.", retained.results().getLast().text());
    }

    @Test void duplicateRequirementIdentityIsUnavailableRatherThanMerged() throws Exception {
        var component = component("r1", ReadOnlyAnswerComponents.Support.ESTABLISHED, "An observed result.");
        var components = new ReadOnlyAnswerComponents(List.of(component, component), true);
        var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        assertWithheld(run.result.audit().typedResults(), TypedResultSettlement.Loss.BINDING_UNAVAILABLE, 2);
        assertNotEquals(run.result.audit().typedResults().results().getFirst().requirementKey(),
                run.result.audit().typedResults().results().getLast().requirementKey());
    }

    @Test void changedRequestCannotBindCapturedResults() throws Exception {
        var components = components(ReadOnlyAnswerComponents.Support.ESTABLISHED);
        var result = TypedResultSettlement.settle(UUID.randomUUID(), REQUEST, "Another request.", components,
                components.render(), components.render(), ContentRestrictions.UNRESTRICTED);
        assertWithheld(result, TypedResultSettlement.Loss.BINDING_UNAVAILABLE, 1);
    }

    @Test void committedTextChangeIsIndependentOfDeliveredText() {
        var components = components(ReadOnlyAnswerComponents.Support.ESTABLISHED);
        var result = TypedResultSettlement.settle(UUID.randomUUID(), REQUEST, REQUEST, components,
                components.render(), "Changed retained answer.", ContentRestrictions.UNRESTRICTED);
        assertWithheld(result, TypedResultSettlement.Loss.RETENTION_CHANGED, 1);
        assertTrue(result.presentationIntact());
        assertFalse(result.historyProjectionIntact());
    }

    @Test void absentProducerDoesNotCertifyEmptyResultScope() throws Exception {
        var run = run(ReadOnlyAnswerComponents.empty(), "An ordinary answer.", ContentRestrictions.UNRESTRICTED);
        assertEquals(TypedResultSettlement.Loss.NO_TYPED_RESULTS, run.result.audit().typedResults().loss());
        assertFalse(run.memory.typedResultHistory().scopeEstablished());
        assertTrue(run.memory.typedResultHistory().conversationPresent());
    }

    @Test void abnormalSettlementsRetainUnavailableOutcomesWithoutConversation() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            int outcome = variant;
            var memory = new SessionMemory();
            var components = components(ReadOnlyAnswerComponents.Support.ESTABLISHED);
            var processor = processor(memory, (raw, root, context) -> {
                TurnAuditCapture.recordReadOnlyResults(raw, components);
                if (outcome == 0) throw new IllegalStateException("Synthetic failure.");
                if (outcome == 1) throw new CancellationException("Synthetic cancellation.");
                return Optional.empty();
            });
            var session = new Session(workspace, new Config());
            if (outcome < 2) assertThrows(RuntimeException.class, () -> processor.process(session, REQUEST, context()));
            else processor.process(session, REQUEST, context());
            assertEquals(1, memory.typedResultHistory().turns().size());
            assertWithheld(memory.typedResultHistory().turns().getFirst().settlement(),
                    TypedResultSettlement.Loss.TERMINAL_UNAVAILABLE, 1);
            assertFalse(memory.hasContent());
        }
    }

    @Test void abortedPresentationCannotEscapeThroughListenerEarlyReturn() throws Exception {
        var components = components(ReadOnlyAnswerComponents.Support.ESTABLISHED);
        var run = run(components, components.render() + "\n[turn aborted: interrupted]", ContentRestrictions.UNRESTRICTED);
        assertEquals(1, run.memory.typedResultHistory().turns().size());
        assertWithheld(run.memory.typedResultHistory().turns().getFirst().settlement(),
                TypedResultSettlement.Loss.TERMINAL_UNAVAILABLE, 1);
        assertFalse(run.memory.hasContent());
    }

    @Test void traceAttachmentAndTerminalProjectionPreserveOrDowngradeTheProjection() throws Exception {
        var components = components(ReadOnlyAnswerComponents.Support.ESTABLISHED);
        var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        assertEquals(run.result.audit().typedResults(), run.result.audit().withLocalTrace(null).typedResults());
        var unavailable = run.result.audit().safeTerminalProjection(TurnSettlement.Kind.FAILED, false).typedResults();
        assertWithheld(unavailable, TypedResultSettlement.Loss.TERMINAL_UNAVAILABLE, 1);
        assertWithheld(run.result.audit().safeTerminalProjection(TurnSettlement.Kind.CANCELLED, false)
                .withLocalTrace(null).typedResults(), TypedResultSettlement.Loss.TERMINAL_UNAVAILABLE, 1);
    }

    @Test void restoredProseDoesNotReconstituteTypedAssociations() {
        var memory = new SessionMemory();
        memory.update(REQUEST, "A saved answer claiming completion.");
        assertTrue(memory.typedResultHistory().conversationPresent());
        assertTrue(memory.typedResultHistory().turns().isEmpty());
        assertFalse(memory.typedResultHistory().scopeEstablished());
    }

    @Test void compactionDoesNotSilentlyResolveIndependentlyRetainedResults() throws Exception {
        var components = components(ReadOnlyAnswerComponents.Support.INSUFFICIENT_EVIDENCE);
        var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        run.memory.pruneOldest(2);
        assertFalse(run.memory.hasContent());
        assertEquals(1, run.memory.typedResultHistory().turns().size());
        assertEquals(ReadOnlyAnswerComponents.Support.INSUFFICIENT_EVIDENCE,
                run.memory.typedResultHistory().turns().getFirst().settlement().results().getFirst().support());
    }

    @Test void evidenceCapAndClearHaveExplicitLifetimeAndEvictionBehavior() throws Exception {
        var memory = new SessionMemory();
        var processor = processor(memory, (raw, root, context) -> Optional.of(new Result.Ok("An ordinary answer.")));
        var session = new Session(workspace, new Config());
        for (int turn = 0; turn < 205; turn++) processor.process(session, REQUEST, context());
        assertEquals(200, memory.typedResultHistory().turns().size());
        assertEquals(5, memory.typedResultHistory().evictedTurns());
        var lifetime = memory.typedResultHistory().turns().getFirst().lifetimeId();
        memory.clear();
        assertTrue(memory.typedResultHistory().turns().isEmpty());
        assertEquals(0, memory.typedResultHistory().evictedTurns());
        processor.process(session, REQUEST, context());
        assertNotEquals(lifetime, memory.typedResultHistory().turns().getFirst().lifetimeId());
    }

    @Test void characterCapEvictsWholeHistoricalTurns() throws Exception {
        var components = new ReadOnlyAnswerComponents(IntStream.range(0, 20).mapToObj(index ->
                component("r" + index, ReadOnlyAnswerComponents.Support.ESTABLISHED, "x".repeat(2_000))).toList(), true);
        var memory = new SessionMemory();
        var processor = processor(memory, (raw, root, context) -> {
            TurnAuditCapture.recordReadOnlyResults(raw, components);
            return Optional.of(new Result.Ok(components.render()));
        });
        var session = new Session(workspace, new Config());
        for (int turn = 0; turn < 8; turn++) processor.process(session, REQUEST, context());
        assertTrue(memory.typedResultHistory().evictedTurns() > 0);
        assertEquals(8, memory.typedResultHistory().turns().size() + memory.typedResultHistory().evictedTurns());
        assertTrue(memory.typedResultHistory().turns().stream().mapToLong(turn -> turn.settlement().characters()).sum() <= 256_000);
    }

    @Test void oversizedPayloadRetainsStructuralLossInsteadOfPartOfTheRequirements() throws Exception {
        var components = new ReadOnlyAnswerComponents(IntStream.range(0, 10).mapToObj(index ->
                component("r" + index, ReadOnlyAnswerComponents.Support.ESTABLISHED, "x".repeat(8_000))).toList(), true);
        var run = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        assertWithheld(run.result.audit().typedResults(), TypedResultSettlement.Loss.LIMIT_EXCEEDED, 10);
    }

    @Test void repeatedLocalRequirementIdsAreQualifiedByDifferentTurnsAndSessions() throws Exception {
        var components = components(ReadOnlyAnswerComponents.Support.ESTABLISHED);
        var first = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        var second = run(components, components.render(), ContentRestrictions.UNRESTRICTED);
        assertNotEquals(first.result.audit().typedResults().turnId(), second.result.audit().typedResults().turnId());
        assertNotEquals(first.result.audit().typedResults().results().getFirst().requirementKey(),
                second.result.audit().typedResults().results().getFirst().requirementKey());
        assertNotEquals(first.memory.typedResultHistory().turns().getFirst().lifetimeId(),
                second.memory.typedResultHistory().turns().getFirst().lifetimeId());
    }

    private Run run(ReadOnlyAnswerComponents components, String answer, ContentRestrictions restrictions) throws Exception {
        var memory = new SessionMemory();
        var processor = processor(memory, (raw, root, context) -> {
            TurnAuditCapture.recordReadOnlyResults(raw, components);
            ContentRestrictionScope.restrict(restrictions);
            return Optional.of(new Result.Ok(answer));
        });
        var result = processor.process(new Session(workspace, new Config()), REQUEST, context());
        return new Run(result, memory);
    }

    private static TurnProcessor processor(SessionMemory memory, TurnRouter router) {
        var processor = new TurnProcessor(router);
        processor.addListener(new MemoryUpdateListener(new ConversationManager(memory, new TokenBudget()), null, memory));
        return processor;
    }

    private static Context context() { return Context.builder(new Config()).build(); }

    private static ReadOnlyAnswerComponents components(ReadOnlyAnswerComponents.Support... support) {
        return new ReadOnlyAnswerComponents(IntStream.range(0, support.length).mapToObj(index ->
                component("r" + index, support[index], "Result " + index + " remains recorded.")).toList(), false,
                ReadOnlyAnswerComponents.CoverageOrigin.REQUEST_MEANING);
    }

    private static ReadOnlyAnswerComponents.Component component(String id,
            ReadOnlyAnswerComponents.Support support, String text) {
        return new ReadOnlyAnswerComponents.Component(id, "DIAGNOSIS", support, "BOUNDED_PRODUCER", text,
                List.of(new ReadOnlyAnswerComponents.Reference("observed-1", HASH, 0, 1)), HASH);
    }

    private static void assertWithheld(TypedResultSettlement result, TypedResultSettlement.Loss loss, int count) {
        assertEquals(loss, result.loss());
        assertEquals(count, result.results().size());
        for (var entry : result.results()) {
            assertEquals("", entry.requirementId());
            assertEquals("", entry.kind());
            assertEquals("", entry.reason());
            assertFalse(entry.reasonRetained());
            assertEquals("", entry.text());
            assertFalse(entry.textRetained());
            assertTrue(entry.references().isEmpty());
            assertEquals("", entry.derivationHash());
            assertTrue(entry.requirementKey().matches("[a-f0-9]{64}"));
        }
    }

    private record Run(TurnResult result, SessionMemory memory) {}
}
