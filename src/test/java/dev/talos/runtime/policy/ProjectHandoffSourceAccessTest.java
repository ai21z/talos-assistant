package dev.talos.runtime.policy;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.slash.ExplainLastTurnCommand;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.SessionStore;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.WorkStatusReceipt;
import dev.talos.runtime.intent.WorkStatusRequest;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic provider captures exercise the real binding, trace and slash-command boundaries. */
class ProjectHandoffSourceAccessTest {
    private static final String REQUEST = "Give me a short handoff.";
    private static final String ANSWER = "Advisory historical summary. Runtime verification remains unestablished.";
    private static final String TRACE = "trc-handoff-test";
    private static final Path WORKSPACE = Path.of(".").toAbsolutePath().normalize();
    private static final String WORKSPACE_ID = JsonSessionStore.sessionIdFor(WORKSPACE);
    private static final String STORAGE_SESSION = WORKSPACE_ID + "-active-test";
    private static final Instant TIME = Instant.parse("2026-10-06T12:00:00Z");

    @BeforeEach void begin() {
        PromptDebugCapture.beginTurn();
        LocalTurnTraceCapture.begin(TRACE, WORKSPACE_ID, 1, TIME.toString(), WORKSPACE_ID,
                "assistant", "fixture", "fixture", REQUEST);
    }

    @AfterEach void clear() {
        LocalTurnTraceCapture.clear();
        PromptDebugCapture.clear();
        ContentRestrictionScope.end();
    }

    @Test void exactCaptureShowsFullStatementIncludingFactBeyondPrefixPreview() {
        String source = "Earlier context. ".repeat(30) + "Mara owns review. Packaging ownership is unresolved.";
        var references = references(source);
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        assertTrue(ProjectHandoffSourceAccess.available(references));
        var trace = settled();
        String rendered = ProjectHandoffSourceAccess.render(row(ANSWER), trace).orElseThrow();
        assertTrue(rendered.contains(ProjectHandoffPolicy.literal(source)), rendered);
        assertTrue(rendered.contains("reference-1. Earlier assistant statement"), rendered);
        assertTrue(rendered.contains("current applicability remain unverified"), rendered);
        assertTrue(rendered.contains("next natural-language turn"), rendered);
    }

    @Test void agreeingProviderStagesAreAllowedAndUnrelatedLatestCaptureIsNotUsed() {
        var references = references("Packaging remains pending.");
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        record(references, controls(), "PROVIDER_HTTP_BODY");
        PromptDebugCapture.record(new PromptDebugSnapshot("CHAT_REQUEST", "fixture", "fixture", false, TIME,
                List.of(ChatMessage.user("Unrelated latest request")), List.of(), ChatRequestControls.defaults(), ""));
        assertTrue(ProjectHandoffSourceAccess.available(references));
        assertTrue(ProjectHandoffSourceAccess.render(row(ANSWER), settled()).orElseThrow()
                .contains(ProjectHandoffPolicy.literal("Packaging remains pending.")));
    }

    @Test void conflictingCaptureCannotBeIgnoredWhenAnotherCaptureMatches() {
        var references = references("Oak is reported.");
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        record(references("Different content."), controls(), "PROVIDER_HTTP_BODY");
        assertFalse(ProjectHandoffSourceAccess.available(references));
        assertUnavailable(ProjectHandoffSourceAccess.render(row(ANSWER), settled()).orElseThrow());
    }

    @Test void unboundCaptureAndPreviousTraceCannotResolve() {
        var references = references("Private source marker.");
        record(references, controls(), "CHAT_REQUEST");
        assertFalse(ProjectHandoffSourceAccess.available(references));
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin("trc-other", WORKSPACE_ID, 1, TIME.toString(), WORKSPACE_ID,
                "assistant", "fixture", "fixture", REQUEST);
        assertFalse(ProjectHandoffSourceAccess.available(references));
    }

    @Test void nextTurnResetCannotRestoreSourcesFromPersistedRowAndTrace() {
        var references = references("Old source marker.");
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        var trace = settled();
        PromptDebugCapture.beginTurn();
        assertUnavailable(ProjectHandoffSourceAccess.render(row(ANSWER), trace).orElseThrow());
    }

