package dev.talos.runtime.toolcall;

import dev.talos.core.util.Hash;
import dev.talos.tools.MutationPreimage;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolMutationEffects;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ToolMutationStateAccounting.retainedMutationPreimage, which ToolCallExecutionStage calls for every executed outcome,
 * keeps a producer preimage on every successful result, so the next change of a target observes the state that the
 * earlier change left (P19, D13 option A). It withholds it after a failed result with mutation effects on the same path
 * in the loop run, as P18 did. The results here are constructed. The stage itself is driven by the REPL entry tests in
 * ReplacementScopeReplEntryTest.
 */
class RetainedMutationPreimageTest {
    private static final String PRIOR = "[SERVER]\nport=8080\n[CLIENT]\nport=8080\n";

    @Test
    void everySuccessfulResultKeepsItsPreimage() {
        LoopState state = loopState();
        MutationPreimage first = preimage("config.ini", PRIOR);
        MutationPreimage second = preimage("config.ini", "[SERVER]\nport=9001\n[CLIENT]\nport=8080\n");
        MutationPreimage other = preimage("notes.md", "a\n");

        assertEquals(first, ToolMutationStateAccounting.retainedMutationPreimage(state, applied("config.ini", first)));
        assertEquals(second, ToolMutationStateAccounting.retainedMutationPreimage(state, applied("config.ini", second)));
        assertEquals(other, ToolMutationStateAccounting.retainedMutationPreimage(state, applied("notes.md", other)));
    }

    @Test
    void anEarlierFailedEffectOnTheTargetWithholdsTheLaterPreimage() {
        LoopState state = loopState();
        ToolResult failed = ToolResult.fail(ToolError.internal("Failed to write file"))
                .withMutationEffects(ToolMutationEffects.uncertain("config.ini"));

        assertSame(MutationPreimage.none(), ToolMutationStateAccounting.retainedMutationPreimage(state, failed));
        assertSame(MutationPreimage.none(), ToolMutationStateAccounting.retainedMutationPreimage(
                state, applied("config.ini", preimage("config.ini", PRIOR))));
        MutationPreimage other = preimage("notes.md", "a\n");
        assertEquals(other, ToolMutationStateAccounting.retainedMutationPreimage(state, applied("notes.md", other)));
    }

    @Test
    void aFailedResultWithoutEffectsWithholdsNothing() {
        LoopState state = loopState();
        ToolResult failed = ToolResult.fail(ToolError.internal("old_string was not found"));

        assertSame(MutationPreimage.none(), ToolMutationStateAccounting.retainedMutationPreimage(state, failed));
        MutationPreimage preimage = preimage("config.ini", PRIOR);
        assertEquals(preimage,
                ToolMutationStateAccounting.retainedMutationPreimage(state, applied("config.ini", preimage)));
    }

    @Test
    void anEffectOnAnotherPathAndANullResultChangeNothing() {
        LoopState state = loopState();
        assertSame(MutationPreimage.none(), ToolMutationStateAccounting.retainedMutationPreimage(state, null));
        assertSame(MutationPreimage.none(), ToolMutationStateAccounting.retainedMutationPreimage(null, null));
        ToolMutationStateAccounting.retainedMutationPreimage(state, ToolResult.ok("made")
                .withMutationEffects(ToolMutationEffects.applied("docs")));

        MutationPreimage preimage = preimage("config.ini", PRIOR);
        assertEquals(preimage,
                ToolMutationStateAccounting.retainedMutationPreimage(state, applied("config.ini", preimage)));
    }

    @Test
    void theExecutedOutcomeCarriesThePreimageWithoutPrintingIt() {
        String canary = "p18-canary-7f3a";
        MutationPreimage preimage = preimage("config.ini", PRIOR + "# " + canary + "\n");
        ToolResult result = applied("config.ini", preimage);

        var outcome = ToolOutcomeFactory.executed(
                new dev.talos.tools.ToolCall("talos.write_file", java.util.Map.of("path", "config.ini")),
                "config.ini", result, null, null, null, null, preimage);

        assertEquals(preimage, outcome.mutationPreimage());
        assertEquals(false, outcome.toString().contains(canary), outcome.toString());
        assertSame(MutationPreimage.none(), ToolOutcomeFactory.executed(
                new dev.talos.tools.ToolCall("talos.write_file", java.util.Map.of("path", "config.ini")),
                "config.ini", result, null, null, null).mutationPreimage());
    }

    private static ToolResult applied(String path, MutationPreimage preimage) {
        return ToolResult.ok("Updated " + path)
                .withMutationEffects(ToolMutationEffects.applied(path))
                .withMutationPreimage(preimage);
    }

    private static MutationPreimage preimage(String path, String text) {
        return MutationPreimage.existing(path, Hash.sha256Hex(text.getBytes(StandardCharsets.UTF_8)), text);
    }

    private static LoopState loopState() {
        return new LoopState("", List.of(), List.of(), null, null, null, 5, 0);
    }
}
