package dev.talos.runtime;

import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.spi.types.ChatMessage;

import java.util.List;
import java.util.Objects;

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
        if (resolution == null) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(new CapturedResolution(
                resolution,
                currentUserBoundary(resolution.contract(), messages)));
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

    private record CapturedResolution(
            TaskResolution resolution,
            ChatMessage currentUserBoundary
    ) {
        private CapturedResolution {
            if (resolution == null) {
                throw new IllegalArgumentException(
                        "captured resolution is required");
            }
        }
    }
}
