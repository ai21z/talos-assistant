package dev.talos.core;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Tests for {@link ConfigView} typed accessors.
 */
class ConfigViewTest {
    private final Config cfg = new Config(null);
    private final ConfigView view = cfg.view();
    @Nested class RagAccessors {
        @Test void topK_returnsDefault() {
            assertEquals(6, view.rag().topK());
        }
        @Test void chunkChars_returnsDefault() {
            assertEquals(1200, view.rag().chunkChars());
        }
        @Test void chunkOverlap_returnsDefault() {
            assertEquals(150, view.rag().chunkOverlap());
        }
        @Test void embedConcurrency_returnsDefault() {
            assertEquals(4, view.rag().embedConcurrency());
        }
        @Test void includes_isNonEmpty() {
            assertFalse(view.rag().includes().isEmpty());
        }
        @Test void excludes_isNonEmpty() {
            assertFalse(view.rag().excludes().isEmpty());
        }
        @Test void vectorsDisabled_fromDefault() {
            // Default beta setup is BM25-only unless the user configures a local embedding endpoint.
            assertFalse(view.rag().vectors().enabled());
        }
    }
    @Nested class OllamaAccessors {
        @Test void host_returnsDefault() {
            assertEquals("http://127.0.0.1:11434", view.ollama().host());
        }
        @Test void model_returnsNonBlank() {
            assertFalse(view.ollama().model().isBlank());
        }
        @Test void embed_returnsDefault() {
            assertEquals("bge-m3", view.ollama().embed());
        }
    }
    @Nested class LimitsAccessors {
        @Test void topKMax_returnsDefault() {
            assertEquals(100, view.limits().topKMax());
        }
        @Test void fileBytesMax_returnsDefault() {
            assertEquals(200_000, view.limits().fileBytesMax());
        }
        @Test void fileLinesMax_returnsDefault() {
            assertEquals(8_000, view.limits().fileLinesMax());
        }
        @Test void llmTimeoutMs_returnsDefault() {
            assertEquals(300_000L, view.limits().llmTimeoutMs());
        }
        @Test void llmContextMaxTokens_returnsDefault() {
            assertEquals(8192, view.limits().llmContextMaxTokens());
        }
        @Test void ratePerSec_returnsDefault() {
            assertEquals(10, view.limits().ratePerSec());
        }
    }
    @Nested class UiAccessors {
        @Test void showTimingAfterAnswer_returnsDefault() {
            assertTrue(view.ui().showTimingAfterAnswer());
        }
        @Test void showBreakdown_returnsDefault() {
            assertFalse(view.ui().showBreakdown());
        }
    }
    @Nested class ToolsAccessors {
        @Test void nativeCalling_returnsDefault() {
            assertTrue(view.tools().nativeCalling());
        }
    }
    @Nested class SessionAccessors {
        @Test void persistence_returnsDefault() {
            assertTrue(view.session().persistence());
        }
        @Test void autoLoad_isOptInByDefault() {
            assertFalse(view.session().autoLoad());
        }
    }
    @Nested class ConvenienceMethod {
        @Test void configView_sameFromCfgView() {
            assertSame(cfg, cfg.view().raw());
        }
        @Test void configView_ofNull_usesDefaultConfig() {
            ConfigView v = ConfigView.of(null);
            assertNotNull(v.raw());
        }
    }
    @Nested class MutationVisibility {
        @Test void runtimeChange_isVisibleThroughView() {
            // ConfigView reads from the live map, so mutations are visible
            Config mutable = new Config(null);
            ConfigView v = mutable.view();
            int before = v.rag().topK();
            assertEquals(6, before);
            // Mutate the underlying map
            @SuppressWarnings("unchecked")
            var rag = (java.util.Map<String, Object>) mutable.data.get("rag");
            rag.put("top_k", 42);
            assertEquals(42, v.rag().topK(), "View should reflect live mutations");
        }
    }
}
