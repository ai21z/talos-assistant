package dev.talos.runtime.policy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.security.Redactor;
import dev.talos.core.util.Hash;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.intent.WorkStatusRequest;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Immediate source inspection from exact, privacy-admitted current-turn captures. No source is persisted here. */
public final class ProjectHandoffSourceAccess {
    public static final String TAG = "project-handoff-advisory";
    private static final String TRACE = "projectHandoff.sourceTraceId";
    private static final String TURN = "projectHandoff.sourceTurnNumber";
    private static final String REQUEST = "projectHandoff.sourceRequestSha256";
    private static final String INPUT = "projectHandoff.sourceInputSha256";
    private static final int MAX_CAPTURES = 16;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Redactor REDACTOR = new Redactor();
    private static final String UNAVAILABLE = "\nHistorical handoff sources are unavailable for this exact turn. "
            + "Source access is limited to the current captured turn and remains subject to privacy and presentation restrictions.\n";

    private ProjectHandoffSourceAccess() {}

    /** Binds the next advisory request to runtime identity and the exact admitted serialized snapshot. */
    public static void bind(ProjectHandoffPolicy.References references) {
        if (!LocalTurnTraceCapture.isActive() || references == null) return;
        String data = input(references);
        PromptDebugCapture.putTurnDiagnostic(TRACE, LocalTurnTraceCapture.currentTraceId());
        PromptDebugCapture.putTurnDiagnostic(TURN, Integer.toString(LocalTurnTraceCapture.currentTurnNumber()));
        PromptDebugCapture.putTurnDiagnostic(REQUEST, digest(references.currentRequest()));
        PromptDebugCapture.putTurnDiagnostic(INPUT, digest(data));
    }

    public static boolean available(ProjectHandoffPolicy.References references) {
        return available(references, UnaryOperator.identity());
    }

