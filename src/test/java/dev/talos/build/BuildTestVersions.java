package dev.talos.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class BuildTestVersions {

    private BuildTestVersions() {}

    static String currentTalosVersion() throws IOException {
        try (var lines = Files.lines(Path.of("gradle.properties"))) {
            return lines
                    .map(String::strip)
                    .filter(line -> line.startsWith("talosVersion="))
                    .map(line -> line.substring("talosVersion=".length()).strip())
                    .findFirst()
                    .orElseThrow(() -> new IOException("Missing talosVersion in gradle.properties"));
        }
    }
}
