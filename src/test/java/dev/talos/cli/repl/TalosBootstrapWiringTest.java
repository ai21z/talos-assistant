package dev.talos.cli.repl;

import dev.talos.core.Config;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.SessionApprovalPolicy;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prompt 6 - bootstrap wiring integration confidence.
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
 *       {@link SessionApprovalPolicy} - not the {@link ApprovalPolicy#ALWAYS_ASK}
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

    @TempDir
    Path isolatedUserHome;

    private final List<ReplRouter> openedRouters = new ArrayList<>();
    private String previousUserHome;

    @BeforeEach
    void isolateUserHome() {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedUserHome.toString());
    }

    @AfterEach
    void closeRoutersAndRestoreUserHome() {
        try {
            for (int index = openedRouters.size() - 1; index >= 0; index--) {
                openedRouters.get(index).getRuntimeSession().close();
            }
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private ReplRouter createRouter(
            SessionState session,
            Config config,
            java.io.PrintStream out,
            Path workspace
    ) {
        return remember(TalosBootstrap.create(session, config, out, workspace));
    }

    private ReplRouter createRouter(
            SessionState session,
            Config config,
            java.io.PrintStream out,
            Path workspace,
            org.jline.reader.LineReader lineReader
    ) {
        return remember(TalosBootstrap.create(session, config, out, workspace, lineReader));
    }

    private ReplRouter createRouter(
            SessionState session,
            Config config,
            java.io.PrintStream out,
            Path workspace,
            org.jline.reader.LineReader lineReader,
            java.util.function.Function<String, String> approvalReader
    ) {
        return remember(TalosBootstrap.create(
                session, config, out, workspace, lineReader, approvalReader));
    }

    private ReplRouter remember(ReplRouter router) {
        openedRouters.add(router);
        return router;
    }

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
        ReplRouter router = createRouter(
                stubSession(), new Config(),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                WS);

        TurnProcessor tp = router.turnProcessor();
        assertNotNull(tp, "bootstrap must produce a wired TurnProcessor");

        ApprovalPolicy policy = tp.approvalPolicy();
        assertNotNull(policy);
        assertInstanceOf(SessionApprovalPolicy.class, policy,
                "live REPL path must use SessionApprovalPolicy, not ALWAYS_ASK - "
                        + "otherwise the user's 'a = yes for session' choice silently "
                        + "does nothing (pre-fix regression).");
    }

    @Test
    void bootstrapRegistersPerTurnListeners() {
        ReplRouter router = createRouter(
                stubSession(), new Config(),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                WS);

        TurnProcessor tp = router.turnProcessor();

        assertTrue(tp.hasListenerOfType(MemoryUpdateListener.class),
                "MemoryUpdateListener must be registered - without it, "
                        + "conversation history is never committed.");
        assertTrue(tp.hasListenerOfType(ActiveTaskContextUpdateListener.class),
                "ActiveTaskContextUpdateListener must be registered - without it, "
                        + "post-turn proposals, denials, and verifier findings "
                        + "never become follow-up context.");
        assertTrue(tp.hasListenerOfType(JsonTurnLogAppender.class),
                "JsonTurnLogAppender must be registered - without it, "
                        + "the per-turn JSONL durability is silently inactive "
                        + "and crash recovery degrades to the close-only snapshot.");
    }

    /**
     * The bootstrap discards provider fragments and renders the final
     * selected answer through the supplied {@code out} stream. In production RunCmd supplies a
     * terminal-backed stream ({@code TerminalOutput.printStreamFor}), so
     * JLine's cursor/column model sees every character that reaches the
     * terminal; that routing is covered by
     * {@code dev.talos.cli.ui.TerminalOutputTest}. The pre-T774 dual-writer
     * split (raw stdout beside terminal.writer()) caused the Apr 2026
     * prompt-redraw corruption documented in TalosBootstrap.
     */
    @Test
    void bootstrapDiscardsFragmentsAndRendersSelectedAnswerThroughTheSingleSuppliedStream() throws Exception {
        java.io.ByteArrayOutputStream terminalSink = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream suppliedSink = new java.io.ByteArrayOutputStream();

        org.jline.terminal.Terminal term = org.jline.terminal.TerminalBuilder.builder()
                .dumb(true)
                .streams(new java.io.ByteArrayInputStream(new byte[0]), terminalSink)
                .build();
        org.jline.reader.LineReader reader = org.jline.reader.LineReaderBuilder.builder()
                .terminal(term)
                .build();

        ReplRouter router = createRouter(
                stubSession(), new Config(),
                new java.io.PrintStream(suppliedSink),
                WS, reader);

        router.context().streamSink().accept("CHUNK-PROBE");
        assertInstanceOf(
                ToolCallStreamFilter.class,
                router.context().streamSink()).flush();
        assertEquals(0, suppliedSink.size(), "Raw fragments must not reach the supplied stream");
        assertTrue(router.getModes().setActive("ask"));
        assertTrue(router.tryHandlePrompt("Respond with exactly: SELECTED-PROBE"));
        term.flush();

        String termOut = terminalSink.toString(java.nio.charset.StandardCharsets.UTF_8);
        String supplied = suppliedSink.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(supplied.contains("CHUNK-PROBE"));
        assertTrue(supplied.contains("SELECTED-PROBE"),
                "The final selected answer must reach the single supplied stream");
        assertFalse(termOut.contains("CHUNK-PROBE"),
                "no second terminal-writer side channel may exist (single-writer contract)");
        assertFalse(termOut.contains("SELECTED-PROBE"),
                "Selected answers must not acquire a second terminal-writer side channel");
    }

    /**
     * Back-compat path: when no {@link org.jline.reader.LineReader} is
     * supplied, provider fragments remain private and the selected answer
     * is rendered to the provided {@link java.io.PrintStream}.
     */
    @Test
    void bootstrapRendersSelectedAnswerToStdoutWhenLineReaderAbsent() {
        java.io.ByteArrayOutputStream stdoutSink = new java.io.ByteArrayOutputStream();
        ReplRouter router = createRouter(
                stubSession(), new Config(),
                new java.io.PrintStream(stdoutSink),
                WS); // no LineReader

        router.context().streamSink().accept("CHUNK-PROBE");
        assertInstanceOf(
                ToolCallStreamFilter.class,
                router.context().streamSink()).flush();
        assertEquals(0, stdoutSink.size(), "Headless raw fragments must remain private");
        assertTrue(router.getModes().setActive("ask"));
        assertTrue(router.tryHandlePrompt("Respond with exactly: SELECTED-PROBE"));
        String stdOut = stdoutSink.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(stdOut.contains("CHUNK-PROBE"));
        assertTrue(stdOut.contains("SELECTED-PROBE"),
                "With no LineReader, the selected answer must reach the supplied PrintStream");
    }

    @Test
    void bootstrapUsesSuppliedApprovalReaderWhenNoLineReaderIsPresent() {
        List<String> prompts = new ArrayList<>();
        ReplRouter router = createRouter(
                stubSession(), new Config(),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                WS,
                null,
                prompt -> {
                    prompts.add(prompt);
                    return "n";
                });

        assertFalse(router.context().approvalGate().approve("write file", "target: index.html"));
        assertEquals(1, prompts.size(), "approval should read exactly one scripted response");
        assertTrue(prompts.getFirst().contains("Allow?"));
    }

    @Test
    void bootstrapClosesLlmClientWhenRuntimeSessionCloses() {
        ReplRouter router = createRouter(
                stubSession(), new Config(),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                WS);

        assertFalse(router.context().llm().isClosed(),
                "freshly bootstrapped LlmClient should be open before session shutdown");

        router.getRuntimeSession().close();

        assertTrue(router.context().llm().isClosed(),
                "runtime session close must close the context-owned LlmClient so managed engines release processes");
    }
}

