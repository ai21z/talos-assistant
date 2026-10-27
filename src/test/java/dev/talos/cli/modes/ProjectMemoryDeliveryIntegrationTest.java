package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.context.*;
import dev.talos.core.llm.ContextDeliveryWireFixture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class ProjectMemoryDeliveryIntegrationTest {
    @TempDir Path temp;

    @AfterEach void clear() {
        LocalTurnTraceCapture.clear();
        PromptDebugCapture.clear();
        ContextLedgerCapture.clear();
        ProviderResponseCapture.clear();
    }

    @ParameterizedTest
    @CsvSource({"8192,false", "2048,false", "8192,true", "2048,true"})
    void sourceDispositionMatchesActualHttpBodyAndCompletedTrace(int window, boolean streaming) throws Exception {
        String marker = "MICA_PROJECT_MEMORY_DISPATCH_MARKER";
        String source = marker + " " + "x".repeat(9800);
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.writeString(workspace.resolve("TALOS.md"), source);
        try (var wire = new ContextDeliveryWireFixture(window, streaming, "Synthetic response, no semantic claim.")) {
            var ctx = Context.builder(wire.config).llm(wire.client).build();
            var messages = new ArrayList<>(List.of(ChatMessage.system("Read-only synthetic task."),
                    ChatMessage.user("Explain this project.")));
            LocalTurnTraceCapture.begin("trc-delivery", "mica", 1, "2026-10-08T00:00:00Z",
                    "synthetic-workspace", "auto", "synthetic", "mica-synthetic", "Explain this project.");
            var prepared = AssistantTurnPreparation.prepare(messages, workspace, ctx, false);
            var admission = ContextLedgerCapture.snapshot();
            assertFalse(prepared.plan().taskContract().mutationAllowed());
            assertTrue(messages.stream().anyMatch(message -> message.content().contains(marker)));
            assertEquals(1, admission.entries().stream().filter(entry -> entry.item().pathHint().equals("TALOS.md")
                    && entry.decision().action() == ContextDecision.Action.ADMITTED_TO_CONTEXT).count());
            assertFalse(admission.summary().byDecision().containsKey("INCLUDED_IN_MODEL_PROMPT"));
            String body = wire.send(messages);
            var prompt = PromptDebugCapture.latest().orElseThrow();
            var ledger = ContextLedgerCapture.snapshot();
            var trace = LocalTurnTraceCapture.complete();
            var sourceEntry = ledger.entries().stream().filter(entry -> entry.item().pathHint().equals("TALOS.md")
                    && !entry.requestId().isEmpty()).findFirst().orElseThrow();
            boolean delivered = body.contains(marker);
            ContextDeliveryWireFixture.record("delivery-" + window + "-" + streaming,
                    Map.of("body", body, "ledger", ledger, "promptDebug", prompt,
                            "trace", trace, "delivered", delivered));
            assertEquals(window == 8192, delivered);
            assertEquals(body, prompt.providerBodyJson());
            assertEquals(ProviderContextMembership.decode(body).bodyHash(), sourceEntry.providerBodyHash());
            assertEquals(admission.entries().stream().filter(entry -> entry.item().pathHint().equals("TALOS.md"))
                    .findFirst().orElseThrow().item().textHash(), sourceEntry.item().textHash());
            assertEquals(delivered ? ContextDecision.Action.SERIALIZED_AT_TRANSPORT_HANDOFF
                    : ContextDecision.Action.WITHHELD_FROM_MODEL, sourceEntry.decision().action());
            assertEquals(1, trace.events().stream().filter(event -> event.type().equals("CONTEXT_DELIVERY")).count());
        }
    }
}
