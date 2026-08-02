package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1204V2GrammarRegressionTest {

    private static final String TAIL_PROHIBITION =
            "Edit index.html, without changing style.css. ";

    private static final Set<TargetRole> POSITIVE_MUTATION_ROLES = Set.of(
            TargetRole.MUST_MUTATE,
            TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE,
            TargetRole.OUTPUT_DESTINATION,
            TargetRole.MAY_MUTATE);

    @Test
    void laterNonImperativeContentClausesCannotEscapeAT1196Tail() {
        for (String laterClause : List.of(
                "Consider updating app.js.",
                "The report says update app.js.",
                "Maybe update app.js.",
                "Should we update app.js?",
                "Without updating app.js.")) {
            String request = TAIL_PROHIBITION + laterClause;
            TaskResolution resolution = resolveWithoutThrowing(request);

            assertTailProhibition(resolution, request);
            assertNoPositiveAuthority(resolution, "app.js", request);
        }
    }

    @Test
    void laterNonImperativeWorkspaceClausesCannotGainAuthority() {
        for (String laterClause : List.of(
                "Consider deleting cache.tmp.",
                "The report says delete cache.tmp.",
                "Maybe delete cache.tmp.",
                "Should we delete cache.tmp?",
                "Without deleting cache.tmp.")) {
            String request = TAIL_PROHIBITION + laterClause;
            TaskResolution resolution = resolveWithoutThrowing(request);

            assertTailProhibition(resolution, request);
            assertNoPositiveAuthority(resolution, "cache.tmp", request);
        }
    }

    @Test
    void laterRequiredContentKeepsEarlierDifferentTargetForbidden() {
        String request = TAIL_PROHIBITION + "Then edit app.js.";
        TaskResolution resolution = resolveWithoutThrowing(request);
        SequentialMutationClausePlan.ClauseDecision later =
                resolution.sequentialMutationPlan()
                        .effectiveDecision("app.js")
                        .orElseThrow(() -> new AssertionError(
                                request + " resolution=" + resolution));

        assertTailProhibition(resolution, request);
        assertEquals(
                SequentialMutationClausePlan.Authority.REQUIRED,
                later.authority(),
                request);
        assertEquals(TargetRole.MUST_MUTATE,
                resolution.intent().targets().find("app.js")
                        .orElseThrow()
                        .role(),
                request);
        assertTrue(resolution.contract().expectedTargets().contains("app.js"),
                request);
        assertExactSource(request, later);
    }

    @Test
    void laterSameTargetRequirementCannotOverrideEarlierProhibition() {
        String request = TAIL_PROHIBITION + "Then update style.css.";
        TaskResolution resolution = resolveWithoutThrowing(request);
        SequentialMutationClausePlan.ClauseDecision later =
                resolution.sequentialMutationPlan()
                        .effectiveDecision("style.css")
                        .orElseThrow(() -> new AssertionError(
                                request + " resolution=" + resolution));

        assertTailProhibition(resolution, request);
        assertEquals(
                SequentialMutationClausePlan.Authority.REQUIRED,
                later.authority(),
                request);
        assertFalse(
                resolution.contract().expectedTargets().contains("style.css"),
                request);
        assertExactSource(request, later);
    }

    @Test
    void laterWorkspaceOperationIsTypedBlockedWithoutContentAuthority() {
        String request = TAIL_PROHIBITION + "Then delete cache.tmp.";
        TaskResolution resolution = resolveWithoutThrowing(request);
        SequentialMutationClausePlan.ClauseDecision later =
                resolution.sequentialMutationPlan()
                        .effectiveDecision("cache.tmp")
                        .orElseThrow(() -> new AssertionError(
                                request + " resolution=" + resolution));

        assertTailProhibition(resolution, request);
        assertEquals(
                SequentialMutationClausePlan.ActionFamily.WORKSPACE_OPERATION,
                later.actionFamily(),
                request);
        assertEquals(
                SequentialMutationClausePlan.Authority
                        .WORKSPACE_OPERATION_BLOCKED,
                later.authority(),
                request);
        assertTrue(resolution.blocksWorkspaceOperations(), request);
        assertNoPositiveAuthority(resolution, "cache.tmp", request);
        assertExactSource(request, later);
    }

    @Test
    void sentenceBoundaryPreposedTargetCannotThrowOrMintAuthority() {
        String request = TAIL_PROHIBITION
                + "In app.js, update the banner.";
        TaskResolution resolution = resolveWithoutThrowing(request);

        assertTailProhibition(resolution, request);
        assertNoPositiveAuthority(resolution, "app.js", request);
        assertTrue(
                resolution.sequentialMutationPlan().decisions("app.js")
                        .stream()
                        .noneMatch(decision -> decision.authority()
                                == SequentialMutationClausePlan.Authority
                                        .REQUIRED),
                request);
    }

    @Test
    void providerControlCompositionCannotThrowOrMintAuthority() {
        for (String request : List.of(
                TAIL_PROHIBITION + "\u0000Update app.js.",
                TAIL_PROHIBITION + "Upda\u0000te app.js.",
                TAIL_PROHIBITION + "Update \u0000app.js.",
                TAIL_PROHIBITION + "\u0000Delete cache.tmp.",
                TAIL_PROHIBITION + "\u0000In app.js, update the banner.")) {
            TaskResolution resolution = resolveWithoutThrowing(request);
            String target = request.contains("cache.tmp")
                    ? "cache.tmp"
                    : "app.js";

            assertTailProhibition(resolution, request);
            assertNoPositiveAuthority(resolution, target, request);
            assertTrue(
                    resolution.sequentialMutationPlan().ownedRegions().stream()
                            .anyMatch(region -> region.sourceText().indexOf(
                                    '\u0000') >= 0
                                    && request.substring(
                                            region.startOffset(),
                                            region.endOffset())
                                            .equals(region.sourceText())),
                    request);
        }
    }

    private static TaskResolution resolveWithoutThrowing(String request) {
        return assertDoesNotThrow(
                () -> TaskContractResolver.resolveFromUserRequest(request),
                request);
    }

    private static void assertTailProhibition(
            TaskResolution resolution,
            String request
    ) {
        assertEquals(
                CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                resolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow(() -> new AssertionError(
                                request + " resolution=" + resolution))
                        .authority(),
                request);
        assertEquals(
                TargetRole.FORBIDDEN,
                resolution.intent().targets().find("style.css")
                        .orElseThrow()
                        .role(),
                request);
        assertTrue(
                resolution.contract().forbiddenTargets().contains("style.css"),
                request);
    }

    private static void assertNoPositiveAuthority(
            TaskResolution resolution,
            String path,
            String request
    ) {
        assertFalse(resolution.contract().expectedTargets().contains(path),
                request + " resolution=" + resolution);
        assertTrue(
                resolution.sequentialMutationPlan().decisions(path).stream()
                        .noneMatch(decision -> decision.authority()
                                == SequentialMutationClausePlan.Authority
                                        .REQUIRED),
                request + " resolution=" + resolution);
        assertTrue(
                resolution.intent().targets().find(path)
                        .map(target -> !POSITIVE_MUTATION_ROLES.contains(
                                target.role()))
                        .orElse(true),
                request + " resolution=" + resolution);
    }

    private static void assertExactSource(
            String request,
            SequentialMutationClausePlan.ClauseDecision decision
    ) {
        assertEquals(
                decision.sourceText(),
                request.substring(
                        decision.startOffset(),
                        decision.endOffset()),
                request);
    }
}
