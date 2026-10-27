package dev.talos.qualification;

import dev.talos.cli.modes.Mode;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.ProviderCanonicalAnswerLineageFixture;
import dev.talos.core.util.UiChrome;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationCanonicalAnswerLineageIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void providerDisplayWrapperCommitsCanonicalAnswerAndRetainsModelOrigin()
            throws Exception {
        var captured = ProviderCanonicalAnswerLineageFixture.run(
                temp.resolve("provider-workspace"),
                temp.resolve("provider-store"));

        assertEquals("\n4\n\n", captured.displayText());
        assertEquals("4", captured.persistedAnswer());
        assertEquals(List.of(
                        "MODE_PRESENTATION_LAYOUT",
                        "SESSION_COMMIT_NORMALIZATION"),
                captured.trace().events().stream()
                        .filter(event -> "ANSWER_TRANSFORMED".equals(event.type()))
                        .map(event -> String.valueOf(event.data().get("reasonCode")))
                        .toList());

        byte[] scoredAnswer = (captured.persistedAnswer() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(
                QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                QualificationCausalOwnership.matchedAnswerOrigin(
                        scoredAnswer,
                        QualificationJson.bytes(captured.trace()),
                        QualificationProtocol.VERSION));
    }

    @Test
    void deterministicF07LikeDisplayWrapperCommitsCanonicalAnswerWithoutModelOrigin()
            throws Exception {
        Config config = new Config();
        JsonSessionStore store = new JsonSessionStore(temp.resolve("deterministic-store"));
        Path workspace = temp.resolve("deterministic-workspace");
        String sessionId = JsonSessionStore.newSessionInstanceId(
                workspace,
                Instant.parse("2026-07-21T00:00:00Z"));
        ModeController modes = new ModeController().add(new Mode() {
            @Override
            public String name() {
                return "ask";
            }

            @Override
            public boolean canHandle(String rawLine) {
                return true;
            }

            @Override
            public Optional<Result> handle(String rawLine, Path workspace, Context context) {
                LocalTurnTraceCapture.recordDeterministicAnswerSelected("Ready");
                String display = "\nReady\n\n";
                LocalTurnTraceCapture.recordAnswerTransformedForRouting(
                        display,
                        LocalTurnTraceCapture.AnswerTransformationReason
                                .MODE_PRESENTATION_LAYOUT);
                return Optional.of(new Result.Ok(display));
            }
        });
        assertTrue(modes.setActive("ask"));
        TurnResult result;
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = new TurnProcessor(modes);
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));

            result = processor.process(
                    new Session(workspace, config),
                    "Inspect the page and report when ready.",
                    Context.builder(config).build());
        }
        List<TurnRecord> records = store.loadTurns(sessionId);

        assertEquals(1, records.size());
        assertEquals("\nReady\n\n", ((Result.Ok) result.result()).text);
        assertEquals("Ready", records.getFirst().assistantText());
        assertEquals(
                QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                QualificationCausalOwnership.matchedAnswerOrigin(
                        (records.getFirst().assistantText() + "\n")
                                .getBytes(StandardCharsets.UTF_8),
                        QualificationJson.bytes(result.audit().localTrace()),
                        QualificationProtocol.VERSION),
                QualificationJson.write(result.audit().localTrace()));
    }

    @Test
    void pureOperatorAbortChromeCommitsAnExplicitEmptyOperatorAnswer()
            throws Exception {
        Config config = new Config();
        JsonSessionStore store = new JsonSessionStore(temp.resolve("operator-store"));
        Path workspace = temp.resolve("operator-workspace");
        String sessionId = JsonSessionStore.newSessionInstanceId(
                workspace,
                Instant.parse("2026-07-21T00:00:00Z"));
        ModeController modes = new ModeController().add(new Mode() {
            @Override
            public String name() {
                return "ask";
            }

            @Override
            public boolean canHandle(String rawLine) {
                return true;
            }

            @Override
            public Optional<Result> handle(String rawLine, Path workspace, Context context) {
                String marker = UiChrome.TURN_ABORTED_PREFIX + ": interrupted]";
                LocalTurnTraceCapture.recordOperatorAnswerSelected(marker);
                String display = "\n" + marker + "\n\n";
                LocalTurnTraceCapture.recordAnswerTransformedForRouting(
                        display,
                        LocalTurnTraceCapture.AnswerTransformationReason
                                .MODE_PRESENTATION_LAYOUT);
                LocalTurnTraceCapture.recordOutcome(
                        "FAILED", "NOT_RUN", "UNKNOWN", "BACKEND_ERROR", "LLM_ABORTED");
                return Optional.of(new Result.Ok(display));
            }
        });
        assertTrue(modes.setActive("ask"));
        TurnResult result;
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = new TurnProcessor(modes);
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));

            result = processor.process(
                    new Session(workspace, config),
                    "Create cancellation.txt, then interrupt before approval.",
                    Context.builder(config).build());
        }
        List<TurnRecord> records = store.loadTurns(sessionId);

        assertEquals(1, records.size());
        assertEquals("", records.getFirst().assistantText());
        assertEquals(
                QualificationRecords.AnswerOrigin.OPERATOR,
                QualificationCausalOwnership.matchedAnswerOrigin(
                        new byte[0],
                        QualificationJson.bytes(result.audit().localTrace()),
                        QualificationProtocol.VERSION));
    }
}
