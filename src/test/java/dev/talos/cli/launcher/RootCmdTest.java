package dev.talos.cli.launcher;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootCmdTest {

    @Test
    void longHelpOptionShowsCurrentProductIdentity() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new RootCmd())
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));

        int exit = cmd.execute("--help");

        assertEquals(0, exit);
        String text = out.toString() + err;
        assertTrue(text.contains("Talos - local-first workspace operator"), text);
        assertFalse(text.contains("Local Knowledge Engine"), text);
    }

    @Test
    void shortHelpOptionShowsUsage() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new RootCmd())
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));

        int exit = cmd.execute("-h");

        assertEquals(0, exit);
        assertTrue((out.toString() + err).contains("Usage: talos"));
    }
}
