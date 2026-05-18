package dev.talos.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.prompt.PromptDebugInspector;
import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.SessionApprovalPolicy;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TraceRedactor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.BatchWorkspaceApplyTool;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.DeletePathTool;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RenamePathTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Synchronized approval audit harness.
 *
 * <p>The current PowerShell live audit can pipe fixed input into the CLI, but
 * it cannot wait for approval prompts before sending approval responses. This
 * harness exercises the same runtime approval boundary with an explicit
 * fail-closed approval script: if an approval prompt appears unexpectedly, or
 * an expected prompt does not appear, the run fails.
 *
 * <p>Tests use {@link #runScripted(Request)} with a scripted LLM. The same
 * runner shape can be used with a live {@link LlmClient} by calling
 * {@link #run(Request, LlmClient)}.
 */
public final class SynchronizedApprovalAuditRunner {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SynchronizedApprovalAuditRunner() {
    }

    public record Request(
            String name,
            Path workspace,
            Config config,
            String userPrompt,
            List<String> scriptedModelResponses,
            List<ScriptedApprovalGate.Step> approvals
    ) {
        public Request {
            name = name == null || name.isBlank() ? "synchronized approval audit" : name;
            if (workspace == null) throw new IllegalArgumentException("workspace is required");
            config = config == null ? new Config(null) : config;
            userPrompt = userPrompt == null ? "" : userPrompt;
            scriptedModelResponses = scriptedModelResponses == null ? List.of() : List.copyOf(scriptedModelResponses);
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
        }
    }

    public record Result(
            String finalAnswer,
            List<ScriptedApprovalGate.Event> approvals,
            String modelTranscript,
            LocalTurnTrace trace
    ) {
        public Result {
            finalAnswer = finalAnswer == null ? "" : finalAnswer;
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
            modelTranscript = modelTranscript == null ? "" : modelTranscript;
        }

        public String traceText() {
            if (trace == null) return "";
            StringBuilder out = new StringBuilder();
            out.append(trace.outcome().status()).append('\n');
            for (var event : trace.events()) {
                out.append(event.type()).append(' ')
                        .append(event.toolName()).append(' ')
                        .append(event.data()).append('\n');
            }
            return out.toString();
        }
    }

    public record ArtifactBundle(
            Path root,
            Path summary,
            Path finalAnswer,
            Path approvalsJsonl,
            Path modelTranscript,
            Path traceJson,
            Path traceText,
            Path promptDebugMarkdown,
            Path providerBodyJson,
            Path sessionSnapshot,
            Path turnJsonl,
            Path workspaceStatus,
            Path workspaceDiff
    ) {
    }

    public static Result runScripted(Request request) {
        return run(request, LlmClient.scripted(request.scriptedModelResponses()));
    }

    public static Result run(Request request, LlmClient llm) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (llm == null) throw new IllegalArgumentException("llm is required");

        ScriptedApprovalGate gate = new ScriptedApprovalGate(request.approvals());
        ToolRegistry registry = standardToolRegistry();
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry,
                new SessionApprovalPolicy());
        ToolCallLoop loop = new ToolCallLoop(processor, ToolCallLoop.DEFAULT_MAX_ITERATIONS);
        Context ctx = Context.builder(request.config())
                .sandbox(new Sandbox(request.workspace(), Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .llm(llm)
                .executionPhaseState(new ExecutionPhaseState(ExecutionPhase.INSPECT))
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("synchronized approval audit harness"));
        messages.add(ChatMessage.user(request.userPrompt()));

        beginTrace(request, llm);
        PromptDebugCapture.beginTurn();
        TurnUserRequestCapture.set(request.userPrompt());
        AssistantTurnExecutor.TurnOutput turnOutput;
        LocalTurnTrace trace;
        try {
            turnOutput = AssistantTurnExecutor.execute(
                    messages,
                    request.workspace(),
                    ctx,
                    new AssistantTurnExecutor.Options());
            LocalTurnTraceCapture.recordModelResponseReceived(turnOutput.text());
            LocalTurnTraceCapture.recordOutcomeIfAbsent(
                    "OK",
                    "NOT_RUN",
                    "UNKNOWN",
                    "UNKNOWN",
                    "SYNCHRONIZED_APPROVAL_AUDIT");
            trace = LocalTurnTraceCapture.complete();
            gate.assertExhausted();
            return new Result(turnOutput.text(), gate.events(), messages.toString(), trace);
        } finally {
            TurnUserRequestCapture.clear();
            LocalTurnTraceCapture.clear();
            if (TurnAuditCapture.isActive()) {
                TurnAuditCapture.end();
            }
        }
    }

    private static ToolRegistry standardToolRegistry() {
        FileUndoStack undoStack = new FileUndoStack();
        ToolRegistry registry = new ToolRegistry(false);
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        registry.register(new BatchWorkspaceApplyTool());
        registry.register(new MakeDirectoryTool());
        registry.register(new MovePathTool());
        registry.register(new CopyPathTool());
        registry.register(new RenamePathTool());
        registry.register(new DeletePathTool());
        registry.register(new GrepTool());
        registry.register(new ListDirTool());
        return registry;
    }

    public static ArtifactBundle writeAuditArtifacts(Path artifactRoot, Request request, Result result)
            throws IOException {
        if (artifactRoot == null) throw new IllegalArgumentException("artifactRoot is required");
        if (request == null) throw new IllegalArgumentException("request is required");
        if (result == null) throw new IllegalArgumentException("result is required");

        Path root = artifactRoot.toAbsolutePath().normalize().resolve(safeFileName(request.name()));
        deleteScenarioArtifactRoot(artifactRoot.toAbsolutePath().normalize(), root);
        Path promptDebugDir = root.resolve("prompt-debug");
        Path providerDir = root.resolve("provider-bodies");
        Path traceDir = root.resolve("traces");
        Path sessionDir = root.resolve("sessions");
        Path workspaceDir = root.resolve("workspace");
        Files.createDirectories(promptDebugDir);
        Files.createDirectories(providerDir);
        Files.createDirectories(traceDir);
        Files.createDirectories(sessionDir);
        Files.createDirectories(workspaceDir);

        Path finalAnswer = root.resolve("final-answer.txt");
        Path approvalsJsonl = root.resolve("approvals.jsonl");
        Path modelTranscript = root.resolve("model-transcript.txt");
        Path traceJson = traceDir.resolve("last-trace.json");
        Path traceText = traceDir.resolve("last-trace.txt");
        Path promptDebugMarkdown = promptDebugDir.resolve("prompt-debug.md");
        Path providerBodyJson = providerDir.resolve("provider-body.json");
        String sessionId = JsonSessionStore.sessionIdFor(request.workspace());
        Path sessionSnapshot = sessionDir.resolve(sessionId + ".json");
        Path turnJsonl = sessionDir.resolve(sessionId + ".turns.jsonl");
        Path workspaceStatus = workspaceDir.resolve("status.txt");
        Path workspaceDiff = workspaceDir.resolve("diff.txt");
        Path summary = root.resolve("AUDIT-BUNDLE.md");

        String finalAnswerForArtifacts = assistantTextForArtifacts(request, result);
        writeSafe(finalAnswer, finalAnswerForArtifacts);
        writeSafe(modelTranscript, modelTranscriptForArtifacts(request, result));
        writeApprovals(approvalsJsonl, result.approvals());
        writeTraceJson(traceJson, result.trace());
        writeSafe(traceText, result.traceText());
        writePromptDebug(promptDebugMarkdown, providerBodyJson);
        writeSessionArtifacts(sessionDir, sessionId, request, result, finalAnswerForArtifacts);
        writeSafe(workspaceStatus, workspaceStatus(request.workspace()));
        writeSafe(workspaceDiff, workspaceDiffPlaceholder(request.workspace()));
        writeSafe(summary, summary(request, result, root, finalAnswer, approvalsJsonl, modelTranscript,
                traceJson, traceText, promptDebugMarkdown, providerBodyJson, sessionSnapshot, turnJsonl,
                workspaceStatus, workspaceDiff));

        return new ArtifactBundle(
                root,
                summary,
                finalAnswer,
                approvalsJsonl,
                modelTranscript,
                traceJson,
                traceText,
                promptDebugMarkdown,
                providerBodyJson,
                sessionSnapshot,
                turnJsonl,
                workspaceStatus,
                workspaceDiff);
    }

    private static void writeApprovals(Path path, List<ScriptedApprovalGate.Event> approvals) throws IOException {
        StringBuilder out = new StringBuilder();
        for (ScriptedApprovalGate.Event event : approvals == null ? List.<ScriptedApprovalGate.Event>of() : approvals) {
            out.append(sanitize(JSON.writeValueAsString(event))).append(System.lineSeparator());
        }
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeTraceJson(Path path, LocalTurnTrace trace) throws IOException {
        if (trace == null) {
            writeSafe(path, "{\"status\":\"not-captured\"}\n");
            return;
        }
        writeSafe(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
    }

    private static void writePromptDebug(Path markdownPath, Path providerBodyPath) throws IOException {
        PromptDebugSnapshot snapshot = PromptDebugCapture.latest().orElse(null);
        if (snapshot == null) {
            writeSafe(markdownPath, """
                    # Talos Prompt Debug

                    No provider prompt was captured for this harness run.
                    Scripted deterministic runs may exercise runtime policy without provider transport.
                    """);
            writeSafe(providerBodyPath, """
                    {
                      "status": "not-captured",
                      "reason": "No provider body was captured for this harness run."
                    }
                    """);
            return;
        }
        writeSafe(markdownPath, PromptDebugInspector.format(snapshot));
        if (snapshot.providerBodyJson().isBlank()) {
            writeSafe(providerBodyPath, """
                    {
                      "status": "not-captured",
                      "reason": "Prompt capture had no provider body JSON."
                    }
                    """);
        } else {
            writeSafe(providerBodyPath, PromptDebugInspector.redactedProviderBodyJson(snapshot));
        }
    }

    private static void writeSessionArtifacts(
            Path sessionDir,
            String sessionId,
            Request request,
            Result result,
            String finalAnswerForArtifacts) {
        JsonSessionStore store = new JsonSessionStore(sessionDir);
        Instant now = Instant.now();
        String model = result.trace() == null ? "" : result.trace().model().model();
        String assistantText = finalAnswerForArtifacts == null ? "" : finalAnswerForArtifacts;
        store.save(new SessionData(
                sessionId,
                request.workspace().toAbsolutePath().normalize().toString(),
                "",
                1,
                now,
                List.of(
                        new SessionData.Turn("user", request.userPrompt(), "ok"),
                        new SessionData.Turn("assistant", assistantText, "ok")),
                model));
        store.appendTurn(sessionId, new TurnRecord(
                1,
                now,
                0L,
                request.userPrompt(),
                assistantText,
                toolCalls(result.trace()),
                result.approvals().size(),
                (int) result.approvals().stream().filter(event -> event.response().isApproved()).count(),
                (int) result.approvals().stream().filter(event -> !event.response().isApproved()).count(),
                "",
                "ok",
                TurnPolicyTrace.empty(),
                result.trace() == null ? "" : result.trace().traceId()));
        if (result.trace() != null) {
            store.saveTrace(sessionId, result.trace());
        }
    }

    private static List<TurnRecord.ToolCallSummary> toolCalls(LocalTurnTrace trace) {
        if (trace == null || trace.events().isEmpty()) return List.of();
        return trace.events().stream()
                .filter(event -> event.toolName() != null && !event.toolName().isBlank())
                .map(event -> new TurnRecord.ToolCallSummary(
                        event.toolName(),
                        "",
                        true,
                        event.type()))
                .toList();
    }

    private static String workspaceStatus(Path workspace) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("Workspace: ").append(workspace.toAbsolutePath().normalize()).append('\n');
        out.append("Git repository: ").append(Files.isDirectory(workspace.resolve(".git"))).append('\n');
        out.append("Files:\n");
        if (!Files.exists(workspace)) return out.append("(missing)\n").toString();
        try (Stream<Path> paths = Files.walk(workspace)) {
            List<String> files = paths
                    .filter(Files::isRegularFile)
                    .map(path -> workspace.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toList());
            if (files.isEmpty()) {
                out.append("(none)\n");
            } else {
                for (String file : files) {
                    out.append("- ").append(file).append('\n');
                }
            }
        }
        return out.toString();
    }

    private static String workspaceDiffPlaceholder(Path workspace) {
        return """
                Workspace diff capture: not available in the deterministic Java approval harness.
                Use the CLI/PTTY live-audit runner for real git status/diff capture.

                Workspace root: %s
                """.formatted(workspace.toAbsolutePath().normalize());
    }

    private static String summary(
            Request request,
            Result result,
            Path root,
            Path finalAnswer,
            Path approvalsJsonl,
            Path modelTranscript,
            Path traceJson,
            Path traceText,
            Path promptDebugMarkdown,
            Path providerBodyJson,
            Path sessionSnapshot,
            Path turnJsonl,
            Path workspaceStatus,
            Path workspaceDiff) {
        return """
                # Synchronized Approval Audit Bundle

                - Run: %s
                - Workspace: %s
                - Artifact root: %s
                - Approvals observed: %d
                - Trace ID: %s

                ## Files

                - Final answer: %s
                - Approvals JSONL: %s
                - Model transcript: %s
                - Trace JSON: %s
                - Trace text: %s
                - Prompt debug markdown: %s
                - Provider body JSON: %s
                - Session snapshot: %s
                - Turn JSONL: %s
                - Workspace status: %s
                - Workspace diff: %s
                """.formatted(
                request.name(),
                request.workspace().toAbsolutePath().normalize(),
                root,
                result.approvals().size(),
                result.trace() == null ? "" : result.trace().traceId(),
                finalAnswer,
                approvalsJsonl,
                modelTranscript,
                traceJson,
                traceText,
                promptDebugMarkdown,
                providerBodyJson,
                sessionSnapshot,
                turnJsonl,
                workspaceStatus,
                workspaceDiff);
    }

    private static void writeSafe(Path path, String value) throws IOException {
        Files.writeString(path, sanitize(value), StandardCharsets.UTF_8);
    }

    private static void deleteScenarioArtifactRoot(Path artifactRoot, Path root) throws IOException {
        Path safeArtifactRoot = artifactRoot.toAbsolutePath().normalize();
        Path safeRoot = root.toAbsolutePath().normalize();
        if (!safeRoot.startsWith(safeArtifactRoot) || safeRoot.equals(safeArtifactRoot)) {
            throw new IOException("refusing to clear unsafe artifact root: " + safeRoot);
        }
        if (!Files.exists(safeRoot)) return;
        try (Stream<Path> paths = Files.walk(safeRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String assistantTextForArtifacts(Request request, Result result) {
        String answer = result == null ? "" : result.finalAnswer();
        if (rawProtectedReadMayHaveEnteredModelContext(request, result)) {
            return MemoryUpdateListener.assistantTextForPersistence(answer, request.userPrompt());
        }
        return answer;
    }

    private static String modelTranscriptForArtifacts(Request request, Result result) {
        String transcript = result == null ? "" : result.modelTranscript();
        if (rawProtectedReadMayHaveEnteredModelContext(request, result)) {
            return MemoryUpdateListener.assistantTextForPersistence(transcript, request.userPrompt());
        }
        return transcript;
    }

    private static boolean rawProtectedReadMayHaveEnteredModelContext(Request request, Result result) {
        if (request == null || result == null) return false;
        if (!ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(request.config())) return false;
        if (!result.approvals().stream().anyMatch(event -> event.response().isApproved())) return false;
        return TraceRedactor.looksLikeProtectedReadRequest(request.userPrompt());
    }

    private static String sanitize(String value) {
        return ProtectedContentPolicy.sanitizeText(Objects.toString(value, ""));
    }

    private static String safeFileName(String value) {
        String safe = Objects.toString(value, "").strip().replaceAll("[^A-Za-z0-9._-]", "-");
        safe = safe.replaceAll("-+", "-");
        if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) return "approval-audit";
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private static void beginTrace(Request request, LlmClient llm) {
        TurnAuditCapture.begin();
        LocalTurnTraceCapture.begin(
                "trc-sync-approval-" + request.name().replaceAll("[^A-Za-z0-9._-]", "_"),
                "sync-approval-audit",
                1,
                Instant.now().toString(),
                "workspace:" + Integer.toHexString(request.workspace().toString().hashCode()),
                "harness",
                backendFrom(llm),
                llm.getModel(),
                request.userPrompt());
    }

    private static String backendFrom(LlmClient llm) {
        String model = llm == null ? "" : llm.getModel();
        int slash = model.indexOf('/');
        return slash > 0 ? model.substring(0, slash) : "scripted";
    }
}
