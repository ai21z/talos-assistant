package dev.talos.runtime.policy;

import dev.talos.core.Config;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;

import java.nio.file.Path;
import java.util.Locale;

/** Deterministic derivation for current-turn evidence obligations. */
public final class EvidenceObligationPolicy {
    private static final Config DEFAULT_CAPABILITY_CONFIG = new Config(null);

    private EvidenceObligationPolicy() {}

    public static EvidenceObligation derive(TaskContract contract, ExecutionPhase phase, Path workspace) {
        return derive(contract, phase, workspace, DEFAULT_CAPABILITY_CONFIG);
    }

    public static EvidenceObligation derive(
            TaskContract contract,
            ExecutionPhase phase,
            Path workspace,
            Config cfg
    ) {
        if (contract == null) return EvidenceObligation.NONE;
        TaskType type = contract.type() == null ? TaskType.UNKNOWN : contract.type();
        if (type == TaskType.UNKNOWN || type == TaskType.SMALL_TALK) {
            return EvidenceObligation.NONE;
        }
        if (type == TaskType.DIRECTORY_LISTING) {
            return EvidenceObligation.LIST_DIRECTORY_ONLY;
        }
        if (type == TaskType.VERIFY_ONLY) {
            return EvidenceObligation.VERIFY_FROM_TRACE_OR_EVIDENCE;
        }
        if (hasUnsupportedDocumentTarget(contract, cfg)) {
            return EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED;
        }
        if (hasSourceEvidenceTargets(contract) && hasProtectedExpectedTarget(contract, workspace)) {
            return EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED;
        }
        if (!contract.mutationAllowed() && hasProtectedExpectedTarget(contract, workspace)) {
            return EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED;
        }
        if (hasStaticWebDiagnosisObligation(contract, type)) {
            return EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED;
        }
        if (contract.mutationAllowed() && hasSourceEvidenceTargets(contract)) {
            return EvidenceObligation.READ_TARGET_REQUIRED;
        }
        if (!contract.mutationAllowed() && !contract.expectedTargets().isEmpty()) {
            return EvidenceObligation.READ_TARGET_REQUIRED;
        }
        if (type == TaskType.WORKSPACE_EXPLAIN || type == TaskType.DIAGNOSE_ONLY) {
            return EvidenceObligation.WORKSPACE_INSPECTION_REQUIRED;
        }
        return EvidenceObligation.NONE;
    }

    public static EvidenceObligation parse(String value) {
        if (value == null || value.isBlank()) return EvidenceObligation.NONE;
        try {
            return EvidenceObligation.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return EvidenceObligation.NONE;
        }
    }

    private static boolean hasUnsupportedDocumentTarget(TaskContract contract, Config cfg) {
        for (String target : evidenceTargets(contract)) {
            if (requiresUnsupportedCapabilityCheck(Path.of(target), cfg)) {
                return true;
            }
        }
        return false;
    }

    static boolean requiresUnsupportedCapabilityCheck(Path target) {
        return requiresUnsupportedCapabilityCheck(target, DEFAULT_CAPABILITY_CONFIG);
    }

    static boolean requiresUnsupportedCapabilityCheck(Path target, Config cfg) {
        if (target == null) return false;
        Config safeCfg = cfg == null ? DEFAULT_CAPABILITY_CONFIG : cfg;
        return FileCapabilityPolicy.describe(target, safeCfg)
                .map(info -> !info.enabled())
                .orElse(false);
    }

    private static boolean hasProtectedExpectedTarget(TaskContract contract, Path workspace) {
        for (String target : evidenceTargets(contract)) {
            if (ProtectedPathPolicy.classify(workspace, target).protectedPath()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSourceEvidenceTargets(TaskContract contract) {
        return contract != null && !contract.sourceEvidenceTargets().isEmpty();
    }

    private static Iterable<String> evidenceTargets(TaskContract contract) {
        if (contract == null) return java.util.Set.of();
        if (!contract.sourceEvidenceTargets().isEmpty()) return contract.sourceEvidenceTargets();
        return contract.expectedTargets();
    }

    private static boolean hasStaticWebDiagnosisObligation(TaskContract contract, TaskType type) {
        if (type != TaskType.DIAGNOSE_ONLY) return false;
        for (String target : contract.expectedTargets()) {
            if (isStaticWebTarget(target)) return true;
        }
        String lower = contract.originalUserRequest().toLowerCase(Locale.ROOT);
        return lower.contains("website")
                || lower.contains("web page")
                || lower.contains("webpage")
                || lower.contains("static page")
                || lower.contains("static web")
                || lower.contains("html")
                || lower.contains("css")
                || lower.contains("javascript")
                || lower.contains("script")
                || lower.contains("selector")
                || lower.contains("button");
    }

    private static boolean isStaticWebTarget(String target) {
        if (target == null || target.isBlank()) return false;
        String lower = target.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.endsWith(".html")
                || lower.endsWith(".htm")
                || lower.endsWith(".css")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx");
    }
}
