package dev.talos.cli.repl;

import dev.talos.core.Config;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.SessionApprovalPolicy;
import dev.talos.runtime.TurnProcessor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prompt 6 — bootstrap wiring integration confidence.
 *
 * <p>The Prompt 3 policy layer and the Prompt 2 per-turn durability both
 * live in {@code dev.talos.runtime} and are exhaustively unit-tested in
 * isolation. None of those unit tests, however, prove that
 * {@link TalosBootstrap#create} actually threads those components into the
 * live runtime. This test closes that gap with one narrow assertion per
 * wiring contract:
 *
 * <ul>
 *   <li>{@link TurnProcessor#approvalPolicy()} returns a real
 *       {@link SessionApprovalPolicy} — not the {@link ApprovalPolicy#ALWAYS_ASK}
 *       default. (Regression guard against the pre-fix HEAD where the
 *       policy existed in code but was never instantiated by bootstrap.)</li>
 *   <li>{@link MemoryUpdateListener} is registered as a post-turn listener
 *       (conversation history commit).</li>
 *   <li>{@link JsonTurnLogAppender} is registered as a post-turn listener
 *       (per-turn JSONL durability).</li>
 * </ul>
 */
class TalosBootstrapWiringTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    private static SessionState stubSession() {
        return new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; }
            public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; }
            public void setDebug(boolean on) { dbg = on; }
        };
    }

    @Test
    void bootstrapWiresSessionApprovalPolicyIntoTurnProcessor() {
        ReplRouter router = TalosBootstrap.create(
                stubSession(), new Config(),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                WS);

        TurnProcessor tp = router.turnProcessor();
        assertNotNull(tp, "bootstrap must produce a wired TurnProcessor");

        ApprovalPolicy policy = tp.approvalPolicy();
        assertNotNull(policy);
        assertInstanceOf(SessionApprovalPolicy.class, policy,
                "live REPL path must use SessionApprovalPolicy, not ALWAYS_ASK — "
                        + "otherwise the user's 'a = yes for session' choice silently "
                        + "does nothing (pre-fix regression).");
    }

    @Test
    void bootstrapRegistersPerTurnListeners() {
        ReplRouter router = TalosBootstrap.create(
                stubSession(), new Config(),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                WS);

        TurnProcessor tp = router.turnProcessor();

        assertTrue(tp.hasListenerOfType(MemoryUpdateListener.class),
                "MemoryUpdateListener must be registered — without it, "
                        + "conversation history is never committed.");
        assertTrue(tp.hasListenerOfType(JsonTurnLogAppender.class),
                "JsonTurnLogAppender must be registered — without it, "
                        + "the per-turn JSONL durability is silently inactive "
                        + "and crash recovery degrades to the close-only snapshot.");
    }
}

