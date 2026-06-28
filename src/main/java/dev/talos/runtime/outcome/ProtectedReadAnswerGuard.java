package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolError;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards final answers after approved protected reads so stale private content
 * from conversation history cannot substitute for current-turn evidence.
 */
public final class ProtectedReadAnswerGuard {
    private static final Pattern ENV_ASSIGNMENT = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Z][A-Z0-9_]{2,}\\s*=\\s*[^\\s`'\"<>]+)");
    private static final Pattern WHETHER_CONTAINS_PROMPT = Pattern.compile(
            "(?i)\\bwhether\\s+(?:it|the document|the file)\\s+contains\\s+([^?.!]+)");
    private static final Pattern DOES_CONTAIN_PROMPT = Pattern.compile(
            "(?i)\\bdoes\\s+(?:it|the document|the file)\\s+contain\\s+([^?.!]+)");

    private ProtectedReadAnswerGuard() {
    }

    public record PostconditionResult(
            String answer,
            boolean repaired
    ) {
        public PostconditionResult {
            answer = answer == null ? "" : answer;
        }
    }

    public static String summarizeDeniedProtectedReadOutcomesIfNeeded(
            String answer,
            ToolCallLoop.LoopResult loopResult
    ) {
        if (loopResult == null) return answer;
        List<ToolCallLoop.ToolOutcome> deniedProtectedReads = loopResult.toolOutcomes().stream()
                .filter(ProtectedReadAnswerGuard::isDeniedProtectedReadOutcome)
                .toList();
        if (deniedProtectedReads.isEmpty()) return answer;

        StringBuilder out = new StringBuilder();
        out.append("[Approval blocked: protected content was not read]\n\n")
                .append("Protected content was not read because approval was denied for:\n");
        for (ToolCallLoop.ToolOutcome outcome : deniedProtectedReads) {
            String path = canonicalDisplayPath(outcome.pathHint());
            out.append("- ")
                    .append(path.isBlank() ? outcome.toolName() : path)
                    .append(": approval denied\n");
        }
        out.append("\nNo protected file content was shown. ")
                .append("Approve the protected read if you want Talos to inspect it.");
        return out.toString().stripTrailing();
    }

    public static String suppressProtectedHistoryContentIfNeeded(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) {
        if (answer == null || answer.isBlank()) return answer == null ? "" : answer;
        if (hasSuccessfulCurrentProtectedRead(loopResult, workspace)) return answer;
        for (String snippet : priorProtectedSnippets(messages)) {
            if (answerContainsSnippet(answer, snippet)) {
                LocalTurnTraceCapture.warning(
                        "PROTECTED_HISTORY_SUPPRESSED",
                        "Suppressed answer text matching protected content from prior conversation history "
                                + "without a current-turn approved protected read.");
                return "I did not show protected content from an earlier approved read because this turn "
                        + "did not request and complete a fresh protected read approval.";
            }
        }
        return answer;
    }

    public static PostconditionResult enforceApprovedProtectedReadPostcondition(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) {
        return enforceApprovedProtectedReadPostcondition(answer, loopResult, workspace, List.of());
    }

    public static PostconditionResult enforceApprovedProtectedReadPostcondition(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            List<ChatMessage> messages
    ) {
        List<ToolCallLoop.ToolOutcome> protectedReads = successfulCurrentProtectedReadOutcomes(
                loopResult,
                workspace);
        if (protectedReads.isEmpty()) {
            return new PostconditionResult(answer, false);
        }

        String status = "PASSED";
        String reason = "approved protected read answer used current read evidence";
        String current = answer == null ? "" : answer;
        boolean repaired = false;
        // T760: blank answers and refusals are distinct conditions. Both are
        // repaired with the approved read evidence (shipping an empty final
        // answer after the user granted a protected-read approval is worse),
        // but the trace reason must be truthful about which one happened.
        boolean blankAnswer = current.isBlank();
        if ((blankAnswer || isProtectedReadRefusal(current))
                && !answerContainsCurrentProtectedReadEvidence(current, protectedReads)) {
            String repairedContainmentAnswer =
                    approvedPrivateDocumentContainmentAnswer(messages, loopResult);
            current = repairedContainmentAnswer.isBlank()
                    ? approvedProtectedReadEvidenceAnswer(protectedReads)
                    : repairedContainmentAnswer;
            status = "REPAIRED";
            if (!repairedContainmentAnswer.isBlank()) {
                reason = "blocked/refusal answer replaced with approved private-document containment answer";
            } else if (blankAnswer) {
                reason = "blank model answer replaced with current approved read evidence";
            } else {
                reason = "generic model refusal replaced with current approved read evidence";
            }
            repaired = true;
        }
        LocalTurnTraceCapture.recordProtectedReadPostcondition(
                status,
                protectedReads.stream().map(ToolCallLoop.ToolOutcome::pathHint).toList(),
                reason);
        return new PostconditionResult(current, repaired);
    }

    private static boolean hasSuccessfulCurrentProtectedRead(
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) {
        return !successfulCurrentProtectedReadOutcomes(loopResult, workspace).isEmpty();
    }

    private static List<ToolCallLoop.ToolOutcome> successfulCurrentProtectedReadOutcomes(
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return List.of();
        List<ToolCallLoop.ToolOutcome> out = new ArrayList<>();
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null) continue;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!outcome.success() || outcome.denied()) continue;
            if (ProtectedPathPolicy.classify(workspace, outcome.pathHint()).protectedPath()
                    || looksProtectedPathHint(outcome.pathHint())
                    || isApprovedDocumentExtractionRead(loopResult, outcome)) {
                out.add(outcome);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Refusal markers are matched within the ANSWER HEAD only (T760).
     * Refusals are characteristically answer-initial, and runtime-injected
     * markers ("approval blocked", "protected content was redacted") always
     * sit at offset 0 of runtime-replaced answers, so head scoping cannot
     * miss them. Whole-answer matching destroyed long grounded answers whose
     * tail carried a legitimate caveat ("the raw value cannot be shared").
     * A refusal buried past the head passes through - an answer-quality
     * trade only; disclosure control lives in the suppression/handoff/
     * redaction layers, not here.
     */
    private static final int REFUSAL_HEAD_CHARS = 240;

    private static boolean isProtectedReadRefusal(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String head = answer.strip();
        if (head.length() > REFUSAL_HEAD_CHARS) {
            head = head.substring(0, REFUSAL_HEAD_CHARS);
        }
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.contains("can't provide")
                || lower.contains("cannot provide")
                || lower.contains("can't share")
                || lower.contains("cannot share")
                || lower.contains("can't reveal")
                || lower.contains("cannot reveal")
                || lower.contains("can't disclose")
                || lower.contains("cannot disclose")
                || lower.contains("not allowed to provide")
                || lower.contains("not able to provide")
                || lower.contains("can't assist with that")
                || lower.contains("cannot assist with that")
                || lower.contains("can't access local files")
                || lower.contains("cannot access local files")
                || lower.contains("approval blocked")
                || lower.contains("redacted from history")
                || lower.contains("protected content was redacted")
                || (lower.contains("i'm sorry") && (lower.contains("can't") || lower.contains("cannot")));
    }

    private static boolean answerContainsCurrentProtectedReadEvidence(
            String answer,
            List<ToolCallLoop.ToolOutcome> protectedReads
    ) {
        if (answer == null || answer.isBlank()) return false;
        String normalizedAnswer = normalizeSensitiveSnippet(answer).toLowerCase(Locale.ROOT);
        for (ToolCallLoop.ToolOutcome outcome : protectedReads) {
            String evidence = protectedReadEvidenceSummary(outcome.summary());
            if (evidence.length() < 4) continue;
            String normalizedEvidence = normalizeSensitiveSnippet(evidence).toLowerCase(Locale.ROOT);
            if (!normalizedEvidence.isBlank() && normalizedAnswer.contains(normalizedEvidence)) {
                return true;
            }
        }
        return false;
    }

    private static String approvedProtectedReadEvidenceAnswer(
            List<ToolCallLoop.ToolOutcome> protectedReads
    ) {
        StringBuilder out = new StringBuilder();
        out.append("[Approved protected read postcondition: model refusal replaced with current approved read evidence.]")
                .append("\n\n")
                .append("Current approved protected read evidence:");
        int limit = Math.min(5, protectedReads.size());
        for (ToolCallLoop.ToolOutcome outcome : protectedReads.subList(0, limit)) {
            out.append("\n- ")
                    .append(outcome.pathHint().isBlank() ? "<protected file>" : outcome.pathHint())
                    .append(": ")
                    .append(protectedReadEvidenceSummary(outcome.summary()));
        }
        if (protectedReads.size() > limit) {
            out.append("\n- ... ").append(protectedReads.size() - limit).append(" more protected reads");
        }
        return out.toString();
    }

    private static String approvedPrivateDocumentContainmentAnswer(
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult
    ) {
        if (loopResult == null || loopResult.readFileBodies() == null || loopResult.readFileBodies().isEmpty()) {
            return "";
        }
        String prompt = latestNaturalLanguageUserPrompt(messages);
        String phrase = containmentPhrase(prompt);
        if (phrase.isBlank()) return "";
        for (String body : loopResult.readFileBodies().values()) {
            if (documentBodyContainsRequestedPhrase(body, phrase)) {
                return safeContainmentAnswer(phrase);
            }
        }
        return "";
    }

    private static String canonicalDisplayPath(String pathHint) {
        return pathHint == null ? "" : pathHint.strip().replace('\\', '/');
    }

    private static boolean isApprovedDocumentExtractionRead(
            ToolCallLoop.LoopResult loopResult,
            ToolCallLoop.ToolOutcome outcome
    ) {
        if (loopResult == null || outcome == null || outcome.pathHint() == null || outcome.pathHint().isBlank()) {
            return false;
        }
        String path = canonicalDisplayPath(outcome.pathHint()).toLowerCase(Locale.ROOT);
        if (!(path.endsWith(".pdf")
                || path.endsWith(".docx")
                || path.endsWith(".xls")
                || path.endsWith(".xlsx"))) {
            return false;
        }
        String readBody = currentReadBody(loopResult, outcome.pathHint());
        if (readBody.isBlank()) return false;
        String lower = readBody.toLowerCase(Locale.ROOT);
        return lower.contains("extracted document text from")
                || lower.contains("extractor:");
    }

    private static boolean isDeniedProtectedReadOutcome(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null || outcome.mutating() || outcome.success() || !outcome.denied()) {
            return false;
        }
        if (!"talos.read_file".equals(outcome.toolName())) return false;
        if (!ToolError.DENIED.equals(outcome.errorCode())) return false;
        return isUserApprovalDeniedOutcome(outcome);
    }

    private static boolean isUserApprovalDeniedOutcome(ToolCallLoop.ToolOutcome outcome) {
        return outcome != null
                && outcome.failureReason() == dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED;
    }

    private static String protectedReadEvidenceSummary(String summary) {
        String value = singleLine(summary);
        if (value.isBlank()) return "content was read, but no short summary was available";
        String withoutLineNumber = value.replaceFirst("^\\d+\\s*\\|\\s*", "");
        return withoutLineNumber.isBlank() ? value : withoutLineNumber;
    }

    private static boolean looksProtectedPathHint(String pathHint) {
        // T759: delegates to the single canonical classifier (the local
        // substring copy false-positived on names like tokenizer.java).
        // Delta vs the old copy: CONTROL-kind hints (.git, .gnupg) now count
        // as protected too - consistent with the workspace-resolved branch
        // beside this check, which already included CONTROL.
        return dev.talos.runtime.policy.ProtectedContentPolicy.looksProtectedPathString(pathHint);
    }

    private static Set<String> priorProtectedSnippets(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (ChatMessage message : messages) {
            if (message == null || !"assistant".equals(message.role())) continue;
            String content = message.content();
            if (content == null || content.isBlank()) continue;
            if (!looksLikeProtectedHistoryAnswer(content)) continue;
            Matcher matcher = ENV_ASSIGNMENT.matcher(content);
            while (matcher.find()) {
                String snippet = normalizeSensitiveSnippet(matcher.group(1));
                if (snippet.length() >= 8) out.add(snippet);
            }
        }
        return out;
    }

    private static boolean looksLikeProtectedHistoryAnswer(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains(".env")
                || lower.contains("approved file")
                || lower.contains("protected")
                || lower.contains("secret")
                || lower.contains("token")
                || lower.contains("password")
                || lower.contains("credential");
    }

    private static boolean answerContainsSnippet(String answer, String snippet) {
        String normalizedAnswer = normalizeSensitiveSnippet(answer).toLowerCase(Locale.ROOT);
        String normalizedSnippet = normalizeSensitiveSnippet(snippet).toLowerCase(Locale.ROOT);
        return normalizedSnippet.length() >= 8 && normalizedAnswer.contains(normalizedSnippet);
    }

    private static String latestNaturalLanguageUserPrompt(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) continue;
            String content = message.content() == null ? "" : message.content().strip();
            if (content.isBlank()) continue;
            if (content.startsWith("[tool_result:")) continue;
            return content;
        }
        return "";
    }

    private static String containmentPhrase(String prompt) {
        if (prompt == null || prompt.isBlank()) return "";
        Matcher whetherMatcher = WHETHER_CONTAINS_PROMPT.matcher(prompt);
        if (whetherMatcher.find()) {
            return cleanContainmentPhrase(whetherMatcher.group(1));
        }
        Matcher doesMatcher = DOES_CONTAIN_PROMPT.matcher(prompt);
        if (doesMatcher.find()) {
            return cleanContainmentPhrase(doesMatcher.group(1));
        }
        return "";
    }

    private static String cleanContainmentPhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) return "";
        String cleaned = phrase.strip();
        cleaned = cleaned.replaceAll("(?i)\\bdo not print.*$", "").strip();
        while (!cleaned.isEmpty() && ".,;:!?".indexOf(cleaned.charAt(cleaned.length() - 1)) >= 0) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).strip();
        }
        return cleaned;
    }

    private static boolean documentBodyContainsRequestedPhrase(String body, String phrase) {
        if (body == null || body.isBlank() || phrase == null || phrase.isBlank()) return false;
        String normalizedBody = normalizeSensitiveSnippet(body).toLowerCase(Locale.ROOT);
        List<String> requiredTokens = significantTokens(phrase);
        if (requiredTokens.isEmpty()) return false;
        for (String token : requiredTokens) {
            if (!normalizedBody.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> significantTokens(String phrase) {
        String[] pieces = normalizeSensitiveSnippet(phrase).toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String> tokens = new ArrayList<>();
        for (String piece : pieces) {
            if (piece.isBlank()) continue;
            if (piece.length() <= 1) continue;
            if (isContainmentStopWord(piece)) continue;
            tokens.add(piece);
        }
        return tokens;
    }

    private static boolean isContainmentStopWord(String token) {
        return switch (token) {
            case "a", "an", "the", "it", "this", "that", "document", "file", "value", "raw", "contains", "contain" -> true;
            default -> false;
        };
    }

    private static String safeContainmentAnswer(String phrase) {
        String normalizedPhrase = cleanContainmentPhrase(phrase);
        if (normalizedPhrase.isBlank()) {
            return "Yes. The document contains the requested protected value, but the raw value is not printed.";
        }
        String suffix = normalizedPhrase.toLowerCase(Locale.ROOT).contains("name")
                ? "the name is not printed."
                : "the raw value is not printed.";
        return "Yes. The document contains " + normalizedPhrase + ", but " + suffix;
    }

    private static String currentReadBody(ToolCallLoop.LoopResult loopResult, String pathHint) {
        if (loopResult == null || loopResult.readFileBodies() == null || loopResult.readFileBodies().isEmpty()) {
            return "";
        }
        return loopResult.readFileBodies().getOrDefault(
                canonicalDisplayPath(pathHint).toLowerCase(Locale.ROOT),
                loopResult.readFileBodies().getOrDefault(canonicalDisplayPath(pathHint), ""));
    }

    private static String normalizeSensitiveSnippet(String value) {
        if (value == null) return "";
        String stripped = value.strip();
        while (!stripped.isEmpty() && ".,;:!?)]}".indexOf(stripped.charAt(stripped.length() - 1)) >= 0) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped.replaceAll("\\s+", " ");
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) return "no additional detail";
        String line = value.replace('\n', ' ').replace('\r', ' ').strip();
        return line.length() <= 240 ? line : line.substring(0, 237) + "...";
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }
}
