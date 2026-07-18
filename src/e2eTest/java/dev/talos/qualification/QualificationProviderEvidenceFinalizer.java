package dev.talos.qualification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Finalizes immutable provider evidence without conflating process startup with model generation. */
final class QualificationProviderEvidenceFinalizer {
    private static final String PROMPT_OUTPUT = "prompt-debug.txt";
    private static final String MODEL_OUTPUT = "llama.log";
    private static final long MAX_PROMPT_BYTES = 16L * 1024 * 1024;
    private static final long MAX_MODEL_LOG_BYTES = 64L * 1024 * 1024;

    private QualificationProviderEvidenceFinalizer() {}

    record Result(Path promptDebug, Path modelLog, boolean providerPromptSent) {}

    static Result finalizeEvidence(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            int serverPort,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        if (serverPort < 1 || serverPort > 65_535) {
            throw new IllegalArgumentException("server port must be between 1 and 65535");
        }
        Path runRoot = QualificationRequirementFinalizer.containedRunRoot(
                packetRoot, requestedRunRoot, scenarioId, catalog);
        Path root = packetRoot.toAbsolutePath().normalize().toRealPath();
        Path promptOutput = runRoot.resolve(PROMPT_OUTPUT);
        Path modelOutput = runRoot.resolve(MODEL_OUTPUT);
        List<Path> renders = providerRenders(runRoot);
        boolean promptExists = Files.exists(promptOutput, LinkOption.NOFOLLOW_LINKS);
        boolean modelExists = Files.exists(modelOutput, LinkOption.NOFOLLOW_LINKS);

        if (promptExists || modelExists) {
            if (!promptExists || !modelExists) {
                throw new IllegalStateException("provider NOT_APPLICABLE evidence must be paired");
            }
            requireRegular(promptOutput, "prompt evidence");
            requireRegular(modelOutput, "model runtime evidence");
            if (renders.isEmpty()) {
                String prompt = boundedText(promptOutput, MAX_PROMPT_BYTES, "prompt evidence");
                String model = boundedText(modelOutput, MAX_MODEL_LOG_BYTES, "model runtime evidence");
                if (!prompt.equals(QualificationProviderEvidence.promptNotApplicableText())
                        || !model.equals(QualificationProviderEvidence.modelRuntimeNotApplicableText())) {
                    throw new IllegalStateException(
                            "pre-existing provider evidence is not the canonical NOT_APPLICABLE pair");
                }
                return new Result(promptOutput, modelOutput, false);
            }
            if (renders.size() != 1) {
                throw new IllegalStateException(
                        "expected exactly one provider prompt render, found " + renders.size());
            }
            Path sourceLog = root.resolve(
                    Path.of("home", ".talos", "logs", "llama_cpp-" + serverPort + ".log"));
            sourceLog = containedRegular(root, sourceLog, "llama.cpp source log");
            byte[] promptBytes = boundedBytes(
                    renders.getFirst(), MAX_PROMPT_BYTES, "provider prompt render");
            byte[] modelBytes = boundedBytes(
                    sourceLog, MAX_MODEL_LOG_BYTES, "llama.cpp source log");
            if (!java.util.Arrays.equals(Files.readAllBytes(promptOutput), promptBytes)
                    || !java.util.Arrays.equals(Files.readAllBytes(modelOutput), modelBytes)) {
                throw new IllegalStateException(
                        "pre-existing provider evidence conflicts with the canonical source pair");
            }
            return new Result(promptOutput, modelOutput, true);
        }

        if (renders.size() != 1) {
            throw new IllegalStateException(
                    "expected exactly one provider prompt render, found " + renders.size());
        }
        Path sourceLog = root.resolve(
                Path.of("home", ".talos", "logs", "llama_cpp-" + serverPort + ".log"));
        sourceLog = containedRegular(root, sourceLog, "llama.cpp source log");
        byte[] promptBytes = boundedBytes(renders.getFirst(), MAX_PROMPT_BYTES, "provider prompt render");
        byte[] modelBytes = boundedBytes(sourceLog, MAX_MODEL_LOG_BYTES, "llama.cpp source log");
        writePair(promptOutput, promptBytes, modelOutput, modelBytes);
        return new Result(promptOutput, modelOutput, true);
    }

    private static List<Path> providerRenders(Path runRoot) throws IOException {
        try (var files = Files.list(runRoot)) {
            return files.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("prompt-debug-")
                                && name.endsWith(".md")
                                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
                    })
                    .sorted()
                    .toList();
        }
    }

    private static void requireRegular(Path path, String label) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(label + " is missing or not a regular file: " + path);
        }
    }

    private static Path containedRegular(Path root, Path path, String label) throws IOException {
        requireRegular(path, label);
        Path real = path.toRealPath();
        if (!real.startsWith(root)) {
            throw new IllegalStateException(label + " escapes the qualification packet: " + real);
        }
        return real;
    }

    private static String boundedText(Path path, long maxBytes, String label) throws IOException {
        return new String(boundedBytes(path, maxBytes, label), StandardCharsets.UTF_8);
    }

    private static byte[] boundedBytes(Path path, long maxBytes, String label) throws IOException {
        long size = Files.size(path);
        if (size <= 0 || size > maxBytes) {
            throw new IllegalStateException(label + " size is outside the allowed range: " + size);
        }
        return Files.readAllBytes(path);
    }

    static void writePair(
            Path promptOutput,
            byte[] promptBytes,
            Path modelOutput,
            byte[] modelBytes) throws IOException {
        // Never delete an already-published first member when the second fails;
        // exact retry is safe, while transactional pair visibility remains T1192.
        QualificationArtifactPublication.publishNewOrVerifyExact(
                promptOutput, promptBytes, "provider prompt evidence");
        QualificationArtifactPublication.publishNewOrVerifyExact(
                modelOutput, modelBytes, "provider model evidence");
    }
}
