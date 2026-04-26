package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogbackOutputPolicyTest {

    @Test
    void runtimeLogbackKeepsWarningsOutOfNormalConsoleOutput() throws Exception {
        String xml = resourceText("/logback.xml");

        assertTrue(xml.contains("class=\"ch.qos.logback.core.FileAppender\""),
                "WARN diagnostics should be preserved in a log file.");
        assertTrue(xml.contains("<appender-ref ref=\"FILE\"/>"));
        assertTrue(xml.contains("class=\"ch.qos.logback.classic.filter.ThresholdFilter\""));
        assertTrue(xml.contains("<level>ERROR</level>"),
                "Console output should be limited to hard errors, not normal WARN diagnostics.");
        assertTrue(xml.contains("<target>System.err</target>"));
    }

    private static String resourceText(String name) throws Exception {
        try (var in = LogbackOutputPolicyTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "Missing resource: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
