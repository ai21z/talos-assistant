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

    /**
     * JLine-safe stream sink wiring: when a {@link org.jline.reader.LineReader}
     * is supplied, streaming chunks must be routed through its
     * {@code Terminal.writer()} so JLine's cursor/column model stays in sync
     * with what actually reaches the terminal. Writes that bypass JLine
     * (raw {@code System.out.print}) leave JLine's internal state diverged
     * from reality; on Windows (jna=true) the next prompt redraw then
     * overwrites the live input line with scrollback content — the
     * "hallucinated text bled into next input" symptom observed in
     * test-output.txt Apr 2026 line 306.
     *
     * <p>This test proves the routing contract, not the redraw semantics:
     * we construct a DumbTerminal wired to a byte-sink, invoke the wired
     * stream sink directly with a known chunk, and assert the chunk
     * emerged from the terminal's writer and NOT from the
     * {@link java.io.PrintStream} passed as {@code out}.
     */
    @Test
    void bootstrapRoutesStreamThroughLineReaderTerminalWhenAvailable() throws Exception {
        java.io.ByteArrayOutputStream terminalSink = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream stdoutSink   = new java.io.ByteArrayOutputStream();

        org.jline.terminal.Terminal term = org.jline.terminal.TerminalBuilder.builder()
                .dumb(true)
                .streams(new java.io.ByteArrayInputStream(new byte[0]), terminalSink)
                .build();
        org.jline.reader.LineReader reader = org.jline.reader.LineReaderBuilder.builder()
                .terminal(term)
                .build();

        ReplRouter router = TalosBootstrap.create(
                stubSession(), new Config(),
                new java.io.PrintStream(stdoutSink),
                WS, reader);

        // Drive one chunk directly through the wired stream sink — same
        // path a live streaming turn would exercise, but without depending
        // on mode/placeholder/turn-executor internals.
        router.context().streamSink().accept("CHUNK-PROBE");
        term.flush();

        String termOut = terminalSink.toString(java.nio.charset.StandardCharsets.UTF_8);
        String stdOut  = stdoutSink.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(termOut.contains("CHUNK-PROBE"),
                "terminal writer must receive streamed chunks when LineReader is supplied");
        assertFalse(stdOut.contains("CHUNK-PROBE"),
                "streamed chunks must not leak to raw stdout when terminal-backed sink is available");
    }

    /**
     * Back-compat path: when no {@link org.jline.reader.LineReader} is
     * supplied (headless tests, programmatic API callers), the sink must
     * fall back to the provided {@link java.io.PrintStream}. Prevents a
     * silent regression where tightening the JLine path accidentally
     * drops output for non-interactive invocations.
     */
    @Test
    void bootstrapFallsBackToStdoutWhenLineReaderAbsent() {
        java.io.ByteArrayOutputStream stdoutSink = new java.io.ByteArrayOutputStream();
        ReplRouter router = TalosBootstrap.create(
                stubSession(), new Config(),
                new java.io.PrintStream(stdoutSink),
                WS); // no LineReader

        router.context().streamSink().accept("CHUNK-PROBE");
        String stdOut = stdoutSink.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(stdOut.contains("CHUNK-PROBE"),
                "with no LineReader, sink must fall back to the passed PrintStream");
    }
}