    /** The optional projection accounts for the caller's additional configured output restrictions. */
    public static boolean available(ProjectHandoffPolicy.References references, UnaryOperator<String> projection) {
        if (references == null || !LocalTurnTraceCapture.isActive()
                || !permitted(references.restrictions())) return false;
        try {
            var resolved = resolve(LocalTurnTraceCapture.currentTraceId(), LocalTurnTraceCapture.currentTurnNumber(),
                    references.currentRequest());
            return resolved.isPresent() && resolved.get().data().equals(input(references))
                    && resolved.get().references().entries().equals(references.entries())
                    && safelyPresentable(references, projection);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Empty means this is not a handoff. An unavailable handoff is reported explicitly, without another lookup. */
    public static Optional<String> render(TurnRecord turn, LocalTurnTrace pairedTrace,
                                         UnaryOperator<String> projection) {
        if (turn == null || !ProjectHandoffPolicy.required(WorkStatusRequest.parse(turn.userInput())))
            return Optional.empty();
        try {
            if (!validAssessment(turn, pairedTrace)) return Optional.of(UNAVAILABLE);
            var resolved = resolve(turn.traceId(), turn.turnNumber(), turn.userInput());
            if (resolved.isEmpty() || !safelyPresentable(resolved.get().references(), projection))
                return Optional.of(UNAVAILABLE);
            return Optional.of(renderSources(resolved.get().references()));
        } catch (RuntimeException unavailable) {
            return Optional.of(UNAVAILABLE);
        }
    }

    public static Optional<String> render(TurnRecord turn, LocalTurnTrace pairedTrace) {
        return render(turn, pairedTrace, UnaryOperator.identity());
    }

    private static Optional<Resolved> resolve(String traceId, int turnNumber, String request) {
        if (traceId == null || traceId.isBlank() || turnNumber <= 0
                || !permitted(ContentRestrictionScope.current())) return Optional.empty();
        List<PromptDebugSnapshot> captures = PromptDebugCapture.history();
        if (captures.isEmpty() || captures.size() > MAX_CAPTURES) return Optional.empty();
        Resolved agreed = null;
        for (var capture : captures) {
            if (!capture.controls().debugTags().contains(TAG)
                    || !traceId.equals(capture.diagnostics().get(TRACE))) continue;
            var controls = capture.controls();
            if (!Integer.toString(turnNumber).equals(capture.diagnostics().get(TURN))
                    || !digest(request).equals(capture.diagnostics().get(REQUEST))
                    || !permitted(capture.contentRestrictions())
                    || controls.toolChoice() != ToolChoiceMode.NONE || !controls.namedTool().isEmpty()
                    || controls.toolAuthorityMode() != ProviderResponseCapture.ToolAuthorityMode.NONE
                    || controls.responseFormat() != ResponseFormatMode.JSON_SCHEMA
                    || !controls.singleAttempt() || controls.maxOutputTokens() != 512
                    || !capture.tools().isEmpty() || capture.messages().size() != 2)
                return Optional.empty();
            var policy = capture.messages().getFirst();
            var dataMessage = capture.messages().getLast();
            if (!"system".equals(policy.role()) || !"user".equals(dataMessage.role())
                    || capture.messages().stream().anyMatch(message -> message.hasNativeToolCalls()
                        || message.toolCallId() != null && !message.toolCallId().isBlank()
                        || !permitted(message.contentRestrictions()))) return Optional.empty();
            String data = dataMessage.content();
            if (data == null || data.length() > ProjectHandoffPolicy.MAX_DATA_CHARS
                    || !digest(data).equals(capture.diagnostics().get(INPUT))) return Optional.empty();
            var references = decode(data, request, capture.contentRestrictions());
            if (references == null) return Optional.empty();
            var current = new Resolved(data, references);
            if (agreed != null && !agreed.data().equals(data)) return Optional.empty();
            agreed = current;
        }
        return Optional.ofNullable(agreed);
    }

    private static ProjectHandoffPolicy.References decode(String data, String request, ContentRestrictions restrictions) {
        try {
            JsonNode root = JSON.readTree(data);
            if (root == null || !root.isObject() || root.size() != 4
                    || !root.path("current_request").isTextual()
                    || !request.equals(root.path("current_request").textValue())
                    || !root.path("omitted").isBoolean() || !root.path("requested_results").isArray()) return null;
            JsonNode entries = root.path("references");
            if (!entries.isArray() || entries.isEmpty() || entries.size() > ProjectHandoffPolicy.MAX_PAIRS * 2)
                return null;
            var references = new ArrayList<ProjectHandoffPolicy.Reference>();
            var seen = new HashSet<String>();
            for (var entry : entries) {
                if (!entry.isObject() || entry.size() != 3 || !entry.path("id").isTextual()
                        || !entry.path("speaker").isTextual() || !entry.path("text").isTextual()) return null;
                String id = entry.path("id").textValue();
                String speaker = entry.path("speaker").textValue();
                String text = entry.path("text").textValue();
                if (!id.matches("reference-[0-9]{1,10}") || !seen.add(id)
                        || !("user".equals(speaker) || "assistant".equals(speaker))
                        || text.isBlank() || text.length() > ProjectHandoffPolicy.MAX_MESSAGE_CHARS) return null;
                references.add(new ProjectHandoffPolicy.Reference(id, speaker, text));
            }
            var result = new ProjectHandoffPolicy.References(references, root.path("omitted").booleanValue(), restrictions, request);
            // Runtime reconstruction checks the original request, result inventory and closed input shape.
            return root.equals(JSON.readTree(input(result))) ? result : null;
        } catch (java.io.IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean validAssessment(TurnRecord turn, LocalTurnTrace trace) {
        if (trace == null || turn.traceId().isBlank() || !turn.traceId().equals(trace.traceId())
                || turn.turnNumber() != trace.turnNumber() || !"ok".equals(turn.status())
                || turn.assistantText().isBlank()) return false;
        boolean found = false;
        for (var event : trace.events()) {
            if (!"WORK_STATUS_ASSESSED".equals(event.type())) continue;
            var data = event.data();
            if (!digest(turn.userInput().strip()).equals(data.get("requestHash"))
                    || !digest(turn.assistantText().strip()).equals(data.get("answerHash"))
                    || !"COVERED".equals(data.get("coverage"))
                    || !(data.get("roles") instanceof List<?> roles) || !roles.contains("PROJECT_HANDOFF")) return false;
            found = true;
        }
        return found;
    }

    private static boolean safelyPresentable(ProjectHandoffPolicy.References references, UnaryOperator<String> projection) {
        if (!permitted(references.restrictions())) return false;
        for (var source : references.entries()) {
            String text = source.text();
            // Test raw text before literal escaping so escaping cannot conceal a privacy-sensitive token.
            if (!text.equals(ProtectedContentPolicy.sanitizeText(text))
                    || !text.equals(REDACTOR.redactBlock(text)) || !text.equals(project(projection, text))) return false;
            String literal = ProjectHandoffPolicy.literal(text);
            if (!literal.equals(REDACTOR.redactBlock(literal)) || !literal.equals(project(projection, literal))) return false;
        }
        String rendered = renderSources(references);
        return rendered.equals(REDACTOR.redactBlock(rendered)) && rendered.equals(project(projection, rendered));
    }

    private static String renderSources(ProjectHandoffPolicy.References references) {
        var answer = new StringBuilder("\nHistorical handoff sources for this captured turn\n")
                .append("Complete admitted statements are shown as quoted historical data. Their truth, relevance and current applicability remain unverified.\n")
                .append("Access expires when the next natural-language turn starts. No sources are restored from saved history.\n")
                .append("Literal {U+XXXX} notation represents escaped source characters.\n");
        for (var source : references.entries()) {
            answer.append('\n').append(source.id()).append(". Earlier ").append(source.speaker())
                    .append(" statement\n").append(ProjectHandoffPolicy.literal(source.text())).append('\n');
        }
        if (references.omitted())
            answer.append("\nOther conversation material was omitted by admission or retention bounds.\n");
        return answer.toString().strip();
    }

    private static boolean permitted(ContentRestrictions restrictions) {
        var effective = restrictions.intersect(ContentRestrictionScope.current());
        return effective.artifactAllowed() && effective.historyAllowed();
    }

    private static String input(ProjectHandoffPolicy.References references) {
        return references.providerMessages().getLast().content();
    }

    private static String project(UnaryOperator<String> projection, String value) {
        return Objects.requireNonNull(projection).apply(value);
    }

    private static String digest(String text) {
        return Hash.sha256Hex(Objects.requireNonNull(text).getBytes(StandardCharsets.UTF_8));
    }

    private record Resolved(String data, ProjectHandoffPolicy.References references) {}
}
