package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Empty-argument edit failures no longer clear the successful-read caches
 * (the failure never touches disk), so the duplicate-read suppression must
 * not short-circuit the re-read that the empty-edit repair instruction
 * itself demands: the prior read may already be compacted out of provider
 * context, making "use the evidence you already have" unsatisfiable.
 */
class RedundantReadSuppressionGuardEmptyEditTest {

    @Test
    void aReReadDemandedByTheEmptyEditRepairIsNotSuppressed() {
        LoopState state = state();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "index.html"));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state, read, "index.html", ToolResult.ok("1 | <html>\n"));
        state.emptyEditArgumentFailuresByPath.put("index.html", 1);

        assertNull(RedundantReadSuppressionGuard.decision(read, state, false),
                "the repair instruction demands a re-read; suppressing it is self-defeating");
    }

    @Test
    void anUnrelatedDuplicateReadStaysSuppressed() {
        LoopState state = state();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "index.html"));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state, read, "index.html", ToolResult.ok("1 | <html>\n"));

        assertNotNull(RedundantReadSuppressionGuard.decision(read, state, false),
                "ordinary duplicate reads keep the existing suppression");
    }

    private static LoopState state() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user("Fix index.html."));
        return new LoopState(
                "",
                List.of(),
                messages,
                Path.of(".").toAbsolutePath().normalize(),
                null,
                null,
                10,
                0);
    }
}
