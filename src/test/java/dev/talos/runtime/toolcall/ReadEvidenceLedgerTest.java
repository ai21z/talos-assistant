package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T1013: read evidence must bind to the effective execution path with
 * deterministic temporal ordering. Alias keys, legal filename characters,
 * and storage order must never decide which file's content serves as
 * evidence. All evidence is injected through the real writer
 * ({@link ReadEvidenceStateAccounting#recordSuccessfulToolResult}) so these
 * pins hold for the production recording path, not a synthetic store.
 */
class ReadEvidenceLedgerTest {

    @TempDir
    Path workspace;

    @Test
    void hedgedAliasReadNeverBindsContentToTheAliasPath() {
        LoopState state = loopState();
        // The model hedges both keys; execution resolves "path" first, so
        // the file actually read is b.md and the effective path is b.md.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("file_path", "a.md");
        params.put("path", "b.md");
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", params),
                "b.md",
                ToolResult.ok("1 | b-content\n"));

        ToolMutationEvidence evidence = ToolMutationEvidenceFactory.from(
                new ToolCall("talos.write_file", Map.of("path", "a.md", "content", "rewrite\n")),
                state,
                "a.md");

        assertFalse(evidence.fullWriteReplacement(),
                "a read of b.md must never serve as previous-content evidence for a.md: " + evidence);
    }

    @Test
    void evidenceBindsToTheEffectiveExecutionPath() {
        LoopState state = loopState();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("file_path", "a.md");
        params.put("path", "b.md");
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", params),
                "b.md",
                ToolResult.ok("1 | b-content\n"));

        ToolMutationEvidence evidence = ToolMutationEvidenceFactory.from(
                new ToolCall("talos.write_file", Map.of("path", "b.md", "content", "rewrite\n")),
                state,
                "b.md");

        assertTrue(evidence.fullWriteReplacement(),
                "the effective path b.md owns the read evidence: " + evidence);
        assertEquals("b-content\n", evidence.oldString());
    }

    @Test
    void filenamesContainingSignatureMetacharactersKeepTheirIdentity() {
        LoopState state = loopState();
        // ';' and '=' are legal filename characters and historically broke
        // the flattened "key=value;" evidence identity: a read of
        // "a;b=c.md" must never satisfy a lookup for the path "a".
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", Map.of("path", "a;b=c.md")),
                "a;b=c.md",
                ToolResult.ok("1 | metachar-content\n"));

        ToolMutationEvidence wrongPath = ToolMutationEvidenceFactory.from(
                new ToolCall("talos.write_file", Map.of("path", "a", "content", "rewrite\n")),
                state,
                "a");
        ToolMutationEvidence rightPath = ToolMutationEvidenceFactory.from(
                new ToolCall("talos.write_file", Map.of("path", "a;b=c.md", "content", "rewrite\n")),
                state,
                "a;b=c.md");

        assertFalse(wrongPath.fullWriteReplacement(),
                "a read of 'a;b=c.md' must not bind evidence to the path 'a': " + wrongPath);
        assertTrue(rightPath.fullWriteReplacement(),
                "the real metacharacter filename keeps its own evidence: " + rightPath);
        assertEquals("metachar-content\n", rightPath.oldString());
    }

    @Test
    void aLaterFreshCompleteReadSupersedesTheOlderOneDeterministically() {
        LoopState state = loopState();
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", Map.of("path", "notes.md")),
                "notes.md",
                ToolResult.ok("1 | old state\n"));
        // A distinct later complete read observes the file changed (e.g.
        // an approved workspace command ran in between).
        Map<String, String> secondCall = new LinkedHashMap<>();
        secondCall.put("path", "notes.md");
        secondCall.put("max_lines", "200");
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", secondCall),
                "notes.md",
                ToolResult.ok("1 | new state\n"));

        String body = AppendLinePreApprovalGuard.completeReadBodyForPath(state, "notes.md");

        assertEquals("new state\n", body,
                "the latest fresh complete read supersedes the older one deterministically");
    }

    @Test
    void aLaterRangedReadCannotDisplaceCompleteReadEvidence() {
        LoopState state = loopState();
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", Map.of("path", "notes.md")),
                "notes.md",
                ToolResult.ok("1 | complete state\n"));
        Map<String, String> rangedCall = new LinkedHashMap<>();
        rangedCall.put("path", "notes.md");
        rangedCall.put("offset", "40");
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", rangedCall),
                "notes.md",
                ToolResult.ok("40 | a ranged window\n"));

        String body = AppendLinePreApprovalGuard.completeReadBodyForPath(state, "notes.md");

        assertEquals("complete state\n", body,
                "a ranged read is never complete-source evidence and cannot displace the complete read");
    }

    private LoopState loopState() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Read then update the requested file.")));
        Context ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(LlmClient.scripted(List.of()))
                .build();
        return new LoopState("", List.of(), messages, workspace, ctx, null, 5, 0);
    }
}
