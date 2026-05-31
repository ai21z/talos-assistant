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
        return new TaskContract(
                intent.type(),
                intent.mutationRequested(),
                intent.mutationAllowed(),
                intent.verificationRequired(),
                pathsWithRoles(targets, TargetRole.MUST_MUTATE, TargetRole.OUTPUT_DESTINATION),
                pathsWithRoles(targets, TargetRole.SOURCE_EVIDENCE, TargetRole.MUST_READ),
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
