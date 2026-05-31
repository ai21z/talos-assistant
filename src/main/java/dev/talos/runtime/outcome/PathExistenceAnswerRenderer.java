package dev.talos.runtime.outcome;

import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.turn.CurrentTurnPlan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Renders deterministic file-existence facts once path-existence evidence is satisfied. */
public final class PathExistenceAnswerRenderer {
    private static final String PREFIX = "[Path existence verified]";

    private PathExistenceAnswerRenderer() {}

    public static String prependVerifiedStatusIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            EvidenceObligation obligation,
            EvidenceObligationVerifier.Result evidenceResult,
            Path workspace
    ) {
        String current = answer == null ? "" : answer;
        if (current.startsWith(PREFIX)) return current;
        if (obligation != EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED) return current;
        if (evidenceResult == null || evidenceResult.status() != EvidenceObligationVerifier.Status.SATISFIED) {
            return current;
        }
        if (workspace == null) return current;

        List<String> targets = sortedTargets(plan == null ? null : plan.taskContract());
        if (targets.isEmpty()) return current;

        Path root;
        try {
            root = workspace.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return current;
        }

        List<String> lines = new ArrayList<>();
        for (String target : targets) {
            String status = status(root, target);
            if (status.isBlank()) continue;
            lines.add(target + ": " + status);
        }
        if (lines.isEmpty()) return current;

        String summary = PREFIX + "\n- " + String.join("\n- ", lines);
        return current.isBlank() ? summary : summary + "\n\n" + current;
    }

    private static List<String> sortedTargets(TaskContract contract) {
        if (contract == null) return List.of();
        Set<String> targets = contract.sourceEvidenceTargets().isEmpty()
                ? contract.expectedTargets()
                : contract.sourceEvidenceTargets();
        if (targets == null || targets.isEmpty()) return List.of();
        return targets.stream()
                .map(ToolCallSupport::normalizePath)
                .map(String::strip)
                .filter(target -> !target.isBlank())
                .distinct()
                .sorted(Comparator.comparing((String target) -> target.toLowerCase(Locale.ROOT))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static String status(Path root, String target) {
        Path resolved = resolve(root, target);
        if (resolved == null) return "outside workspace";
        return Files.exists(resolved) ? "exists" : "not found";
    }

    private static Path resolve(Path root, String target) {
        if (root == null || target == null || target.isBlank()) return null;
        try {
            Path candidate = Path.of(target);
            Path resolved = candidate.isAbsolute() ? candidate : root.resolve(candidate);
            resolved = resolved.toAbsolutePath().normalize();
            return resolved.startsWith(root) ? resolved : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
