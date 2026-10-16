package dev.talos.runtime;

import dev.talos.core.util.Hash;
import dev.talos.runtime.intent.WorkStatusRequest;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** A settlement marker for deterministic status ownership, not verification proof. */
public record WorkStatusReceipt(boolean active, String requestHash, String answerHash,
                                WorkStatusRequest.Coverage coverage,
                                Set<WorkStatusRequest.Role> roles, boolean presentationComplete,
                                java.util.List<ResultDecision> resultDecisions) {
    public record ResultDecision(String idHash, String kind, String disposition, int start, int end) {}
    public WorkStatusReceipt {
        requestHash = finiteHash(requestHash);
        answerHash = finiteHash(answerHash);
        coverage = coverage == null ? WorkStatusRequest.Coverage.INVALID : coverage;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        resultDecisions = resultDecisions == null ? java.util.List.of() : java.util.List.copyOf(resultDecisions);
        presentationComplete = presentationComplete && active && !requestHash.isEmpty() && !answerHash.isEmpty()
                && coverage == WorkStatusRequest.Coverage.COVERED && !roles.isEmpty()
                && !roles.contains(WorkStatusRequest.Role.UNSUPPORTED);
    }
    public WorkStatusReceipt(boolean active, String requestHash, String answerHash,
                             WorkStatusRequest.Coverage coverage, Set<WorkStatusRequest.Role> roles,
                             boolean presentationComplete) {
        this(active, requestHash, answerHash, coverage, roles, presentationComplete, java.util.List.of());
    }
    public static WorkStatusReceipt captured(dev.talos.runtime.intent.RequestedResultInventory inventory, String answer) {
        var roles = java.util.EnumSet.noneOf(WorkStatusRequest.Role.class);
        for (var result : inventory.results()) roles.add(switch (result.kind()) {
            case RETAINED_SAVES -> WorkStatusRequest.Role.ACTIONS;
            case RETAINED_VERIFICATION -> WorkStatusRequest.Role.VERIFICATION;
            case RETAINED_UNCHECKED -> WorkStatusRequest.Role.UNCERTAINTY;
            default -> WorkStatusRequest.Role.UNSUPPORTED;
        });
        boolean partial = !inventory.unresolvedRegions().isEmpty() || roles.contains(WorkStatusRequest.Role.UNSUPPORTED)
                || inventory.results().stream().anyMatch(result -> result.disposition()
                    == dev.talos.runtime.intent.RequestedResultInventory.Disposition.UNSUPPORTED);
        if (partial) roles.add(WorkStatusRequest.Role.UNSUPPORTED);
        return new WorkStatusReceipt(true, digest(inventory.originalRequest()), digest(answer),
                partial ? WorkStatusRequest.Coverage.PARTIAL : WorkStatusRequest.Coverage.COVERED, roles, false,
                inventory.results().stream().map(result -> new ResultDecision(digest(result.id()), result.kind().name(),
                        result.disposition().name(), result.region().start(), result.region().end())).toList());
    }
    public static WorkStatusReceipt none() {
        return new WorkStatusReceipt(false, "", "", WorkStatusRequest.Coverage.INVALID, Set.of(), false);
    }
    public static WorkStatusReceipt assessed(WorkStatusRequest request, String answer, boolean complete) {
        return new WorkStatusReceipt(true, digest(request.originalRequest()), digest(answer),
                request.coverage(), request.roles(), complete);
    }
    public boolean matches(String request, String answer) {
        return active && requestHash.equals(digest(request)) && answerHash.equals(digest(answer));
    }
    public WorkStatusReceipt incomplete() {
        return new WorkStatusReceipt(active, requestHash, answerHash, coverage, roles, false, resultDecisions);
    }
    private static String digest(String text) {
        return Hash.sha256Hex((text == null ? "" : text.strip()).getBytes(StandardCharsets.UTF_8));
    }
    private static String finiteHash(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}") ? hash : "";
    }
}
