package dev.talos.spi.types;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TokenChunk}, including the new native tool-call support.
 */
class TokenChunkTest {

    @Nested
    class BackwardCompat {

        @Test
        void of_text_chunk() {
            TokenChunk ch = TokenChunk.of("hello");
            assertEquals("hello", ch.text());
            assertNull(ch.done());
            assertNull(ch.toolCalls());
            assertFalse(ch.hasToolCalls());
        }

        @Test
        void eos_sentinel() {
            TokenChunk ch = TokenChunk.eos();
            assertEquals("", ch.text());
            assertTrue(ch.done());
            assertNull(ch.toolCalls());
            assertFalse(ch.hasToolCalls());
            assertEquals(TokenChunk.TerminalSignal.PROVIDER_SENTINEL, ch.terminalSignal());
        }

        @Test
        void transportEofIsMechanicallyDistinctFromProviderSentinel() {
            TokenChunk ch = TokenChunk.transportEof("stop");
            assertTrue(ch.done());
            assertEquals("stop", ch.finishReason());
            assertEquals(TokenChunk.TerminalSignal.TRANSPORT_EOF, ch.terminalSignal());
        }

        @Test
        void terminalEvidenceDoesNotExpandOrRedefineThePublicRecordContract() {
            assertArrayEquals(
                    new String[]{"text", "done", "toolCalls", "finishReason"},
                    java.util.Arrays.stream(TokenChunk.class.getRecordComponents())
                            .map(java.lang.reflect.RecordComponent::getName)
                            .toArray(String[]::new));

            TokenChunk historical = new TokenChunk("", true, null, "stop");
            TokenChunk transportEof = TokenChunk.transportEof("stop");
            assertEquals(historical, transportEof,
                    "typed evidence must not change established record equality");
            assertEquals(historical.hashCode(), transportEof.hashCode());
            assertEquals(historical.toString(), transportEof.toString());
            assertEquals(TokenChunk.TerminalSignal.PROVIDER_SENTINEL,
                    historical.terminalSignal());
            assertEquals(TokenChunk.TerminalSignal.TRANSPORT_EOF,
                    transportEof.terminalSignal());
        }

        @Test
        void singleArgConstructor() {
            TokenChunk ch = new TokenChunk("text");
            assertEquals("text", ch.text());
            assertNull(ch.done());
            assertNull(ch.toolCalls());
        }

        @Test
        void twoArgConstructor() {
            TokenChunk ch = new TokenChunk("text", false);
            assertEquals("text", ch.text());
            assertFalse(ch.done());
            assertNull(ch.toolCalls());
        }
    }

    @Nested
    class NativeToolCalls {

        @Test
        void ofToolCalls_carriesStructuredCalls() {
            var call = new ChatMessage.NativeToolCall("call_0", "talos.list_dir", Map.of("path", "."));
            TokenChunk ch = TokenChunk.ofToolCalls(List.of(call));

            assertTrue(ch.hasToolCalls());
            assertEquals(1, ch.toolCalls().size());
            assertEquals("talos.list_dir", ch.toolCalls().get(0).name());
            assertEquals(".", ch.toolCalls().get(0).arguments().get("path"));
            assertEquals("", ch.text()); // text is empty for tool-call chunks
        }

        @Test
        void ofToolCalls_multipleCallsPreserved() {
            var call1 = new ChatMessage.NativeToolCall("call_0", "talos.list_dir", Map.of("path", "."));
            var call2 = new ChatMessage.NativeToolCall("call_1", "talos.read_file", Map.of("path", "README.md"));
            TokenChunk ch = TokenChunk.ofToolCalls(List.of(call1, call2));

            assertTrue(ch.hasToolCalls());
            assertEquals(2, ch.toolCalls().size());
        }

        @Test
        void hasToolCalls_falseForNull() {
            TokenChunk ch = new TokenChunk("text", null, null);
            assertFalse(ch.hasToolCalls());
        }

        @Test
        void hasToolCalls_falseForEmptyList() {
            TokenChunk ch = new TokenChunk("text", null, List.of());
            assertFalse(ch.hasToolCalls());
        }

        @Test
        void textChunk_doesNotHaveToolCalls() {
            TokenChunk ch = TokenChunk.of("just text");
            assertFalse(ch.hasToolCalls());
        }
    }
}

