package dev.talos.runtime;

import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.tools.ToolCall;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-local carrier for the resolved current-turn task contract.
 *
 * <p>The executor resolves contracts from full message history. Tool execution
 * must use the same resolved contract, not a second latest-message-only
 * classification, so repair follow-ups and other history-aware contracts remain
 * coherent through the approval gateway.
 */
public final class TurnTaskContractCapture {

    private static final ThreadLocal<CapturedResolution> HOLDER =
            new ThreadLocal<>();

    private TurnTaskContractCapture() {}

    public static void set(TaskContract contract) {
        if (contract == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(new CapturedResolution(
                    TaskResolution.contractOnly(contract),
                    null));
        }
    }

    public static void set(TaskResolution resolution) {
        if (resolution == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(new CapturedResolution(resolution, null));
        }
    }

    public static void set(
            TaskResolution resolution,
            List<ChatMessage> messages
    ) {
        set(resolution, messages, ModelIdentitySnapshot.empty());
    }

    public static void set(TaskResolution resolution, List<ChatMessage> messages,
                           ModelIdentitySnapshot modelIdentity) {
        ChatMessage boundary = resolution == null ? null : currentUserBoundary(resolution.contract(), messages);
        String request = boundary == null ? "" : boundary.content();
        boolean presentation = resolution != null && Objects.equals(request, resolution.contract().originalUserRequest())
                && dev.talos.runtime.policy.RuntimeModelIdentityAnswerPolicy.requestsIdentityComponent(request)
                && !dev.talos.runtime.policy.RuntimeModelIdentityAnswerPolicy.isIdentityOnlyRequest(request);
        set(resolution, messages, modelIdentity, presentation);
    }

    public static void set(TaskResolution resolution, List<ChatMessage> messages,
                           ModelIdentitySnapshot modelIdentity, boolean runtimeIdentityPresentation) {
        if (resolution == null) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(new CapturedResolution(
                resolution,
                currentUserBoundary(resolution.contract(), messages), modelIdentity, runtimeIdentityPresentation,
                latestProviderTaskBoundary(messages)));
    }

    public static boolean runtimeIdentityPresentation() {
        CapturedResolution captured = HOLDER.get();
        return captured != null && captured.runtimeIdentityPresentation;
    }

    public static String providerTaskRequest() {
        CapturedResolution captured = HOLDER.get();
        return captured == null || captured.providerTaskBoundary == null ? "" : captured.providerTaskBoundary.content();
    }

