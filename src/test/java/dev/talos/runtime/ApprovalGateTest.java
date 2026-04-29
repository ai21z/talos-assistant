package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalGateTest {

    @Test void noOpAlwaysApproves() {
        ApprovalGate gate = new NoOpApprovalGate();
        assertTrue(gate.approve("send email", "to user@example.com"));
        assertTrue(gate.approve("delete file", null));
        assertTrue(gate.approve(null, null));
    }

    @Test void customGateCanDeny() {
        ApprovalGate gate = (desc, detail) -> false;
        assertFalse(gate.approve("anything", "detail"));
    }

    @Test void conditionalGate() {
        // Gate that only approves "read" operations
        ApprovalGate gate = (desc, detail) ->
                desc != null && desc.toLowerCase().startsWith("read");

        assertTrue(gate.approve("read file", null));
        assertFalse(gate.approve("delete file", null));
        assertFalse(gate.approve(null, null));
    }

    @Test
    void cliApprovalGateLabelsProtectedReadAsSensitiveRead() {
        var out = new ByteArrayOutputStream();
        var gate = new CliApprovalGate(
                new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        gate.approveFull(
                "protected read: talos.read_file",
                "permission: Permission policy requires approval before reading protected path `.env`.\n"
                        + "    target: .env");

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Action: protected read: talos.read_file"), text);
        assertTrue(text.contains("Risk:   sensitive read"), text);
        assertFalse(text.contains("Risk:   write"), text);
    }
}

