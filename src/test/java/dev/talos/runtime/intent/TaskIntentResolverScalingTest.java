package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskIntentResolverScalingTest {

    @Test
    void sequentialResolutionUsesOneMaskedViewAndOneBindingIndex() {
        Fixture small = fixture(64);
        Fixture large = fixture(128);

        var smallMeasured = TaskIntentResolver.resolveMeasured(
                small.request(), small.contract());
        var largeMeasured = TaskIntentResolver.resolveMeasured(
                large.request(), large.contract());

        assertEquals(63, smallMeasured.resolution()
                .sequentialMutationPlan().occurrenceDecisions().size());
        assertEquals(127, largeMeasured.resolution()
                .sequentialMutationPlan().occurrenceDecisions().size());
        assertEquals(64, smallMeasured.resolution().intent()
                .targets().targets().size());
        assertEquals(128, largeMeasured.resolution().intent()
                .targets().targets().size());

        for (var measured : List.of(smallMeasured, largeMeasured)) {
            var work = measured.work();
            assertEquals(0, work.legacyIntentBuilds(), work.toString());
            assertEquals(1, work.ownedPlanMaterializations(), work.toString());
            assertEquals(1, work.legacyLexicalViewBuilds(), work.toString());
            assertEquals(1, work.requiredBindingBatchBuilds(), work.toString());
            assertNotNull(work.requiredBindingWork(), work.toString());
            assertEquals(1,
                    work.requiredBindingWork().targetIndexBuilds(),
                    work.toString());
            assertEquals(1,
                    work.requiredBindingWork().normalizedRequestCopies(),
                    work.toString());
            assertEquals(1,
                    work.requiredBindingWork().authorityActionScans(),
                    work.toString());
            assertTrue(
                    work.targetAccumulatorWrites()
                            <= work.requiredBindingCandidateCount() * 2,
                    work.toString());
        }

        assertEquals(64, smallMeasured.work().requiredBindingCandidateCount());
        assertEquals(128, largeMeasured.work().requiredBindingCandidateCount());
        assertTrue(
                largeMeasured.work().totalVisits()
                        <= smallMeasured.work().totalVisits() * 5 / 2 + 256,
                () -> "resolver work grew too quickly: small="
                        + smallMeasured.work() + ", large="
                        + largeMeasured.work());
    }

    @Test
    void sequentialOptionalRepairMentionCannotRestoreInheritedAuthority() {
        String followUp = "Repair the remaining issue. Edit anchor.txt. "
                + "If needed, update inherited.txt.";
        TaskResolution resolution = TaskContractResolver.resolveFromMessages(
                List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.user("Edit inherited.txt."),
                        ChatMessage.assistant(
                                "Task incomplete; no file changes were applied."),
                        ChatMessage.user(followUp)));

        assertEquals(
                SequentialMutationClausePlan.Authority.OPTIONAL,
                resolution.sequentialMutationPlan()
                        .effectiveDecision("inherited.txt")
                        .orElseThrow()
                        .authority());
        assertFalse(resolution.contract().expectedTargets()
                .contains("inherited.txt"), resolution.toString());
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                resolution.intent().targets().find("inherited.txt")
                        .orElseThrow()
                        .role());
        assertTrue(resolution.contract().expectedTargets()
                .contains("anchor.txt"), resolution.toString());
    }

    @Test
    void largeSequentialPlansNeverBuildTheQuadraticLegacyParityIntent() {
        Fixture medium = fixture(512);
        Fixture large = fixture(1_024);

        var mediumMeasured = TaskIntentResolver.resolveMeasured(
                medium.request(), medium.contract());
        var largeMeasured = TaskIntentResolver.resolveMeasured(
                large.request(), large.contract());

        assertEquals(511, mediumMeasured.resolution()
                .sequentialMutationPlan().occurrenceDecisions().size());
        assertEquals(1_023, largeMeasured.resolution()
                .sequentialMutationPlan().occurrenceDecisions().size());
        assertEquals(0, mediumMeasured.work().legacyIntentBuilds(),
                mediumMeasured.work().toString());
        assertEquals(0, largeMeasured.work().legacyIntentBuilds(),
                largeMeasured.work().toString());
        assertTrue(
                largeMeasured.work().totalVisits()
                        <= mediumMeasured.work().totalVisits() * 5 / 2 + 2_048,
                () -> "large sequential resolution grew too quickly: medium="
                        + mediumMeasured.work() + ", large="
                        + largeMeasured.work());
    }

    @Test
    void projectedDurableRoleEvidenceOverflowFailsClosedBeforeTraceJson() {
        String request = "Edit anchor.css. Then update a.css, b.css "
                + "carefully ".repeat(58_500)
                + ".";
        TaskContract legacy = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("anchor.css", "a.css", "b.css"),
                Set.of(),
                Set.of(),
                request,
                "projected-role-evidence-bound-fixture");

        var measured = TaskIntentResolver.resolveMeasured(request, legacy);
        var resolution = measured.resolution();

        assertTrue(resolution.sequentialMutationPlan()
                .authorityLimitExceeded());
        assertEquals(
                SequentialMutationClausePlan.RegionKind
                        .LEXICAL_EVIDENCE_LIMIT_QUARANTINE,
                resolution.sequentialMutationPlan().authorityLimit().kind());
        assertFalse(resolution.intent().mutationAllowed());
        assertTrue(resolution.intent().targets().find("a.css").isEmpty());
        assertTrue(resolution.intent().targets().find("b.css").isEmpty());
        assertTrue(MutationPlanSelection.selected(
                resolution.mutationTailPlan(),
                resolution.sequentialMutationPlan()).isEmpty());
    }

    @Test
    void targetOccurrenceLimitCannotRestoreLegacyMutationAuthority() {
        String acceptedRequest = "Edit index.html. For reference, "
                + "a.txt ".repeat(
                        CoordinatedMutationTailParser
                                .MAX_INDEXED_TARGET_OCCURRENCES - 1);
        TaskResolution accepted = TaskContractResolver.resolveFromUserRequest(
                acceptedRequest);
        assertEquals(Set.of("index.html"), accepted.contract().expectedTargets());
        assertFalse(accepted.contract().expectedTargets().contains("a.txt"));

        String overflowRequest = "Edit index.html. For reference, "
                + "a.txt ".repeat(
                        CoordinatedMutationTailParser
                                .MAX_INDEXED_TARGET_OCCURRENCES);
        TaskResolution overflow = TaskContractResolver.resolveFromUserRequest(
                overflowRequest);

        assertEquals(Set.of(), overflow.contract().expectedTargets());
        assertFalse(overflow.hasAuthorizedContentMutation());
        assertTrue(overflow.blocksUnscopedMutation());
        assertTrue(overflow.sequentialMutationPlan().ownedRegions().isEmpty());
        assertEquals(
                SequentialMutationClausePlan.RegionKind
                        .TARGET_OCCURRENCE_LIMIT_QUARANTINE,
                overflow.sequentialMutationPlan().authorityLimit().kind());
        assertEquals(
                "sequential-target-occurrence-limit-exceeded",
                overflow.contract().classificationReason());

        TaskContract explicit = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html", "a.txt"),
                Set.of(),
                Set.of(),
                overflowRequest,
                "target-occurrence-limit-work-fixture");
        var measured = TaskIntentResolver.resolveMeasured(
                overflowRequest,
                explicit);
        assertEquals(0, measured.work().legacyLexicalViewBuilds());
        assertEquals(0, measured.work().requiredBindingBatchBuilds());
        assertEquals(Set.of(), TaskContractCompiler.compile(
                measured.resolution().intent()).expectedTargets());
    }

    @Test
    void targetOccurrenceLimitIsIdenticalForLfAndCrLfRequests() {
        for (String newline : List.of("\n", "\r\n")) {
            String request = "Edit index.html." + newline + "For reference, "
                    + "a.txt ".repeat(
                            CoordinatedMutationTailParser
                                    .MAX_INDEXED_TARGET_OCCURRENCES);
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertTrue(
                    resolution.sequentialMutationPlan()
                            .targetIndexLimitExceeded(),
                    newline.equals("\n") ? "LF" : "CRLF");
            assertEquals(Set.of(), resolution.contract().expectedTargets());
            assertFalse(resolution.hasAuthorizedContentMutation());
            assertEquals(
                    "sequential-target-occurrence-limit-exceeded",
                    resolution.contract().classificationReason());
            assertTrue(resolution.sequentialMutationPlan()
                    .ownedRegions().isEmpty());
            assertEquals(
                    SequentialMutationClausePlan.RegionKind
                            .TARGET_OCCURRENCE_LIMIT_QUARANTINE,
                    resolution.sequentialMutationPlan()
                            .authorityLimit().kind());
        }
    }

    @Test
    void targetOutputVisitLimitHasAnExactBoundaryAndDisablesTheWholeTurn() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (int length = 1; length <= 180; length++) {
            candidates.add("a".repeat(length));
        }
        String acceptedRequest = "Edit " + "a".repeat(180) + ". "
                + "a".repeat(13) + " " + "a".repeat(2) + ".";
        var acceptedScan = CoordinatedMutationTailParser.targetOccurrenceScan(
                RequestLexicalView.of(acceptedRequest),
                List.copyOf(candidates),
                0,
                acceptedRequest.length());

        assertEquals(
                CoordinatedMutationTailParser.TargetOccurrenceScanStatus.COMPLETE,
                acceptedScan.status());
        assertEquals(
                CoordinatedMutationTailParser.MAX_TARGET_OUTPUT_VISITS,
                acceptedScan.outputVisits());
        assertEquals(3, acceptedScan.indexedOccurrenceCount());

        String overflowRequest = acceptedRequest + " a.";
        var overflowScan = CoordinatedMutationTailParser.targetOccurrenceScan(
                RequestLexicalView.of(overflowRequest),
                List.copyOf(candidates),
                0,
                overflowRequest.length());
        assertEquals(
                CoordinatedMutationTailParser.TargetOccurrenceScanStatus
                        .OUTPUT_VISIT_LIMIT,
                overflowScan.status());
        assertEquals(
                CoordinatedMutationTailParser.MAX_TARGET_OUTPUT_VISITS + 1L,
                overflowScan.outputVisits());
        assertEquals(0, overflowScan.indexedOccurrenceCount());
        assertTrue(overflowScan.occurrences().isEmpty());

        TaskContract legacy = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.copyOf(candidates),
                Set.of(),
                Set.of(),
                overflowRequest,
                "target-output-limit-work-fixture");
        var measured = TaskIntentResolver.resolveMeasured(
                overflowRequest,
                legacy);
        TaskResolution overflow = new TaskResolution(
                TaskContractCompiler.compile(measured.resolution().intent()),
                measured.resolution().intent(),
                measured.resolution().mutationTailPlan(),
                measured.resolution().sequentialMutationPlan());

        assertFalse(overflow.hasAuthorizedContentMutation());
        assertTrue(overflow.blocksUnscopedMutation());
        assertEquals(Set.of(), overflow.contract().expectedTargets());
        assertEquals(
                "sequential-target-output-limit-exceeded",
                overflow.contract().classificationReason());
        assertTrue(overflow.sequentialMutationPlan().ownedRegions().isEmpty());
        assertEquals(
                SequentialMutationClausePlan.RegionKind
                        .TARGET_OUTPUT_LIMIT_QUARANTINE,
                overflow.sequentialMutationPlan().authorityLimit().kind());
        assertEquals(0, measured.work().legacyLexicalViewBuilds());
        assertEquals(0, measured.work().requiredBindingBatchBuilds());
    }

    @Test
    void targetIndexLimitDisablesEarlierTailAuthorityButPreservesProhibitions() {
        String request = "Edit index.html so update style.css and do not edit "
                + "secrets.env. " + "a.txt ".repeat(
                        CoordinatedMutationTailParser
                                .MAX_INDEXED_TARGET_OCCURRENCES);
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertFalse(resolution.mutationTailPlan().present());
        assertTrue(MutationPlanSelection.selected(
                resolution.mutationTailPlan(),
                resolution.sequentialMutationPlan()).isEmpty());
        assertFalse(resolution.hasAuthorizedContentMutation());
        assertTrue(resolution.blocksUnscopedMutation());
        assertEquals(Set.of(), resolution.contract().expectedTargets());
        assertFalse(resolution.contract().mutationAllowed());
        assertTrue(resolution.contract().forbiddenTargets()
                .contains("secrets.env"));
        assertTrue(resolution.intent().targets().find("style.css").isEmpty());
        assertEquals(
                TargetRole.FORBIDDEN,
                resolution.intent().targets().find("secrets.env")
                        .orElseThrow().role());
    }

    @Test
    void historyAndRuntimeProjectionsCannotReopenAQuarantinedTurn() {
        String request = "Repair it. " + "a.txt ".repeat(
                CoordinatedMutationTailParser
                        .MAX_INDEXED_TARGET_OCCURRENCES + 1);
        TaskResolution current =
                TaskContractResolver.resolveFromUserRequest(request);
        assertTrue(current.sequentialMutationPlan().targetIndexLimitExceeded());

        TaskContract positive = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("prior.txt"),
                Set.of(),
                Set.of(),
                current.intent().originalUserRequest(),
                "attempted-authority-reopen");
        for (TargetSource source : List.of(
                TargetSource.MESSAGE_HISTORY,
                TargetSource.REPAIR_CONTEXT,
                TargetSource.WORKSPACE_EVIDENCE,
                TargetSource.RUNTIME_DEFAULT)) {
            TaskResolution projected = current.projectOnto(
                    positive,
                    TaskIntentProjector.project(
                            current.intent(),
                            positive,
                            source,
                            "attempted-authority-reopen"));
            assertFalse(projected.hasAuthorizedContentMutation(), source.name());
            assertTrue(projected.blocksUnscopedMutation(), source.name());
            assertFalse(projected.contract().expectedTargets()
                    .contains("prior.txt"), source.name());
            assertTrue(projected.sequentialMutationPlan()
                    .targetIndexLimitExceeded(), source.name());
        }

        TaskContract differentRequest = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("different.txt"),
                Set.of(),
                Set.of(),
                "Edit different.txt.",
                "different-canonical-request");
        TaskResolution differentProjection = current.projectOnto(
                differentRequest,
                TaskIntentProjector.project(
                        current.intent(),
                        differentRequest,
                        TargetSource.RUNTIME_DEFAULT,
                        "different-canonical-request"));
        assertEquals(current, differentProjection);
        assertEquals(current.intent().originalUserRequest(),
                differentProjection.intent().originalUserRequest());
        assertFalse(differentProjection.contract().expectedTargets()
                .contains("different.txt"));

        TaskResolution inherited = TaskContractResolver.resolveFromMessages(
                List.of(
                        ChatMessage.user("Edit prior.txt."),
                        ChatMessage.assistant(
                                "Task incomplete; no file changes were applied."),
                        ChatMessage.user(request)));
        assertFalse(inherited.hasAuthorizedContentMutation());
        assertTrue(inherited.blocksUnscopedMutation());
        assertFalse(inherited.contract().expectedTargets()
                .contains("prior.txt"));
        assertTrue(inherited.sequentialMutationPlan()
                .targetIndexLimitExceeded());

        TaskResolution replay = TaskResolution.confirmedReplay(current);
        assertFalse(replay.hasAuthorizedContentMutation());
        assertTrue(replay.blocksUnscopedMutation());

        TaskContract wrongReason = new TaskContract(
                current.contract().type(),
                current.contract().mutationRequested(),
                false,
                current.contract().verificationRequired(),
                Set.of(),
                current.contract().sourceEvidenceTargets(),
                current.contract().forbiddenTargets(),
                current.intent().originalUserRequest(),
                "wrong-target-limit-reason");
        TaskIntent wrongReasonIntent = TaskIntentProjector.project(
                current.intent(),
                wrongReason,
                TargetSource.RUNTIME_DEFAULT,
                "wrong-target-limit-reason");
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskResolution(
                        wrongReason,
                        wrongReasonIntent,
                        current.mutationTailPlan(),
                        current.sequentialMutationPlan()));

        TaskContract missingEvidence = new TaskContract(
                TaskType.UNKNOWN,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                Set.of(),
                "No mutation request.",
                "sequential-target-occurrence-limit-exceeded");
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskResolution.contractOnly(missingEvidence));
    }

    private static Fixture fixture(int clauses) {
        StringBuilder request = new StringBuilder();
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (int index = 0; index < clauses; index++) {
            String target = "resolver-" + index + ".txt";
            targets.add(target);
            if (index > 0) request.append(' ');
            request.append(index == 0 ? "Edit " : "Update ")
                    .append(target)
                    .append('.');
        }
        String canonical = request.toString();
        return new Fixture(
                canonical,
                new TaskContract(
                        TaskType.FILE_EDIT,
                        true,
                        true,
                        true,
                        Set.copyOf(targets),
                        Set.of(),
                        Set.of(),
                        canonical,
                        "resolver-scaling-fixture"));
    }

    private record Fixture(String request, TaskContract contract) {}
}