    public static int providerTaskBoundaryIndex(List<ChatMessage> messages) {
        CapturedResolution captured = HOLDER.get();
        if (captured == null || captured.providerTaskBoundary == null || messages == null) return -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == captured.providerTaskBoundary) return i;
        }
        return -1;
    }

    public static ModelIdentitySnapshot modelIdentity() {
        CapturedResolution captured = HOLDER.get();
        return captured == null ? ModelIdentitySnapshot.empty() : captured.modelIdentity;
    }

    public static TaskContract get() {
        CapturedResolution captured = HOLDER.get();
        return captured == null ? null : captured.resolution().contract();
    }

    public static TaskIntent getIntent() {
        CapturedResolution captured = HOLDER.get();
        return captured == null ? null : captured.resolution().intent();
    }

    public static TaskResolution getResolution() {
        CapturedResolution captured = HOLDER.get();
        return captured == null ? null : captured.resolution();
    }

    /** Carries the already compiled result interpretation into acquisition without re-parsing. */
    public static void setRequestedResults(dev.talos.runtime.intent.RequestedResultInventory inventory) {
        CapturedResolution captured = HOLDER.get();
        if (captured == null || inventory == null || !Objects.equals(
                captured.resolution().contract().originalUserRequest(), inventory.originalRequest()))
            throw new IllegalArgumentException("Requested results do not bind the captured current request");
        if (captured.requestedResults != null && !captured.requestedResults.equals(inventory))
            throw new IllegalArgumentException("Current requested results cannot be replaced");
        captured.requestedResults = inventory;
    }

    public static dev.talos.runtime.intent.RequestedResultInventory requestedResults() {
        CapturedResolution captured = HOLDER.get();
        return captured == null ? null : captured.requestedResults;
    }

    public static TaskContract currentOrResolve(
            List<ChatMessage> messages
    ) {
        CapturedResolution captured = HOLDER.get();
        return captured == null
                ? TaskContractResolver.fromMessages(messages)
                : captured.resolution().contract();
    }

    /**
     * Returns the current user's canonical request without allowing later
     * synthetic retry messages to replace it.
     *
     * <p>The resolver fallback exists only for legacy/direct callers that have
     * not established a turn capture yet.
     */
    public static String originalUserRequestOrResolve(
            List<ChatMessage> messages
    ) {
        TaskContract contract = currentOrResolve(messages);
        return contract == null || contract.originalUserRequest() == null
                ? ""
                : contract.originalUserRequest();
    }

    public static boolean blocksWorkspaceOperations() {
        CapturedResolution captured = HOLDER.get();
        return captured != null
                && captured.resolution().blocksWorkspaceOperations();
    }

    public static WorkspaceOperationAuthorityPlan workspaceOperationPlan() {
        CapturedResolution captured = HOLDER.get();
        return captured == null
                ? WorkspaceOperationAuthorityPlan.none("")
                : captured.resolution().workspaceOperationPlan();
    }

    public static WorkspaceAuthorization authorizeWorkspaceOperation(
            ToolCall call
    ) {
        if (call == null
                || !WorkspaceOperationPlanner.isWorkspaceOperationTool(
                        call.toolName())) {
            return WorkspaceAuthorization.NOT_WORKSPACE_OPERATION;
        }
        CapturedResolution captured = HOLDER.get();
        if (captured == null) {
            return WorkspaceAuthorization.NO_TYPED_AUTHORITY;
        }
        WorkspaceOperationAuthorityPlan plan =
                captured.resolution().workspaceOperationPlan();
        if (plan.authorizedOperations().isEmpty()) {
            return WorkspaceAuthorization.NO_TYPED_AUTHORITY;
        }
        String operationId = plan.authorizedOperations().getFirst().operationId();
        if (captured.consumedWorkspaceOperationIds.contains(operationId)) {
            return WorkspaceAuthorization.ALREADY_CONSUMED;
        }
        if (captured.attemptedWorkspaceOperationIds.contains(operationId)) {
            return WorkspaceAuthorization.ALREADY_ATTEMPTED;
        }
        if (!captured.successfulContentMutationPaths.containsAll(
                captured.resolution()
                        .contentMutationPredecessorPaths())) {
            return WorkspaceAuthorization.PREDECESSOR_NOT_COMPLETE;
        }
        if (!plan.authorizes(call)) {
            return WorkspaceAuthorization.CALL_DOES_NOT_MATCH_PLAN;
        }
        captured.attemptedWorkspaceOperationIds.add(operationId);
        return WorkspaceAuthorization.AUTHORIZED;
    }

    public static Set<String> pendingContentMutationPredecessorPaths() {
        CapturedResolution captured = HOLDER.get();
        if (captured == null) return Set.of();
        LinkedHashSet<String> pending = new LinkedHashSet<>(
                captured.resolution()
                        .contentMutationPredecessorPaths());
        pending.removeAll(captured.successfulContentMutationPaths);
        return Set.copyOf(pending);
    }

    public static boolean workspaceOperationObligationPending() {
        CapturedResolution captured = HOLDER.get();
        if (captured == null) return false;
        WorkspaceOperationAuthorityPlan plan =
                captured.resolution().workspaceOperationPlan();
        if (plan.authorizedOperations().isEmpty()) return false;
        return !captured.consumedWorkspaceOperationIds.contains(
                plan.authorizedOperations().getFirst().operationId());
    }

    public static boolean typedMutationObligationsPending() {
        return !pendingContentMutationPredecessorPaths().isEmpty()
                || workspaceOperationObligationPending();
    }

    /** Records progress only after the deterministic tool returned success. */
    public static void recordSuccessfulToolCall(ToolCall call) {
        CapturedResolution captured = HOLDER.get();
        if (captured == null || call == null) return;
        if (WorkspaceOperationPlanner.isWorkspaceOperationTool(call.toolName())) {
            WorkspaceOperationAuthorityPlan plan =
                    captured.resolution().workspaceOperationPlan();
            if (plan.authorizes(call)
                    && !plan.authorizedOperations().isEmpty()) {
                captured.consumedWorkspaceOperationIds.add(
                        plan.authorizedOperations().getFirst().operationId());
            }
            return;
        }
        if (!"talos.write_file".equals(call.toolName())
                && !"talos.edit_file".equals(call.toolName())) {
            return;
        }
        String path = ToolCallSupport.resolvePathHint(call);
        if (path == null || path.isBlank()) return;
        captured.successfulContentMutationPaths.add(
                normalizePath(path));
    }

    public enum WorkspaceAuthorization {
        NOT_WORKSPACE_OPERATION,
        AUTHORIZED,
        NO_TYPED_AUTHORITY,
        CALL_DOES_NOT_MATCH_PLAN,
        PREDECESSOR_NOT_COMPLETE,
        ALREADY_CONSUMED,
        ALREADY_ATTEMPTED
    }

    public static int currentUserBoundaryIndex(List<ChatMessage> messages) {
        CapturedResolution captured = HOLDER.get();
        ChatMessage boundary =
                captured == null ? null : captured.currentUserBoundary();
        if (boundary == null || messages == null || messages.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == boundary) return i;
        }
        return -1;
    }

    public static void clear() {
        HOLDER.remove();
    }

    private static ChatMessage currentUserBoundary(
            TaskContract contract,
            List<ChatMessage> messages
    ) {
        if (contract == null || messages == null || messages.isEmpty()) {
            return null;
        }
        String expected = currentUserRequest(contract);
        if (expected.isBlank()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) continue;
            String content = message.content();
            if (ToolCallSupport.isSyntheticToolResultContent(content)) continue;
            return Objects.equals(content, expected) ? message : null;
        }
        return null;
    }

    private static String currentUserRequest(TaskContract contract) {
        String canonical = Objects.toString(
                contract.originalUserRequest(),
                "");
        String marker = switch (contract.classificationReason()) {
            case "repair-follow-up-inherits-previous-mutation-contract",
                    "correction-follow-up-inherits-previous-mutation-contract" ->
                    "\n\nRepair follow-up: ";
            case "confirmation-follow-up-inherits-assistant-mutation-plan" ->
                    "\n\nConfirmation follow-up: ";
            default -> null;
        };
        if (marker == null) return canonical;
        int markerStart = canonical.lastIndexOf(marker);
        if (markerStart < 0) return "";
        return canonical.substring(markerStart + marker.length()).strip();
    }

    private static ChatMessage latestProviderTaskBoundary(List<ChatMessage> messages) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) continue;
            if (ToolCallSupport.isSyntheticToolResultContent(message.content())) continue;
            return !message.hasNativeToolCalls() && message.toolCallId() == null
                    && message.content() != null ? message : null;
        }
        return null;
    }

    private static String normalizePath(String path) {
        String normalized = Objects.toString(path, "").strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static final class CapturedResolution {
        private final TaskResolution resolution;
        private dev.talos.runtime.intent.RequestedResultInventory requestedResults;
        private final ChatMessage currentUserBoundary;
        private final ModelIdentitySnapshot modelIdentity;
        private final boolean runtimeIdentityPresentation;
        private final ChatMessage providerTaskBoundary;
        private final Set<String> successfulContentMutationPaths =
                new LinkedHashSet<>();
        private final Set<String> consumedWorkspaceOperationIds =
                new LinkedHashSet<>();
        private final Set<String> attemptedWorkspaceOperationIds =
                new LinkedHashSet<>();

        private CapturedResolution(
                TaskResolution resolution,
                ChatMessage currentUserBoundary
        ) {
            this(resolution, currentUserBoundary, ModelIdentitySnapshot.empty());
        }

        private CapturedResolution(TaskResolution resolution, ChatMessage currentUserBoundary,
                                   ModelIdentitySnapshot modelIdentity) {
            this(resolution, currentUserBoundary, modelIdentity, false);
        }

        private CapturedResolution(TaskResolution resolution, ChatMessage currentUserBoundary,
                                   ModelIdentitySnapshot modelIdentity, boolean runtimeIdentityPresentation) {
            this(resolution, currentUserBoundary, modelIdentity, runtimeIdentityPresentation, null);
        }

        private CapturedResolution(TaskResolution resolution, ChatMessage currentUserBoundary,
                                   ModelIdentitySnapshot modelIdentity, boolean runtimeIdentityPresentation,
                                   ChatMessage providerTaskBoundary) {
            if (resolution == null) {
                throw new IllegalArgumentException(
                        "captured resolution is required");
            }
            this.resolution = resolution;
            this.currentUserBoundary = currentUserBoundary;
            this.modelIdentity = modelIdentity == null ? ModelIdentitySnapshot.empty() : modelIdentity;
            this.runtimeIdentityPresentation = runtimeIdentityPresentation;
            this.providerTaskBoundary = providerTaskBoundary;
        }

        private TaskResolution resolution() {
            return resolution;
        }

        private ChatMessage currentUserBoundary() {
            return currentUserBoundary;
        }
    }
}
