package dev.talos.cli.doctor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.Path;

/** Reports bounded local runtime and hardware facts without probing GPU/VRAM. */
public final class RuntimeEnvironmentProbe implements DoctorProbe {

    @Override
    public String id() {
        return "runtime-env";
    }

    @Override
    public ProbeResult run(DoctorContext ctx) {
        String os = System.getProperty("os.name", "unknown");
        String arch = System.getProperty("os.arch", "unknown");
        String java = System.getProperty("java.version", "unknown");
        int cpus = Runtime.getRuntime().availableProcessors();
        long maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        String freeMb = freeSpaceMb(ctx.talosHome());

        return ProbeResult.pass(id(), "os=" + os
                + " arch=" + arch
                + " java=" + java
                + " cpu=" + cpus
                + " jvmMaxMemoryMb=" + maxMemoryMb
                + " talosHomeFreeMb=" + freeMb
                + " GPU/VRAM not probed by Talos");
    }

    private static String freeSpaceMb(Path path) {
        try {
            Path existing = nearestExisting(path);
            FileStore store = Files.getFileStore(existing);
            return String.valueOf(store.getUsableSpace() / (1024L * 1024L));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static Path nearestExisting(Path path) throws IOException {
        Path current = path == null ? Path.of(".").toAbsolutePath().normalize() : path.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current == null ? Path.of(".").toAbsolutePath().normalize() : current;
    }
}
