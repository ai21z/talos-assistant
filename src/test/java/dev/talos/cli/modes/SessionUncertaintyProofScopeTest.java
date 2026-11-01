package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.context.ChangeSummaryContext;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A file whose change passed checks without verified completion is reported as uncertain (MICA I3, D5). */
class SessionUncertaintyProofScopeTest {

    @Test
    void aChecksPassFileIsStillUncertain(@TempDir Path workspace) {
        SessionMemory memory = new SessionMemory();
        memory.setChangeSummaryContext(new ChangeSummaryContext(
                ChangeSummaryContext.SCHEMA_VERSION,
                List.of(
                        new ChangeSummaryContext.FileChange("notes.txt", "talos.write_file", 30, "trc-notes",
                                "SUCCEEDED", "PASSED", "COMPLETED_VERIFIED"),
                        new ChangeSummaryContext.FileChange("index.html", "talos.write_file", 31, "trc-index",
                                "SUCCEEDED", "PASSED", "COMPLETED_UNVERIFIED")),
                List.of(),
                "PASSED",
                "COMPLETED_VERIFIED",
                List.of()));
        var ctx = Context.builder(new Config())
                .memory(memory)
                .llm(ScriptedNativeLlmClient.textToolFallbackStrings("Everything is verified."))
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("what are you unsure about from this session? short and evidence-based."));

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages, workspace, ctx, new AssistantTurnExecutor.Options());

        assertTrue(out.text().startsWith("Uncertainty:"), out.text());
        assertTrue(out.text().contains("Latest recorded mutation evidence is not verified complete"), out.text());
    }
}
