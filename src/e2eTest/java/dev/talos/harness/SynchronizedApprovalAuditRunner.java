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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
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
            LocalTurnTrace trace,
            String workspaceDiff
    ) {
        public Result(
                String finalAnswer,
                List<ScriptedApprovalGate.Event> approvals,
                String modelTranscript,
                LocalTurnTrace trace
        ) {
            this(finalAnswer, approvals, modelTranscript, trace, "");
        }

        public Result {
            finalAnswer = finalAnswer == null ? "" : finalAnswer;
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
            modelTranscript = modelTranscript == null ? "" : modelTranscript;
            workspaceDiff = workspaceDiff == null ? "" : workspaceDiff;
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

    public static final class AuditFailure extends AssertionError {
        private final Result partialResult;

        AuditFailure(String message, Result partialResult, Throwable cause) {
            super(message, cause);
            this.partialResult = partialResult == null
                    ? new Result("", List.of(), "", null)
                    : partialResult;
        }

        public Result partialResult() {
            return partialResult;
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
            Path transcriptJson,
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
        WorkspaceSnapshot beforeWorkspace = WorkspaceSnapshot.capture(request.workspace());
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
            WorkspaceSnapshot afterWorkspace = WorkspaceSnapshot.capture(request.workspace());
            Result result = new Result(
                    turnOutput.text(),
                    gate.events(),
                    messages.toString(),
                    trace,
                    WorkspaceSnapshot.diff(beforeWorkspace, afterWorkspace));
            try {
                gate.assertExhausted();
            } catch (AssertionError e) {
                throw new AuditFailure(e.getMessage(), result, e);
            }
            return result;
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
        Path transcriptJson = root.resolve("audit-transcript.json");
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
        writeAuditTranscript(transcriptJson, request, result, root);
        writeSafe(workspaceStatus, workspaceStatus(request.workspace()));
        writeSafe(workspaceDiff, workspaceDiff(request, result));
        writeSafe(summary, summary(request, result, root, finalAnswer, approvalsJsonl, modelTranscript,
                traceJson, traceText, promptDebugMarkdown, providerBodyJson, sessionSnapshot, turnJsonl,
                transcriptJson, workspaceStatus, workspaceDiff));

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
                transcriptJson,
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

    private static void writeAuditTranscript(
            Path path,
            Request request,
            Result result,
            Path root
    ) throws IOException {
        Map<String, Object> transcript = new LinkedHashMap<>();
        transcript.put("schemaVersion", 1);
        transcript.put("schemaName", "talos.synchronizedApprovalAuditTranscript");
        transcript.put("scenario", request.name());
        transcript.put("workspace", request.workspace().toAbsolutePath().normalize().toString());
        transcript.put("artifactRoot", root.toAbsolutePath().normalize().toString());
        transcript.put("userPromptHash", sha256(request.userPrompt()));
        transcript.put("userPromptChars", request.userPrompt().length());
        transcript.put("finalAnswerHash", sha256(result.finalAnswer()));
        transcript.put("finalAnswerChars", result.finalAnswer().length());
        transcript.put("approvalCount", result.approvals().size());
        transcript.put("approvalResponses", result.approvals().stream()
                .map(event -> event.response().name())
                .toList());
        transcript.put("approvalDescriptions", result.approvals().stream()
                .map(event -> sanitize(event.description()))
                .toList());
        LocalTurnTrace trace = result.trace();
        transcript.put("traceId", trace == null ? "" : trace.traceId());
        transcript.put("traceStatus", trace == null ? "" : trace.outcome().status());
        transcript.put("verificationStatus", trace == null ? "" : trace.verification().status());
        transcript.put("verificationSummary", trace == null ? "" : sanitize(trace.verification().summary()));
        transcript.put("checkpointStatus", trace == null ? "" : trace.checkpoint().status());
        transcript.put("toolEventTypes", trace == null ? List.of() : trace.events().stream()
                .map(event -> event.type())
                .toList());
        writeSafe(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(transcript));
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

    private static String workspaceDiff(Request request, Result result) {
        String diff = result == null ? "" : result.workspaceDiff();
        if (diff != null && !diff.isBlank()) return diff;
        Path workspace = request == null ? Path.of(".") : request.workspace();
        return """
                Workspace diff capture: unavailable.
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
            Path transcriptJson,
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
                - Audit transcript JSON: %s
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
                transcriptJson,
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
        if (privateDocumentMayHaveEnteredModelContext(request, result)) {
            return TraceRedactor.PRIVATE_DOCUMENT_ANSWER_REDACTION;
        }
        if (rawProtectedReadMayHaveEnteredModelContext(request, result)) {
            return MemoryUpdateListener.assistantTextForPersistence(answer, request.userPrompt());
        }
        return answer;
    }

    private static String modelTranscriptForArtifacts(Request request, Result result) {
        String transcript = result == null ? "" : result.modelTranscript();
        if (privateDocumentMayHaveEnteredModelContext(request, result)) {
            return TraceRedactor.PRIVATE_DOCUMENT_ANSWER_REDACTION;
        }
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

    private static boolean privateDocumentMayHaveEnteredModelContext(Request request, Result result) {
        if (request == null || result == null) return false;
        return TraceRedactor.looksLikeDocumentExtractionRequest(request.userPrompt());
    }

    private static String sanitize(String value) {
        return ProtectedContentPolicy.sanitizeText(Objects.toString(value, ""));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Objects.toString(value, "").getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value == null ? new byte[0] : value);
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
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

    private record WorkspaceSnapshot(Map<String, SnapshotFile> files, String error) {
        static WorkspaceSnapshot capture(Path workspace) {
            if (workspace == null) {
                return new WorkspaceSnapshot(Map.of(), "workspace is null");
            }
            Path root = workspace.toAbsolutePath().normalize();
            if (!Files.exists(root)) {
                return new WorkspaceSnapshot(Map.of(), "workspace does not exist: " + root);
            }
            Map<String, SnapshotFile> files = new LinkedHashMap<>();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList()) {
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    if (relative.equals(".git") || relative.startsWith(".git/")) continue;
                    files.put(relative, SnapshotFile.capture(path));
                }
                return new WorkspaceSnapshot(Map.copyOf(files), "");
            } catch (IOException e) {
                return new WorkspaceSnapshot(Map.copyOf(files),
                        "workspace snapshot failed: " + sanitize(e.getMessage()));
            }
        }

        static String diff(WorkspaceSnapshot before, WorkspaceSnapshot after) {
            WorkspaceSnapshot safeBefore = before == null ? new WorkspaceSnapshot(Map.of(), "before snapshot missing") : before;
            WorkspaceSnapshot safeAfter = after == null ? new WorkspaceSnapshot(Map.of(), "after snapshot missing") : after;
            StringBuilder out = new StringBuilder();
            out.append("Workspace diff captured by deterministic Java approval harness.\n");
            if (!safeBefore.error().isBlank()) {
                out.append("Before snapshot warning: ").append(sanitize(safeBefore.error())).append('\n');
            }
            if (!safeAfter.error().isBlank()) {
                out.append("After snapshot warning: ").append(sanitize(safeAfter.error())).append('\n');
            }
            TreeSet<String> paths = new TreeSet<>();
            paths.addAll(safeBefore.files().keySet());
            paths.addAll(safeAfter.files().keySet());

            boolean changed = false;
            for (String path : paths) {
                SnapshotFile left = safeBefore.files().get(path);
                SnapshotFile right = safeAfter.files().get(path);
                if (left == null && right == null) continue;
                if (left == null) {
                    changed = true;
                    out.append("\nA ").append(path).append('\n');
                    appendFileDiff(out, "+", right);
                } else if (right == null) {
                    changed = true;
                    out.append("\nD ").append(path).append('\n');
                    appendFileDiff(out, "-", left);
                } else if (!left.hash().equals(right.hash())) {
                    changed = true;
                    out.append("\nM ").append(path).append('\n');
                    appendFileDiff(out, "-", left);
                    appendFileDiff(out, "+", right);
                }
            }
            if (!changed) {
                out.append("\n(no file changes detected)\n");
            }
            return out.toString();
        }

        private static void appendFileDiff(StringBuilder out, String prefix, SnapshotFile file) {
            if (file == null) return;
            if (!file.textCaptured()) {
                out.append(prefix)
                        .append(" [binary-or-large content omitted; ")
                        .append(file.bytes())
                        .append(" bytes; ")
                        .append(file.hash())
                        .append("]\n");
                return;
            }
            String text = sanitize(file.text());
            if (text.isEmpty()) {
                out.append(prefix).append(" [empty file]\n");
                return;
            }
            for (String line : text.split("\\R", -1)) {
                if (line.isEmpty()) continue;
                out.append(prefix).append(' ').append(line).append('\n');
            }
        }
    }

    private record SnapshotFile(long bytes, String hash, boolean textCaptured, String text) {
        private static final int MAX_TEXT_DIFF_BYTES = 64 * 1024;

        static SnapshotFile capture(Path path) throws IOException {
            byte[] bytes = Files.readAllBytes(path);
            boolean textCaptured = bytes.length <= MAX_TEXT_DIFF_BYTES && looksText(bytes);
            String text = textCaptured ? new String(bytes, StandardCharsets.UTF_8) : "";
            return new SnapshotFile(bytes.length, sha256(bytes), textCaptured, text);
        }

        private static boolean looksText(byte[] bytes) {
            if (bytes == null) return true;
            for (byte b : bytes) {
                if (b == 0) return false;
            }
            return true;
        }
    }
}
