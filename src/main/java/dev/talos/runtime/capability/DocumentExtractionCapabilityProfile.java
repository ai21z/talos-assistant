package dev.talos.runtime.capability;

import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.runtime.task.TaskContract;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DocumentExtractionCapabilityProfile {
    public static final String ID = "document-extraction";

    private DocumentExtractionCapabilityProfile() {}

    public static CapabilityProfile select(TaskContract contract, Path workspace, Set<String> mutatedPaths) {
        return isApplicable(contract) ? CapabilityProfile.documentExtraction() : CapabilityProfile.none();
    }

    public static boolean isApplicable(TaskContract contract) {
        if (contract == null || contract.mutationRequested()) return false;
        if (documentTargets(contract).isEmpty()) return false;
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("extract")
                || lower.contains("read")
                || lower.contains("summariz")
                || lower.contains("summaris")
                || lower.contains("compare")
                || lower.contains("what does")
                || lower.contains("what is in")
                || lower.contains("tell me");
    }

    public static boolean isExactTextExtractionTask(TaskContract contract) {
        if (contract == null) return false;
        String lower = contract.originalUserRequest().toLowerCase(Locale.ROOT);
        if (lower.contains("summariz")
                || lower.contains("summaris")
                || lower.contains("compare")
                || lower.contains("analyz")
                || lower.contains("analys")
                || lower.contains("what does")
                || lower.contains("tell me")) {
            return false;
        }
        boolean textRequested = lower.contains("text")
                || lower.contains("contents")
                || lower.contains("content");
        return lower.contains("extract") && textRequested;
    }

    public static List<String> documentTargets(TaskContract contract) {
        if (contract == null || contract.expectedTargets().isEmpty()) return List.of();
        return contract.expectedTargets().stream()
                .filter(DocumentExtractionCapabilityProfile::isDocumentTarget)
                .sorted()
                .toList();
    }

    public static boolean isDocumentTarget(String target) {
        if (target == null || target.isBlank()) return false;
        try {
            return FileCapabilityPolicy.describe(Path.of(target.strip())).isPresent();
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
