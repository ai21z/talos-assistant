package dev.talos.cli.doctor;

import dev.talos.core.IndexPathResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies the workspace's Lucene index directory is writable. Creating the
 * directory is the one acceptable side effect — normal indexing does the
 * same; the temp probe file is always deleted.
 */
public final class IndexWritableProbe implements DoctorProbe {

    @Override
    public String id() {
        return "index-writable";
    }

    @Override
    public ProbeResult run(DoctorContext ctx) {
        Path dir = IndexPathResolver.getIndexDirectory(ctx.talosHome(), ctx.workspace());
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, "doctor-", ".probe");
            Files.deleteIfExists(probe);
            return ProbeResult.pass(id(), "index directory writable: " + dir);
        } catch (IOException e) {
            return ProbeResult.fail(id(),
                    "index directory not writable: " + dir + " (" + e.getMessage() + ")",
                    "check permissions on " + ctx.talosHome());
        }
    }
}
