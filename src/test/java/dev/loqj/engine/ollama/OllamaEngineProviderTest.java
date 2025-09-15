package dev.loqj.engine.ollama;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OllamaEngineProviderTest {
    @Test
    void id_is_ollama() {
        var provider = new OllamaEngineProvider();
        assertEquals("ollama", provider.id());
    }
}
