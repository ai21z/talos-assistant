package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CliApprovalGate}: interactive user approval via stdin.
 */
class CliApprovalGateTest {

    @Test
    void approvesOnY() {
        var gate = gateWith("y\n");
        assertTrue(gate.approve("write file", "path/to/file"));
    }

    @Test
    void approvesOnYes() {
        var gate = gateWith("yes\n");
        assertTrue(gate.approve("write file", null));
    }

    @Test
    void approvesOnYesCaseInsensitive() {
        var gate = gateWith("YES\n");
        assertTrue(gate.approve("write file", null));
    }

    @Test
    void approvesOnYWithWhitespace() {
        var gate = gateWith("  y  \n");
        assertTrue(gate.approve("write file", null));
    }

    @Test
    void deniesOnN() {
        var gate = gateWith("n\n");
        assertFalse(gate.approve("delete file", null));
    }

    @Test
    void deniesOnNo() {
        var gate = gateWith("no\n");
        assertFalse(gate.approve("delete file", null));
    }

    @Test
    void deniesOnEmptyLine() {
        var gate = gateWith("\n");
        assertFalse(gate.approve("delete file", null));
    }

    @Test
    void deniesOnArbitraryInput() {
        var gate = gateWith("maybe\n");
        assertFalse(gate.approve("operation", null));
    }

    @Test
    void deniesOnEOF() {
        var gate = gateWith("");
        assertFalse(gate.approve("operation", null));
    }

    @Test
    void outputIncludesDescription() {
        var bout = new ByteArrayOutputStream();
        var gate = new CliApprovalGate(
                new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bout));

        gate.approve("write to database", null);

        String output = bout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("write to database"),
                "Output should include the operation description");
        assertTrue(output.contains("Allow?"),
                "Output should include the approval prompt");
    }

    @Test
    void outputIncludesDetail() {
        var bout = new ByteArrayOutputStream();
        var gate = new CliApprovalGate(
                new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bout));

        gate.approve("write file", "target: src/main/Main.java");

        String output = bout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("src/main/Main.java"),
                "Output should include the detail");
    }

    @Test
    void handlesNullDescription() {
        var gate = gateWith("y\n");
        assertTrue(gate.approve(null, null));
    }

    private static CliApprovalGate gateWith(String userInput) {
        return new CliApprovalGate(
                new ByteArrayInputStream(userInput.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(new ByteArrayOutputStream()));
    }
}

