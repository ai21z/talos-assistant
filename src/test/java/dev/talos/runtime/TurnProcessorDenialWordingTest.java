package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.tools.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the phrasing of the "user denied approval" error returned by
 * {@link TurnProcessor#executeTool}.
 *
 * <p><b>Why this matters:</b> in a real transcript (Apr 2026), the earlier
 * message {@code "Operation denied by user: talos.edit_file"} caused
 * qwen2.5-coder to respond with prose like
 * <em>"please ensure you have the necessary permissions"</em>. The word
 * <em>denied</em> in training data is overwhelmingly associated with auth
 * / ACL failures, not user intent. Reshaping the message so it leads with
 * <em>"User did not approve …"</em> and mentions workspace control kills
 * the hallucination with a one-line phrasing change. These tests lock in
 * the new wording so a future edit cannot silently resurrect the old
 * anchor.
 */
class TurnProcessorDenialWordingTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    /** A deny-all gate so executeTool returns the denial ToolResult. */
    private static final ApprovalGate DENY = (desc, detail) -> false;

    @Test
    void deniedMessageLeadsWithUserIntentPhrasing() {
        var tp = makeTp();
        ToolResult result = tp.executeTool(
                new dev.talos.runtime.Session(WS, new Config()),
                new ToolCall("talos.write_file", Map.of("path", "a.txt", "content", "x")),
                Context.builder(new Config()).build());

        assertFalse(result.success(), "Deny gate must cause failure");
        assertEquals(ToolError.DENIED, result.error().code());

        String msg = result.error().message();
        assertNotNull(msg);
        assertTrue(msg.startsWith("User did not approve"),
                "Message must lead with user-intent phrasing; was: " + msg);
        assertTrue(msg.contains("talos.write_file"),
                "Message must reference the specific tool; was: " + msg);
    }

    @Test
    void deniedMessageAvoidsAuthAnchoringWord() {
        var tp = makeTp();
        ToolResult result = tp.executeTool(
                new dev.talos.runtime.Session(WS, new Config()),
                new ToolCall("talos.edit_file",
                        Map.of("path", "a.txt", "old_string", "x", "new_string", "y")),
                Context.builder(new Config()).build());

        String msg = result.error().message();
        // "denied" was the specific anchor that triggered the
        // "permissions" hallucination; it must not appear in the message.
        assertFalse(msg.toLowerCase().contains("denied"),
                "Message must not contain the word 'denied' (auth anchor); was: " + msg);
        assertFalse(msg.toLowerCase().contains("permission"),
                "Message must not contain 'permission' (cascading anchor); was: " + msg);
    }

    @Test
    void deniedMessageOffersRecoveryPath() {
        var tp = makeTp();
        ToolResult result = tp.executeTool(
                new dev.talos.runtime.Session(WS, new Config()),
                new ToolCall("talos.write_file", Map.of("path", "a.txt", "content", "x")),
                Context.builder(new Config()).build());

        String msg = result.error().message();
        // The reshape tells the model what to do next — either ask the
        // user, or pick a different action. Either phrase is acceptable;
        // the invariant is that there's a recovery signal.
        assertTrue(msg.contains("ask") || msg.contains("different action"),
                "Message must offer a recovery path; was: " + msg);
    }

    private static TurnProcessor makeTp() {
        ToolRegistry registry = new ToolRegistry();
        // Real write/edit tools so riskLevel() triggers the approval gate.
        registry.register(new dev.talos.tools.impl.FileWriteTool());
        registry.register(new dev.talos.tools.impl.FileEditTool());
        return new TurnProcessor(ModeController.defaultController(), DENY, registry);
    }
}


