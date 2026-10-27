package dev.talos.core.llm;

import dev.talos.safety.ContentRestrictions;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.types.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DocumentPrivacyWorkerTest {
    private static final ContentRestrictions PRIVATE = new ContentRestrictions(true, false, false, false);
    private static final String PROSE = "The workshop attendance was rearranged for Thursday.";
    @AfterEach void clear() { ContentRestrictionScope.end(); PromptDebugCapture.clear(); }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void artifactRejectionCannotTriggerAProviderRetry(boolean streaming) {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var resolver = new LlmEngineResolver() {
            @Override public void select(String backend, String model) {}
            @Override public java.util.stream.Stream<TokenChunk> chatStream(ChatRequest request) {
                attempts.incrementAndGet();
                assertFalse(request.contentRestrictions.artifactAllowed());
                throw new ProviderTransportHandoff.ArtifactRetentionRejected();
            }
            @Override public java.util.stream.Stream<TokenChunk> chatStream(
                    ChatRequest request, ProviderTransportHandoff handoff) {
                return chatStream(request);
            }
            @Override public java.util.stream.Stream<TokenChunk> chatStreamNonStreaming(
                    ChatRequest request, ProviderTransportHandoff handoff) {
                return chatStream(request);
            }
            @Override public void close() {}
        };
        var config = new dev.talos.core.Config();
        config.data.put("llm", java.util.Map.of("transport", "engine", "default_backend", "llama_cpp"));
        config.data.put("engines", java.util.Map.of("llama_cpp", java.util.Map.of("model", "fixture")));
        try (var client = new LlmClient(config, resolver)) {
            var messages = List.of(ChatMessage.user(PROSE).withContentRestrictions(PRIVATE));
            var failure = assertThrows(dev.talos.spi.EngineException.ResponseError.class, () -> {
                if (streaming) client.chatStreamFull(messages, null);
                else client.chatFull(messages);
            });
            assertInstanceOf(ProviderTransportHandoff.ArtifactRetentionRejected.class, failure.getCause());
            assertEquals(1, attempts.get());
        }
    }

    @Test void actualBudgetWorkerCarriesInputRestrictionsBackToOwner() {
        ContentRestrictionScope.begin();
        var marked = new ChatMessage("assistant", PROSE, null, null, PRIVATE);
        try (var budget = new LlmCallBudget(0)) {
            var result = budget.run(stream -> {
                var request = new ChatRequest("fixture", "fixture", "", "", List.of(), null, List.of(marked));
                PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(request, false, PROSE));
                assertEquals(ContentRestrictions.WITHHELD, SafeLogFormatter.text(PROSE));
                return new LlmClient.StreamResult(PROSE, List.of());
            }, 5000, null, "privacy-fixture", null);
            assertEquals(PROSE, result.text());
            assertEquals(PRIVATE, ContentRestrictionScope.current());
            assertEquals(ContentRestrictions.WITHHELD, PromptDebugCapture.latest().orElseThrow().providerBodyJson());
            ContentRestrictionScope.end();
            ContentRestrictionScope.begin();
            budget.run(stream -> {
                assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
                assertEquals(PROSE, SafeLogFormatter.text(PROSE));
                return new LlmClient.StreamResult("ordinary", List.of());
            }, 5000, null, "ordinary-fixture", null);
        }
    }

    @Test void exceptionalWorkerRestoresItsPreviousScope() throws Exception {
        ContentRestrictionScope.begin();
        ContentRestrictionScope.restrict(PRIVATE);
        Runnable wrapped = ContentRestrictionScope.propagate(() -> { throw new IllegalArgumentException("fixture"); });
        var result = new AtomicReference<ContentRestrictions>();
        var thread = new Thread(() -> {
            assertThrows(IllegalArgumentException.class, wrapped::run);
            result.set(ContentRestrictionScope.current());
        });
        thread.start();
        thread.join(5000);
        assertFalse(thread.isAlive());
        assertEquals(ContentRestrictions.UNRESTRICTED, result.get());
        assertEquals(PRIVATE, ContentRestrictionScope.current());
    }

    @Test void lateCancelledWorkerCannotTaintTheNextTurn() throws Exception {
        ContentRestrictionScope.begin();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        try (var budget = new LlmCallBudget(0)) {
            var result = budget.run(stream -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture deadline");
                    ContentRestrictionScope.restrict(PRIVATE);
                    return new LlmClient.StreamResult(PROSE, List.of());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                finally { finished.countDown(); }
            }, 5000, null, "cancel-fixture", null, () -> entered.getCount() == 0);
            assertTrue(result.aborted());
            ContentRestrictionScope.end();
            ContentRestrictionScope.begin();
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
        } finally { release.countDown(); }
    }
}
