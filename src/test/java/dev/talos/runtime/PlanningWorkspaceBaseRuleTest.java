package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turn planning and policy derive from the workspace selected at startup.
 * The process working directory can differ from it, for example with
 * talos run --root, so it is never a base in these packages.
 */
class PlanningWorkspaceBaseRuleTest {
    private static final List<String> FORBIDDEN = List.of(
            "Path.of(\"\")", "Paths.get(\"\")", "Path.of(\".\")", "Paths.get(\".\")",
            "getPath(\"\")", "\"user.dir\"");

    @Test
    void planningAndPolicyNeverUseTheProcessWorkingDirectory() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(
                Path.of("src/main/java/dev/talos/runtime/turn"),
                Path.of("src/main/java/dev/talos/runtime/policy"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        if (FORBIDDEN.stream().anyMatch(line::contains)) {
                            offenders.add(file.toString().replace('\\', '/') + ":" + (i + 1));
                        }
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(), "Process working directory used as a base: " + offenders);
    }
}
