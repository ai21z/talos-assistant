package dev.talos.runtime.context;

import dev.talos.runtime.trace.PromptAuditRedactor;

import java.util.List;
import java.util.Locale;

public record ArtifactGoal(
        ArtifactKind artifactKind,
        ActiveTaskContext.Operation operation,
        List<String> targets,
        String verifierProfile,
        Source source) {

    public ArtifactGoal {
        artifactKind = artifactKind == null ? ArtifactKind.UNKNOWN : artifactKind;
        operation = operation == null ? ActiveTaskContext.Operation.NONE : operation;
        targets = targets == null ? List.of() : List.copyOf(targets);
        verifierProfile = verifierProfile == null ? "" : verifierProfile.strip();
        source = source == null ? Source.NONE : source;
    }

    public enum ArtifactKind { README, MARKDOWN, STATIC_WEB, GENERIC_FILE, UNKNOWN }

    public enum Source { CURRENT_REQUEST, ACTIVE_CONTEXT, TRACE_OUTCOME, NONE }

    public static ArtifactGoal none() {
        return new ArtifactGoal(
                ArtifactKind.UNKNOWN,
                ActiveTaskContext.Operation.NONE,
                List.of(),
                "",
                Source.NONE);
    }

    public static ArtifactGoal fromActiveContext(ActiveTaskContext context) {
        if (context == null || !context.hasTargets() || context.state() != ActiveTaskContext.State.ACTIVE) {
            return none();
        }
        return new ArtifactGoal(
                inferKind(context.targets()),
                context.operation(),
                context.targets(),
                "",
                Source.ACTIVE_CONTEXT);
    }

    public String renderForPlan() {
        if (source == Source.NONE) return ActiveTaskContext.NONE_OR_NOT_DERIVED;
        String rendered = "artifactGoal{"
                + "kind=" + artifactKind
                + ", operation=" + operation
                + ", targets=" + targets
                + ", verifierProfile=" + verifierProfile
                + ", source=" + source
                + '}';
        return PromptAuditRedactor.preview(rendered, ActiveTaskContext.PROMPT_RENDER_CHAR_CAP);
    }

    private static ArtifactKind inferKind(List<String> targets) {
        String first = targets.getFirst().toLowerCase(Locale.ROOT);
        if (first.equals("readme.md") || first.endsWith("/readme.md") || first.endsWith("\\readme.md")) {
            return ArtifactKind.README;
        }
        if (first.endsWith(".html") || first.endsWith(".htm") || first.endsWith(".css") || first.endsWith(".js")) {
            return ArtifactKind.STATIC_WEB;
        }
        if (first.endsWith(".md")) {
            return ArtifactKind.MARKDOWN;
        }
        return ArtifactKind.GENERIC_FILE;
    }
}
