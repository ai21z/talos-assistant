package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustClaimsHonestyTest {

    private static final String ANTI_OVERCLAIM_BOUNDARY =
            "strongest for file-mutation turns";
    private static final String SECRET_REDACTION_BOUNDARY =
            "Secret redaction is best-effort";
    private static final String COMMAND_OUTPUT_BOUNDARY =
            "run_command stdout and stderr pass through the model-context handoff boundary";
    private static final String WINDOWS_PROTECTED_PATH_BOUNDARY =
            "not a complete Windows path-security proof";
    private static final String CHAT_LOCALHOST_BOUNDARY =
            "Chat model endpoints are localhost-gated by default";
    private static final String MASTER_KEY_BOUNDARY =
            "not hardware-backed custody";
    private static final String TRACE_INTEGRITY_BOUNDARY =
            "not tamper-evident";
    private static final String RAG_LOCAL_BOUNDARY =
            "RAG in Talos means the local Lucene index and retrieval pipeline";
    private static final String VECTOR_OPTIONAL_BOUNDARY =
            "Vector retrieval requires a local embedding endpoint";
    private static final String ACCEPTED_MODEL_BOUNDARY =
            "Accepted beta stability profiles are qwen2.5-coder-14b and gpt-oss-20b";
    private static final String EXPERIMENTAL_MODEL_BOUNDARY =
            "Qwen3.6-VibeForged and DeepSeek-Coder-V2-Lite profiles are experimental selectable profiles";
    private static final String GPT_OSS_LOCAL_GGUF_BOUNDARY =
            "gpt-oss-20b requires a concrete local GGUF model path";
    private static final String DEEPSEEK_TOOL_MODE_BOUNDARY =
            "DeepSeek-Coder-V2-Lite uses text/tool-prompt mode";

    private static final Pattern MISUSED_AGENTHALLU_STAT =
            Pattern.compile("11\\.6%.{0,120}(detect|detection|solution|solves|prove|proves)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AGENTHALLU_PRESCRIBES_TALOS =
            Pattern.compile("AgentHallu.{0,160}(prescribe|prescribes|solution|mechanism|Talos)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern UNQUALIFIED_NO_COMPETITOR =
            Pattern.compile("""
                    \\b(?:no\\s+competitor
                    |no\\s+other\\s+(?:assistant|agent|tool|coding\\s+tool)
                    |Talos\\b.{0,80}\\bis\\s+(?:the\\s+)?only\\s+(?:assistant|agent|tool|coding\\s+tool)
                    |Talos\\b.{0,80}\\bis\\s+(?:the\\s+)?only\\s+(?:local|agentic|coding|ai)\\s+(?:assistant|agent|tool)
                    |Talos\\b.{0,80}\\bis\\s+(?:the\\s+)?only\\s+agentic\\s+coding\\s+tool
                    |nobody\\s+else
                    |unlike\\s+every\\s+competitor)\\b
                    """, Pattern.CASE_INSENSITIVE | Pattern.COMMENTS);
    private static final Pattern UNBOUNDED_VECTOR_CLAIM =
            Pattern.compile("""
                    \\b(?:vector\\s+retrieval\\s+is\\s+always\\s+active
                    |always\\s+uses\\s+vectors
                    |vectors\\s+are\\s+always\\s+enabled
                    |requires\\s+a\\s+vector\\s+database
                    |cloud\\s+vector\\s+database)\\b
                    """, Pattern.CASE_INSENSITIVE | Pattern.COMMENTS);
    private static final Pattern COMPLETE_SECRET_CLAIM =
            Pattern.compile("""
                    \\b(?:detects\\s+all\\s+secrets
                    |guaranteed\\s+secret\\s+redaction
                    |complete\\s+credential\\s+detection
                    |complete\\s+PII\\s+detection
                    |secret-proof
                    |PII-proof)\\b
                    """, Pattern.CASE_INSENSITIVE | Pattern.COMMENTS);
    private static final Pattern DEEPSEEK_NATIVE_TOOL_CAPABLE_CLAIM =
            Pattern.compile("DeepSeek.{0,160}(native-tool capable|native tool capable|native/default tool capable|works natively)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PUBLIC_DOC_TICKET_ID = Pattern.compile("\\bT\\d{3,}\\b");

    @Test
    void maintainedDocsBoundTrustClaimsToCurrentProductLimits() throws Exception {
        String docs = publicDocs();
        for (String required : List.of(
                ANTI_OVERCLAIM_BOUNDARY,
                SECRET_REDACTION_BOUNDARY,
                COMMAND_OUTPUT_BOUNDARY,
                WINDOWS_PROTECTED_PATH_BOUNDARY,
                CHAT_LOCALHOST_BOUNDARY,
                MASTER_KEY_BOUNDARY,
                TRACE_INTEGRITY_BOUNDARY,
                RAG_LOCAL_BOUNDARY,
                VECTOR_OPTIONAL_BOUNDARY,
                ACCEPTED_MODEL_BOUNDARY,
                EXPERIMENTAL_MODEL_BOUNDARY,
                GPT_OSS_LOCAL_GGUF_BOUNDARY,
                DEEPSEEK_TOOL_MODE_BOUNDARY)) {
            assertContainsNormalized(docs, required);
        }
    }

    @Test
    void publicPitchSurfacesAvoidUnboundedPositioningOverclaims() throws Exception {
        String publicPitch = read("README.md") + "\n" + readMarkdownTree("docs") + "\n" + read("site/index.html");

        assertDoesNotContainIgnoringCase(publicPitch, "provable agent");
        assertDoesNotContainIgnoringCase(publicPitch, "makes the model provable");
        assertDoesNotContainIgnoringCase(publicPitch, "tamper-proof");
        assertDoesNotContainIgnoringCase(publicPitch, "tamper proof");
        assertPatternAbsent(publicPitch, UNQUALIFIED_NO_COMPETITOR);
        assertPatternAbsent(publicPitch, MISUSED_AGENTHALLU_STAT);
        assertPatternAbsent(publicPitch, AGENTHALLU_PRESCRIBES_TALOS);
        assertPatternAbsent(publicPitch, UNBOUNDED_VECTOR_CLAIM);
        assertPatternAbsent(publicPitch, COMPLETE_SECRET_CLAIM);
        assertPatternAbsent(publicPitch, DEEPSEEK_NATIVE_TOOL_CAPABLE_CLAIM);
    }

    @Test
    void publicDocsDoNotLeakTicketArchiveOrInternalAssistantNames() throws Exception {
        String docs = readMarkdownTree("docs");

        assertFalse(PUBLIC_DOC_TICKET_ID.matcher(docs).find(), "public docs must not leak ticket IDs");
        assertDoesNotContainIgnoringCase(docs, "work-cycle-docs/tickets");
        assertDoesNotContainIgnoringCase(docs, "tickets/done");
        assertDoesNotContainIgnoringCase(docs, "tickets/open");
        assertDoesNotContainIgnoringCase(docs, "codex");
        assertDoesNotContainIgnoringCase(docs, "claude");
        assertDoesNotContainIgnoringCase(docs, "copilot");
    }

    @Test
    void modelSetupDocsKeepAcceptedAndExperimentalProfilesSeparated() throws Exception {
        String modelProfiles = read("docs/reference/model-profiles.md");

        assertContainsNormalized(modelProfiles, ACCEPTED_MODEL_BOUNDARY);
        assertContainsNormalized(modelProfiles, EXPERIMENTAL_MODEL_BOUNDARY);
        assertContainsNormalized(modelProfiles, GPT_OSS_LOCAL_GGUF_BOUNDARY);
        assertContainsNormalized(modelProfiles, DEEPSEEK_TOOL_MODE_BOUNDARY);
        assertContains(modelProfiles, "talos setup models --profile qwen2.5-coder-14b");
        assertContains(modelProfiles, "talos setup models --profile gpt-oss-20b");
        assertContains(modelProfiles, "talos doctor --start");
        assertContains(modelProfiles, "Save the talos doctor --start output as evidence before calling a profile verified on a new machine.");
    }

    @Test
    void competitorExclusivityGuardCatchesBroaderUnqualifiedClaims() {
        for (String unsafe : List.of(
                "No competitor does this.",
                "No other assistant has this trust gate.",
                "No other agent catches false file edits.",
                "Talos is the only assistant with this guarantee.",
                "Talos is the only agentic coding tool with this behavior.",
                "Nobody else verifies file mutation claims.",
                "Unlike every competitor, Talos proves edits.")) {
            assertTrue(UNQUALIFIED_NO_COMPETITOR.matcher(unsafe).find(),
                    "Guard should catch unqualified competitor exclusivity claim: " + unsafe);
        }

        for (String safe : List.of(
                "No inspected source-level tool had the same post-apply gate.",
                "The claim is bounded to inspected files and June 2026.",
                "Talos has a narrow, time-bounded differentiator.",
                "Talos is not the only tool with deterministic safety checks.",
                "The read-only tool surface is selected for inspection turns.")) {
            assertFalse(UNQUALIFIED_NO_COMPETITOR.matcher(safe).find(),
                    "Guard should allow bounded competitor evidence wording: " + safe);
        }
    }

    private static String publicDocs() throws Exception {
        return read("README.md") + "\n"
                + readMarkdownTree("docs");
    }

    private static String read(String first, String... more) throws Exception {
        return Files.readString(Path.of(first, more));
    }

    private static String readMarkdownTree(String directory) throws Exception {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(Path.of(directory))) {
            files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
        StringBuilder sb = new StringBuilder();
        for (Path file : files) {
            sb.append(Files.readString(file)).append('\n');
        }
        return sb.toString();
    }

    private static void assertContains(String text, String expected) {
        assertTrue(text.contains(expected), "Missing required trust disclosure: " + expected);
    }

    private static void assertContainsNormalized(String text, String expected) {
        String normalizedText = text.replaceAll("\\s+", " ").trim();
        String normalizedExpected = expected.replaceAll("\\s+", " ").trim();
        assertTrue(normalizedText.contains(normalizedExpected), "Missing required bounded wording: " + expected);
    }

    private static void assertDoesNotContainIgnoringCase(String text, String forbidden) {
        assertFalse(text.toLowerCase().contains(forbidden.toLowerCase()),
                "Forbidden trust-positioning phrase is present: " + forbidden);
    }

    private static void assertPatternAbsent(String text, Pattern forbidden) {
        assertFalse(forbidden.matcher(text).find(),
                "Forbidden trust-positioning pattern is present: " + forbidden.pattern());
    }
}
