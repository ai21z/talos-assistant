package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;

public final class TaskIntentResolver {

    private TaskIntentResolver() {}

    public static TaskIntent fromLegacyContract(TaskContract contract) {
        if (contract == null) {
            return new TaskIntent(null, false, false, false, ArtifactTargetSet.empty(), "", "");
        }
        ArtifactTargetSet targets = ArtifactTargetSet.empty();
        for (String target : contract.expectedTargets()) {
            targets = targets.with(TargetRef.of(target, TargetRole.MUST_MUTATE));
        }
        for (String target : contract.sourceEvidenceTargets()) {
            targets = targets.with(TargetRef.of(target, TargetRole.SOURCE_EVIDENCE));
        }
        for (String target : contract.forbiddenTargets()) {
            targets = targets.with(TargetRef.of(target, TargetRole.FORBIDDEN));
        }
        return new TaskIntent(
                contract.type(),
                contract.mutationRequested(),
                contract.mutationAllowed(),
                contract.verificationRequired(),
                targets,
                contract.originalUserRequest(),
                contract.classificationReason());
    }
}
