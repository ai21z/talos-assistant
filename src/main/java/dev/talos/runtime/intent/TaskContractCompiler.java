package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

public final class TaskContractCompiler {

    private TaskContractCompiler() {}

    public static TaskContract compile(TaskIntent intent) {
        if (intent == null) {
            return TaskContract.unknown("");
        }
        ArtifactTargetSet targets = intent.targets();
        if (!intent.mutationAllowed()
                && !targets.pathsByRole(
                        TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE).isEmpty()) {
            throw new IllegalArgumentException(
                    "Read-only intent cannot carry required post-read mutation authority");
        }
        Set<String> expectedTargets = intent.mutationAllowed()
                ? pathsWithRoles(
                        targets,
                        TargetRole.MUST_MUTATE,
                        TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE,
                        TargetRole.OUTPUT_DESTINATION)
                : pathsWithRoles(
                        targets,
                        TargetRole.MUST_MUTATE,
                        TargetRole.OUTPUT_DESTINATION,
                        TargetRole.MUST_READ,
                        TargetRole.VERIFY_ONLY);
        return new TaskContract(
                intent.type(),
                intent.mutationRequested(),
                intent.mutationAllowed(),
                intent.verificationRequired(),
                expectedTargets,
                pathsWithRoles(
                        targets,
                        TargetRole.SOURCE_EVIDENCE,
                        TargetRole.MUST_READ,
                        TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE),
                pathsWithRoles(targets, TargetRole.FORBIDDEN),
                intent.originalUserRequest(),
                intent.classificationReason());
    }

    private static Set<String> pathsWithRoles(ArtifactTargetSet targets, TargetRole first, TargetRole... rest) {
        if (targets == null || targets.targets().isEmpty()) return Set.of();
        EnumSet<TargetRole> roles = EnumSet.of(first, rest);
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (TargetRef target : targets.targets()) {
            if (roles.contains(target.role())) {
                paths.add(target.path());
            }
        }
        return Set.copyOf(paths);
    }
}
