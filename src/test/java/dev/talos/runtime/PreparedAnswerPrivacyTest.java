package dev.talos.runtime;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreparedAnswerPrivacyTest {
    @Test
    void aNullContextRedactorUsesTheDefaultPrivacyProjection() {
        var context = new RuntimeTurnContext() {
            public Config cfg() { return new Config(); }
            public dev.talos.core.llm.LlmClient llm() { return null; }
            public dev.talos.core.security.Sandbox sandbox() { return null; }
            public dev.talos.runtime.phase.ExecutionPhaseState executionPhaseState() { return null; }
            public List<dev.talos.spi.types.ToolSpec> nativeToolSpecs() { return List.of(); }
            public Redactor redactor() { return null; }
        };
        String text = "Workspace C:\\private-fixture\\document.txt";
        assertEquals("Workspace [path]", PreparedAnswer.freeze(text, context, 1).project(text, new Redactor()));
    }
    @Test
    void aDifferentDestinationPolicyCannotRestorePreviouslyRemovedText() {
        String secret = "private-synthetic-67823";
        var redactor = new Redactor(Map.of("redact", Map.of("secrets", List.of(secret))));
        var context = Context.builder(new Config()).redactor(redactor).build();
        var prepared = PreparedAnswer.freeze("Useful " + secret, context, 1);
        assertEquals("Useful [secret]", prepared.project("Useful " + secret, new Redactor()));
        assertFalse(prepared.project(secret, new Redactor()).contains(secret));
        assertTrue(prepared.project(secret, new Redactor()).contains("withheld"));
    }
}