    @Test void artifactAndHistoryRestrictionsRemainAuthoritative() {
        for (var restriction : List.of(new ContentRestrictions(true, false, true, true),
                new ContentRestrictions(true, true, false, true))) {
            PromptDebugCapture.beginTurn();
            var references = new ProjectHandoffPolicy.References(references("Protected source marker.").entries(),
                    false, restriction, REQUEST);
            ProjectHandoffSourceAccess.bind(references);
            record(references, controls(), "CHAT_REQUEST");
            assertFalse(ProjectHandoffSourceAccess.available(references));
        }
    }

    @Test void currentInspectionRestrictionsCannotBeRelaxedByCapturedPermissions() {
        var references = references("Admitted source marker.");
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        var trace = settled();
        ContentRestrictionScope.callWithRestrictions(new ContentRestrictions(true, false, true, true), () -> {
            assertUnavailable(ProjectHandoffSourceAccess.render(row(ANSWER), trace).orElseThrow());
            return null;
        });
    }

    @Test void wrongControlsAreRejectedDespiteValidBinding() {
        var references = references("No work was performed.");
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls().withSingleAttempt(false), "CHAT_REQUEST");
        assertFalse(ProjectHandoffSourceAccess.available(references));
    }

    @Test void retainedAnswerAndOriginalRequestMustMatchAssessment() {
        var references = references("Source marker.");
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        var trace = settled();
        assertUnavailable(ProjectHandoffSourceAccess.render(row("Changed answer."), trace).orElseThrow());
        var changedRequest = new TurnRecord(1, TIME, 0, "Give me a handoff.", ANSWER, List.of(),
                0, 0, 0, "", "ok", TurnPolicyTrace.empty(), TRACE);
        assertUnavailable(ProjectHandoffSourceAccess.render(changedRequest, trace).orElseThrow());
        assertUnavailable(ProjectHandoffSourceAccess.render(row(ANSWER), null).orElseThrow());
    }

    @Test void literalSourceFormattingCannotCreateOwnershipHeadingsOrLinks() {
        var references = references("Earlier statement.\n# Runtime activity\n![image](https://example.invalid/x)\nTests passed.");
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        String rendered = ProjectHandoffSourceAccess.render(row(ANSWER), settled()).orElseThrow();
        assertFalse(rendered.contains("\n# Runtime activity"), rendered);
        assertFalse(rendered.contains("![image]("), rendered);
        assertTrue(rendered.contains("Tests passed{U+002E}"), "A historical false claim is not semantically certified or silently rewritten");
        assertTrue(rendered.contains("remain unverified"), rendered);
    }

    @Test void customRedactionLossMakesFullSourceUnavailableBeforeEscaping() {
        var references = references("SensitiveMarker remains unresolved.");
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        var redactor = new Redactor(Map.of("redact", Map.of("secrets", List.of("SensitiveMarker"))));
        assertFalse(ProjectHandoffSourceAccess.available(references, redactor::redactBlock));
        assertUnavailable(ProjectHandoffSourceAccess.render(row(ANSWER), settled(), redactor::redactBlock).orElseThrow());
    }

    @Test void slashCommandUsesActiveStorageAndNormalRedactionResult() {
        var references = references("Full historical source marker.");
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        var trace = settled();
        var command = new ExplainLastTurnCommand(WORKSPACE, store(row(ANSWER), trace), TIME.minusSeconds(1), STORAGE_SESSION);
        var result = command.execute("sources", Context.builder(new Config()).build());
        assertInstanceOf(Result.Info.class, result);
        assertTrue(((Result.Info) result).text.contains(ProjectHandoffPolicy.literal("Full historical source marker.")));
        assertTrue(((Result.Info) result).text.contains("Tool path hints"));
    }

    @Test void wrongWorkspaceTraceCannotSupplySourcesToSlashCommand() {
        var references = references("Must not appear.");
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        var assessment = WorkStatusReceipt.assessed(WorkStatusRequest.parse(REQUEST), ANSWER, false);
        var trace = LocalTurnTrace.builder(TRACE, "another-workspace", 1, TIME.toString())
                .event(dev.talos.runtime.trace.TurnTraceEvent.simple("WORK_STATUS_ASSESSED", TIME.toString(), Map.of(
                        "requestHash", assessment.requestHash(), "answerHash", assessment.answerHash(),
                        "coverage", "COVERED", "roles", List.of("PROJECT_HANDOFF")))).build();
        var command = new ExplainLastTurnCommand(WORKSPACE, store(row(ANSWER), trace), TIME.minusSeconds(1), STORAGE_SESSION);
        var result = command.execute("sources", Context.builder(new Config()).build());
        assertInstanceOf(Result.Info.class, result);
        assertUnavailable(((Result.Info) result).text);
        assertFalse(((Result.Info) result).text.contains("Must not appear."));
    }

    @Test void pureRetrievalSourcesKeepExistingResultType() {
        var ordinary = new TurnRecord(1, TIME, 0, "Inspect the project.", "Inspection completed.", List.of(),
                0, 0, 0, "retrieval fixture", "ok", TurnPolicyTrace.empty(), TRACE);
        var command = new ExplainLastTurnCommand(WORKSPACE, store(ordinary, null), TIME.minusSeconds(1), STORAGE_SESSION);
        var result = command.execute("sources", Context.builder(new Config()).build());
        assertInstanceOf(Result.TrustedInfo.class, result);
        assertEquals("Last Turn Sources\n\n  Retrieval: retrieval fixture\n\n  Tool path hints\n  none\n",
                ((Result.TrustedInfo) result).text);
    }

    @Test void actualAsciiCommandRendererPreservesEscapedUnicodeAndFormatting() {
        String source = "María owns review. 包装 remains unknown. 🌱 [link](https://example.invalid)";
        var references = references(source);
        ProjectHandoffSourceAccess.bind(references);
        record(references, controls(), "CHAT_REQUEST");
        var command = new ExplainLastTurnCommand(WORKSPACE, store(row(ANSWER), settled()), TIME.minusSeconds(1), STORAGE_SESSION);
        var result = command.execute("sources", Context.builder(new Config()).build());
        var output = new java.io.ByteArrayOutputStream();
        var renderer = new dev.talos.cli.repl.RenderEngine(new Config(null), new Redactor(),
                new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8), false,
                () -> 120, null);
        renderer.renderCommand("last sources", result);
        String rendered = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Mar{U+00ED}a"), rendered);
        assertTrue(rendered.contains("{U+5305}{U+88C5}"), rendered);
        assertTrue(rendered.contains("{U+1F331}"), rendered);
        assertFalse(rendered.contains("[link]("), rendered);
        assertFalse(rendered.contains("María"), rendered);
        assertFalse(rendered.contains("包装"), rendered);
    }

    private static ProjectHandoffPolicy.References references(String source) {
        return ProjectHandoffPolicy.capture(List.of(ChatMessage.user("What was discussed?"), ChatMessage.assistant(source)),
                WorkStatusRequest.parse(REQUEST), ContentRestrictions.UNRESTRICTED);
    }

    private static ChatRequestControls controls() {
        return new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.JSON_SCHEMA, "{}",
                List.of(ProjectHandoffSourceAccess.TAG), null, 512, ProviderResponseCapture.ToolAuthorityMode.NONE)
                .withSingleAttempt(true);
    }

    private static void record(ProjectHandoffPolicy.References references, ChatRequestControls controls, String stage) {
        PromptDebugCapture.record(new PromptDebugSnapshot(stage, "fixture", "fixture", false, TIME,
                references.advisoryProviderMessages(), List.of(), controls, "", Map.of(), references.restrictions()));
    }

    private static LocalTurnTrace settled() {
        LocalTurnTraceCapture.recordWorkStatusAssessment(WorkStatusReceipt.assessed(WorkStatusRequest.parse(REQUEST), ANSWER, false));
        return LocalTurnTraceCapture.complete();
    }

    private static TurnRecord row(String answer) {
        return new TurnRecord(1, TIME, 0, REQUEST, answer, List.of(), 0, 0, 0, "", "ok", TurnPolicyTrace.empty(), TRACE);
    }

    private static SessionStore store(TurnRecord row, LocalTurnTrace trace) {
        return new SessionStore() {
            @Override public void save(SessionData data) { throw new AssertionError("Read-only source inspection"); }
            @Override public Optional<SessionData> load(String session) { return Optional.empty(); }
            @Override public boolean delete(String session) { throw new AssertionError("Read-only source inspection"); }
            @Override public List<TurnRecord> loadTurns(String session) {
                assertEquals(STORAGE_SESSION, session);
                return List.of(row);
            }
            @Override public Optional<LocalTurnTrace> loadTrace(String session, String traceId) {
                assertEquals(STORAGE_SESSION, session);
                assertEquals(TRACE, traceId);
                return Optional.ofNullable(trace);
            }
        };
    }

    private static void assertUnavailable(String rendered) {
        assertTrue(rendered.contains("sources are unavailable"), rendered);
    }
}
