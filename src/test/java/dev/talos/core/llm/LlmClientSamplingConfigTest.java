package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.types.SamplingControls;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** T740: llm.sampling config keys parse into config-level sampling overrides. */
class LlmClientSamplingConfigTest {

    @Test
    void samplingAbsentByDefault() {
        // Hermetic: Config() overlays the developer's real ~/.talos/config.yaml,
        // which may carry a seeded llm.sampling block (T745 A/B harness seeding).
        // "Absent by default" means "no sampling key present", so remove any
        // machine-local overlay before asserting.
        Config cfg = new Config();
        Map<String, Object> llm = asMap(cfg.data.get("llm"));
        if (llm != null) {
            llm.remove("sampling");
        }

        LlmClient client = new LlmClient(cfg);

        assertFalse(client.configSampling().anySet());
    }

    @Test
    void samplingKeysParseFromConfig() {
        Config cfg = new Config();
        Map<String, Object> llm = asMap(cfg.data.get("llm"));
        Map<String, Object> sampling = new LinkedHashMap<>();
        sampling.put("seed", 4242);
        sampling.put("temperature", "0.3");
        sampling.put("top_p", 0.85);
        sampling.put("top_k", "30");
        llm.put("sampling", sampling);

        LlmClient client = new LlmClient(cfg);
        SamplingControls parsed = client.configSampling();

        assertEquals(4242L, parsed.seed());
        assertEquals(0.3, parsed.temperature());
        assertEquals(0.85, parsed.topP());
        assertEquals(30, parsed.topK());
    }

    @Test
    void malformedValuesParseAsUnset() {
        Config cfg = new Config();
        Map<String, Object> llm = asMap(cfg.data.get("llm"));
        Map<String, Object> sampling = new LinkedHashMap<>();
        sampling.put("seed", "not-a-number");
        sampling.put("temperature", "");
        llm.put("sampling", sampling);

        LlmClient client = new LlmClient(cfg);

        assertNull(client.configSampling().seed());
        assertNull(client.configSampling().temperature());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
