package dev.talos.runtime;

import dev.talos.runtime.intent.MutationPlanSelection;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnPolicyTraceScalingTest {

    @Test
    void sharedClauseEvidenceIsDigestedOncePerExactSpan() {
        StringBuilder request = new StringBuilder(
                "Edit anchor.css. Then update ");
        for (int index = 0; index < 128; index++) {
            if (index > 0) request.append(", ");
            request.append("shared-").append(index).append(".css");
        }
        request.append('.');

        var resolution = TaskContractResolver.resolveFromUserRequest(
                request.toString());
        TurnPolicyTrace.LexicalEvidenceBuild build =
                TurnPolicyTrace.lexicalEvidenceBuild(resolution);

        assertEquals(128, build.decisions().size());
        assertEquals(1, build.requestDigestCount());
        Set<String> exactSpans = new LinkedHashSet<>();
        build.decisions().forEach(row -> exactSpans.add(
                row.startOffset() + ":" + row.endOffset()));
        build.regions().forEach(row -> exactSpans.add(
                row.startOffset() + ":" + row.endOffset()));
        assertEquals(exactSpans.size(), build.sourceSpanDigestCount());
        assertTrue(
                build.sourceSpanDigestCount()
                        < build.decisions().size() + build.regions().size(),
                "shared source spans must not be hashed once per target");

    }

    @Test
    void policyAndLocalTraceRejectExpandedRoleEvidenceOneOverTheBound() {
        int half = (int) MutationPlanSelection
                .MAX_PROJECTED_ROLEFUL_SOURCE_CHARACTERS / 2;
        String atHalf = "x".repeat(half);
        String oneOverHalf = "x".repeat(half + 1);

        List<TurnPolicyTrace.RolefulTarget> exact = List.of(
                rolefulTarget("first.txt", atHalf),
                rolefulTarget("second.txt", atHalf));
        assertDoesNotThrow(() -> policy(exact));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(List.of(
                        rolefulTarget("first.txt", atHalf),
                        rolefulTarget("second.txt", oneOverHalf))));

        List<LocalTurnTrace.RolefulTargetSummary> localExact = List.of(
                localRolefulTarget("first.txt", atHalf),
                localRolefulTarget("second.txt", atHalf));
        assertDoesNotThrow(() -> new LocalTurnTrace.TaskContractSummary(
                "FILE_EDIT",
                false,
                true,
                true,
                List.of(),
                List.of(),
                "bounded-evidence",
                localExact));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalTurnTrace.TaskContractSummary(
                        "FILE_EDIT",
                        false,
                        true,
                        true,
                        List.of(),
                        List.of(),
                        "bounded-evidence",
                        List.of(
                                localRolefulTarget("first.txt", atHalf),
                                localRolefulTarget(
                                        "second.txt",
                                        oneOverHalf))));
    }

    @Test
    void policyAndLocalTraceEnforceTheSameRoleRowBoundary() {
        int limit = MutationPlanSelection.MAX_DURABLE_ROLEFUL_TARGETS;
        List<TurnPolicyTrace.RolefulTarget> policyAtLimit = IntStream
                .range(0, limit)
                .mapToObj(index -> rolefulTarget(
                        "policy-" + index + ".txt", ""))
                .toList();
        assertDoesNotThrow(() -> policy(policyAtLimit));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(IntStream.rangeClosed(0, limit)
                        .mapToObj(index -> rolefulTarget(
                                "policy-" + index + ".txt", ""))
                        .toList()));

        List<LocalTurnTrace.RolefulTargetSummary> localAtLimit = IntStream
                .range(0, limit)
                .mapToObj(index -> localRolefulTarget(
                        "trace-" + index + ".txt", ""))
                .toList();
        assertDoesNotThrow(() -> localPolicy(localAtLimit));
        assertThrows(
                IllegalArgumentException.class,
                () -> localPolicy(IntStream.rangeClosed(0, limit)
                        .mapToObj(index -> localRolefulTarget(
                                "trace-" + index + ".txt", ""))
                        .toList()));
    }

    private static TurnPolicyTrace.RolefulTarget rolefulTarget(
            String path,
            String sourceText
    ) {
        return new TurnPolicyTrace.RolefulTarget(
                path,
                TargetRole.MENTIONED_ONLY.name(),
                TargetSource.RUNTIME_DEFAULT.name(),
                "expanded-role-evidence-boundary",
                sourceText,
                1.0);
    }

    private static LocalTurnTrace.RolefulTargetSummary localRolefulTarget(
            String path,
            String sourceText
    ) {
        return new LocalTurnTrace.RolefulTargetSummary(
                path,
                TargetRole.MENTIONED_ONLY.name(),
                TargetSource.RUNTIME_DEFAULT.name(),
                "expanded-role-evidence-boundary",
                sourceText,
                1.0);
    }

    private static TurnPolicyTrace policy(
            List<TurnPolicyTrace.RolefulTarget> targets
    ) {
        return new TurnPolicyTrace(
                "FILE_EDIT",
                false,
                true,
                List.of(),
                List.of(),
                "APPLY",
                "APPLY",
                List.of(),
                List.of(),
                List.of(),
                "bounded-evidence",
                targets,
                List.of(),
                List.of(),
                true);
    }

    private static LocalTurnTrace.TaskContractSummary localPolicy(
            List<LocalTurnTrace.RolefulTargetSummary> targets
    ) {
        return new LocalTurnTrace.TaskContractSummary(
                "FILE_EDIT",
                false,
                true,
                true,
                List.of(),
                List.of(),
                "bounded-evidence",
                targets);
    }
}
