package dev.talos.runtime.toolcall;

import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only call identities of the current turn, across its loops. A nested loop of the same turn
 * sees the calls an outer loop already made. The entries are dropped when any loop of the turn
 * records a mutation or a failed edit, so a read that became necessary again is not suppressed.
 * The register says only that an identical call happened and what it returned. Whether that result
 * is still available to the model is judged against the dispatched messages by the caller.
 */
public final class TurnReadCallRegister {
    private record Entry(Object turn, String canonicalTool, Map<String, String> parameters, String body) {}

    private static final int MAX_ENTRIES = 256;
    private static final ThreadLocal<List<Entry>> ENTRIES = ThreadLocal.withInitial(ArrayList::new);

    private TurnReadCallRegister() {}

    public static void record(ToolCall call, String body) {
        // A turn is a trace. Outside one there is no turn to scope to, and nothing is kept.
        Object turn = LocalTurnTraceCapture.currentTurnToken();
        if (call == null || body == null || call.parameters() == null || turn == null) return;
        List<Entry> entries = ENTRIES.get();
        if (entries.size() >= MAX_ENTRIES) entries.remove(0);
        entries.add(new Entry(turn, ToolAliasPolicy.localCanonicalName(call.toolName()),
                Map.copyOf(call.parameters()), body));
    }

    /** The body an identical call of this turn returned, latest first, or null when none is known. */
    public static String latestBody(ToolCall call) {
        Object turn = LocalTurnTraceCapture.currentTurnToken();
        if (call == null || turn == null) return null;
        List<Entry> entries = ENTRIES.get();
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            // The live capture instance identifies the turn, so a reused trace id cannot join two turns.
            if (entry.turn() == turn
                    && ReadEvidenceLedger.sameCallIdentity(call, entry.canonicalTool(), entry.parameters())) {
                return entry.body();
            }
        }
        return null;
    }

    /** Earlier results stop being reusable, as after a mutation or a failed edit. */
    public static void invalidate() {
        ENTRIES.get().clear();
    }

    public static void clear() {
        ENTRIES.remove();
    }

}
