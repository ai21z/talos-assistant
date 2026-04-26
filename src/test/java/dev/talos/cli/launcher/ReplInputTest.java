package dev.talos.cli.launcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplInputTest {

    @Test
    void scriptedInputSharesPromptAndApprovalReaderWithoutDrift() {
        ByteArrayInputStream in = new ByteArrayInputStream(
                "make a change\r\nn\r\n/exit\r\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReplInput input = ReplInput.scripted(in, new PrintStream(out, true, StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertEquals("make a change", input.readLine("talos [auto] > "));
        assertEquals("n", input.approvalReader().apply("  Allow? [y/N] "));
        assertEquals("/exit", input.readLine("talos [auto] > "));
        assertNull(input.readLine("talos [auto] > "));

        String transcript = out.toString(StandardCharsets.UTF_8);
        assertFalse(transcript.contains("make a change"),
                "Scripted input should not be echoed into captured transcript output.");
        assertFalse(transcript.contains("\nn\n"),
                "Approval response should be consumed, not echoed as a later user turn.");
    }
}
