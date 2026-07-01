package dev.talos.cli.doctor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies the Talos home and its logs directory are writable - they hold
 * the first-run sentinel, secrets, session files, and the managed
 * llama.cpp server log.
 */
public final class HomeWritableProbe implements DoctorProbe {

    @Override
    public String id() {
        return "home-writable";
    }

    @Override
    public ProbeResult run(DoctorContext ctx) {
        Path home = ctx.talosHome();
        Path logs = home.resolve("logs");
        try {
            Files.createDirectories(logs);
            probeWrite(home);
            probeWrite(logs);
            return ProbeResult.pass(id(), "Talos home writable: " + home);
        } catch (IOException e) {
            return ProbeResult.fail(id(),
                    "Talos home not writable: " + home + " (" + e.getMessage() + ")",
                    "check permissions on " + home);
        }
    }

    private static void probeWrite(Path dir) throws IOException {
        Path probe = Files.createTempFile(dir, "doctor-", ".probe");
        Files.deleteIfExists(probe);
    }
}
