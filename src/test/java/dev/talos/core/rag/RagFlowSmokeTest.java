package dev.talos.core.rag;

import dev.talos.core.Config;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class RagFlowSmokeTest {

    @Test
    public void prepare_doNotThrow() {
        RagService svc = new RagService(new Config());
        Path ws = Path.of(".").toAbsolutePath().normalize();

        RagService.Prepared p = svc.prepare(ws, "what is this project", 3);
        assertNotNull(p, "Prepared must not be null");
        assertNotNull(p.snippetMaps(), "snippets list must not be null");
        assertNotNull(p.citations(), "citations list must not be null");
    }

    @Disabled("Avoid slow live LLM call in CI; enable for manual runs")
    @Test
    public void ask_doNotThrow() {
        RagService svc = new RagService(new Config());
        Path ws = Path.of(".").toAbsolutePath().normalize();
        RagService.Answer ans = svc.ask(ws, "hi there", 2);
        assertNotNull(ans, "Answer must not be null");
        assertNotNull(ans.text(), "Answer text must not be null");
        assertNotNull(ans.citations(), "Answer citations must not be null");
    }
}
