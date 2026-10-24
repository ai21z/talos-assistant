package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.toolcall.ToolResultModelContextHandoff;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    // A period directly followed by a letter or digit belongs to the asked
    // phrase (api.stripe.com, notes.txt, 1.5). Other sentence punctuation ends
    // it. The phrase is bounded, because Java matches a repeated alternation
    // recursively, and it must end at sentence punctuation or the end of the
    // prompt, so an overlong phrase is never confirmed from a prefix.
    private static final String ASKED_PHRASE =
            "((?:[^?.!]|\\.(?=[\\p{L}\\p{N}])){1,200})(?=[?!]|\\.(?![\\p{L}\\p{N}])|$)";
    private static final Pattern WHETHER_CONTAINS_PROMPT = Pattern.compile(
            "(?i)\\bwhether\\s+(?:it|the document|the file)\\s+contains\\s+" + ASKED_PHRASE);
    private static final Pattern DOES_CONTAIN_PROMPT = Pattern.compile(
            "(?i)\\bdoes\\s+(?:it|the document|the file)\\s+contain\\s+" + ASKED_PHRASE);
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");
    private static final Pattern READ_LINE_NUMBER = Pattern.compile("^\\d+ \\| ");
    private static final String EXTRACTION_HEADER_PREFIX = "Extracted document text from ";

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
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) {
        if (loopResult == null) return answer;
        List<ToolCallLoop.ToolOutcome> deniedProtectedReads = loopResult.toolOutcomes().stream()
                .filter(ProtectedReadAnswerGuard::isDeniedProtectedReadOutcome)
                .toList();
        if (deniedProtectedReads.isEmpty()) return answer;
        List<ToolCallLoop.ToolOutcome> deniedAfterApprovedRead = deniedProtectedReads.stream()
                .filter(denied -> approvedReadOfSameFile(loopResult, denied, workspace))
                .toList();
        if (deniedAfterApprovedRead.isEmpty()) {
            List<String> otherApprovedReads = approvedProtectedReadPaths(loopResult, workspace);
            if (otherApprovedReads.isEmpty()) {
                return deniedProtectedReadSummary(deniedProtectedReads);
            }
            return deniedProtectedReadSummary(deniedProtectedReads)
                    .replace("[Approval blocked: protected content was not read]",
                            "[Approval blocked: protected content of the denied file(s) was not read]")
                    + "\n\nAn approved read in this turn returned content from: "
                    + String.join(", ", otherApprovedReads) + ".";
        }
        List<ToolCallLoop.ToolOutcome> unread = deniedProtectedReads.stream()
                .filter(denied -> !deniedAfterApprovedRead.contains(denied))
                .toList();
        StringBuilder note = new StringBuilder("[Protected read: approval was denied for one read of ");
        note.append(String.join(", ", deniedAfterApprovedRead.stream()
                .map(outcome -> workspaceDisplayPath(outcome, workspace))
                .distinct()
                .toList()));
        note.append(". Another approved read of that file in this turn returned its content.]");
        if (!unread.isEmpty()) {
            return note + "\n\n" + deniedProtectedReadSummary(unread);
        }
        return note + "\n\n" + (answer == null ? "" : answer);
    }

    public static String summarizeDeniedProtectedReadOutcomesIfNeeded(
            String answer,
            ToolCallLoop.LoopResult loopResult
    ) {
        return summarizeDeniedProtectedReadOutcomesIfNeeded(answer, loopResult, null);
    }

    private static boolean approvedReadOfSameFile(
            ToolCallLoop.LoopResult loopResult,
            ToolCallLoop.ToolOutcome denied,
            Path workspace
    ) {
        String deniedPath = canonicalDisplayPath(denied.pathHint());
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null || !outcome.success() || outcome.denied()) continue;
            if (!"read_file".equals(dev.talos.tools.ToolAliasPolicy.localCanonicalName(outcome.toolName()))) continue;
            if (!deniedPath.isBlank() && deniedPath.equals(canonicalDisplayPath(outcome.pathHint()))) return true;
            if (dev.talos.tools.WorkspacePathIdentity.matches(workspace, outcome.pathHint(), denied.pathHint())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> approvedProtectedReadPaths(ToolCallLoop.LoopResult loopResult, Path workspace) {
        return loopResult.toolOutcomes().stream()
                .filter(outcome -> outcome != null && outcome.success() && !outcome.denied()
                        && "read_file".equals(dev.talos.tools.ToolAliasPolicy.localCanonicalName(outcome.toolName()))
                        && dev.talos.runtime.policy.ProtectedContentPolicy.looksProtectedPathString(
                                canonicalDisplayPath(outcome.pathHint())))
                .map(outcome -> workspaceDisplayPath(outcome, workspace))
                .distinct()
                .toList();
    }

    /** A path inside the workspace is shown relative to it, so an answer does not reveal the home folder. */
    private static String workspaceDisplayPath(ToolCallLoop.ToolOutcome outcome, Path workspace) {
        String display = displayPath(outcome);
        if (workspace == null) return display;
        try {
            Path recorded = Path.of(outcome.pathHint().strip());
            Path root = workspace.toAbsolutePath().normalize();
            if (recorded.isAbsolute()) {
                Path normalized = recorded.normalize();
                if (dev.talos.tools.WorkspacePathIdentity.lexicallyWithin(root, normalized)) {
                    String relative = root.relativize(normalized).toString().replace('\\', '/');
                    if (!relative.isBlank()) return relative;
                }
            }
        } catch (RuntimeException unavailable) {
            return display;
        }
        return display;
    }

    private static String displayPath(ToolCallLoop.ToolOutcome outcome) {
        String path = canonicalDisplayPath(outcome.pathHint());
        return path.isBlank() ? outcome.toolName() : path;
    }

    private static String deniedProtectedReadSummary(List<ToolCallLoop.ToolOutcome> deniedProtectedReads) {

        StringBuilder out = new StringBuilder();
        out.append("[Approval blocked: protected content was not read]\n\n")
                .append("Protected content was not read because approval was denied for:\n");
        for (ToolCallLoop.ToolOutcome outcome : deniedProtectedReads) {
            out.append("- ")
                    .append(displayPath(outcome))
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
                    approvedPrivateDocumentContainmentAnswer(messages, loopResult, protectedReads);
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
            if (!"talos.read_file".equals(outcome.canonicalToolName())) continue;
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

    /**
     * A "Yes" is synthesized only when the asked phrase occurs, word for word
     * and in order, in the document content of a current approved protected
     * read. Only leading articles of the asked phrase are dropped. Extraction
     * headers, read line numbers, Talos notices and the bodies of ordinary
     * files read in the same turn are not document content. When nothing
     * matches, the caller keeps the evidence answer.
     */
    private static String approvedPrivateDocumentContainmentAnswer(
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            List<ToolCallLoop.ToolOutcome> protectedReads
    ) {
        if (loopResult == null || loopResult.readFileBodies() == null || loopResult.readFileBodies().isEmpty()) {
            return "";
        }
        String prompt =
                TurnTaskContractCapture.originalUserRequestOrResolve(messages);
        String phrase = containmentPhrase(prompt);
        if (phrase.isBlank()) return "";
        List<String> askedWords = askedPhraseWords(phrase);
        if (askedWords.isEmpty()) return "";
        for (String body : protectedReadBodies(loopResult, protectedReads)) {
            if (Collections.indexOfSubList(words(documentContent(body)), askedWords) >= 0) {
                return safeContainmentAnswer(phrase);
            }
        }
        return "";
    }

    private static List<String> protectedReadBodies(
            ToolCallLoop.LoopResult loopResult,
            List<ToolCallLoop.ToolOutcome> protectedReads
    ) {
        Set<String> keys = new HashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : protectedReads) {
            String key = readBodyKey(outcome.pathHint());
            if (!key.isBlank()) keys.add(key);
        }
        List<String> bodies = new ArrayList<>();
        for (Map.Entry<String, String> entry : loopResult.readFileBodies().entrySet()) {
            if (keys.contains(readBodyKey(entry.getKey()))) {
                bodies.add(entry.getValue());
            }
        }
        return bodies;
    }

    private static String readBodyKey(String path) {
        String key = canonicalDisplayPath(path);
        while (key.startsWith("./")) {
            key = key.substring(2);
        }
        return key.toLowerCase(Locale.ROOT);
    }

    private static String documentContent(String body) {
        if (body == null || body.isBlank()) return "";
        String text = body.replace("\r\n", "\n");
        if (text.startsWith(ToolResultModelContextHandoff.PROTECTED_READ_WITHHELD_NOTICE)
                || text.startsWith(ToolResultModelContextHandoff.PRIVATE_CONTENT_WITHHELD_NOTICE)) {
            return "";
        }
        if (text.startsWith("(file has ") && text.strip().endsWith("is past end)")) return "";
        boolean extraction = text.startsWith(EXTRACTION_HEADER_PREFIX);
        if (extraction) {
            int contentStart = text.indexOf("\n\n");
            if (contentStart < 0) return "";
            text = text.substring(contentStart + 2);
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("... [output truncated")) continue;
            if (line.startsWith("... (") && line.endsWith(" more lines)")) continue;
            Matcher numbered = READ_LINE_NUMBER.matcher(line);
            if (!extraction && numbered.lookingAt()) {
                out.append(line, numbered.end(), line.length());
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static List<String> askedPhraseWords(String phrase) {
        List<String> asked = new ArrayList<>(words(phrase));
        while (!asked.isEmpty() && isLeadingArticle(asked.get(0))) {
            asked.remove(0);
        }
        return asked;
    }

    private static boolean isLeadingArticle(String word) {
        return "a".equals(word) || "an".equals(word) || "the".equals(word);
    }

    private static List<String> words(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String piece : WORD_SEPARATOR.split(normalized)) {
            if (!piece.isEmpty()) out.add(piece);
        }
        return out;
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
        if (!"talos.read_file".equals(outcome.canonicalToolName())) return false;
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
        String key = readBodyKey(pathHint);
        if (key.isBlank()) return "";
        for (Map.Entry<String, String> entry : loopResult.readFileBodies().entrySet()) {
            if (key.equals(readBodyKey(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return "";
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

}
