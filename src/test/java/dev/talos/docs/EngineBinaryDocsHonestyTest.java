package dev.talos.docs;

import dev.talos.cli.setup.LlamaCppEngineManifest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docs may only name Windows llama.cpp engine binaries that the pinned
 * manifest can actually install. A doc naming a zip that setup cannot
 * produce is an overclaim (T986).
 */
class EngineBinaryDocsHonestyTest {

    private static final Pattern WINDOWS_ENGINE_BINARY = Pattern.compile(
            "\\b(?:llama|cudart)-[A-Za-z0-9.\\-]*bin-win[A-Za-z0-9.\\-]*\\.zip\\b");

    @Test
    void everyWindowsEngineBinaryNamedInDocsExistsInThePinnedManifest() throws IOException {
        Set<String> manifestAssets = new LinkedHashSet<>();
        for (LlamaCppEngineManifest.Entry entry : LlamaCppEngineManifest.entries()) {
            manifestAssets.add(entry.assetName());
            if (entry.companion() != null) {
                manifestAssets.add(entry.companion().assetName());
            }
        }

        List<String> violations = new ArrayList<>();
        for (Path doc : docsFiles()) {
            String text = Files.readString(doc, StandardCharsets.UTF_8);
            Matcher matcher = WINDOWS_ENGINE_BINARY.matcher(text);
            while (matcher.find()) {
                String named = matcher.group();
                if (!manifestAssets.contains(named)) {
                    violations.add(doc + " names " + named + " which is not a pinned manifest asset");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Docs must only name installable Windows engine binaries:\n" + String.join("\n", violations)
                        + "\nPinned assets: " + manifestAssets);
    }

    private static List<Path> docsFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        Path readme = Path.of("README.md");
        if (Files.isRegularFile(readme)) {
            out.add(readme);
        }
        Path docs = Path.of("docs");
        if (Files.isDirectory(docs)) {
            try (Stream<Path> stream = Files.walk(docs)) {
                stream.filter(path -> path.toString().endsWith(".md"))
                        .filter(Files::isRegularFile)
                        .forEach(out::add);
            }
        }
        return out;
    }
}
