package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;

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
        QualificationArtifactPublication.DeclaredRun declared =
                QualificationArtifactPublication.requireWritableDeclaredRun(
                        packetRoot, requestedRunRoot, scenarioId, catalog);
        NoFollowPathPolicy.GuardedRoot packetFiles = declared.packetFiles();
        NoFollowPathPolicy.GuardedRoot runFiles = declared.runFiles();
        Path runRoot = declared.runRoot();
        Path root = declared.packetRoot();
        Path promptOutput = runRoot.resolve(PROMPT_OUTPUT);
        Path modelOutput = runRoot.resolve(MODEL_OUTPUT);
        List<Path> renders = providerRenders(runFiles, runRoot);
        boolean promptExists =
                runFiles.inspect(promptOutput) != NoFollowPathPolicy.EntryType.MISSING;
        boolean modelExists =
                runFiles.inspect(modelOutput) != NoFollowPathPolicy.EntryType.MISSING;

        if (promptExists || modelExists) {
            if (!promptExists || !modelExists) {
                throw new IllegalStateException("provider NOT_APPLICABLE evidence must be paired");
            }
            runFiles.requireRegularFile(promptOutput);
            runFiles.requireRegularFile(modelOutput);
            if (renders.isEmpty()) {
                String prompt = boundedText(
                        runFiles, promptOutput, MAX_PROMPT_BYTES, "prompt evidence");
                String model = boundedText(
                        runFiles, modelOutput, MAX_MODEL_LOG_BYTES, "model runtime evidence");
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
            sourceLog = requiredSourceLog(packetFiles, sourceLog);
            byte[] promptBytes = boundedBytes(
                    runFiles, renders.getFirst(), MAX_PROMPT_BYTES, "provider prompt render");
            byte[] modelBytes = boundedBytes(
                    packetFiles, sourceLog, MAX_MODEL_LOG_BYTES, "llama.cpp source log");
            if (!java.util.Arrays.equals(runFiles.readAllBytes(promptOutput), promptBytes)
                    || !java.util.Arrays.equals(runFiles.readAllBytes(modelOutput), modelBytes)) {
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
        sourceLog = requiredSourceLog(packetFiles, sourceLog);
        byte[] promptBytes = boundedBytes(
                runFiles, renders.getFirst(), MAX_PROMPT_BYTES, "provider prompt render");
        byte[] modelBytes = boundedBytes(
                packetFiles, sourceLog, MAX_MODEL_LOG_BYTES, "llama.cpp source log");
        writePair(runFiles, promptOutput, promptBytes, modelOutput, modelBytes);
        return new Result(promptOutput, modelOutput, true);
    }

    private static List<Path> providerRenders(
            NoFollowPathPolicy.GuardedRoot runFiles,
            Path runRoot
    ) throws IOException {
        return runFiles.walk(runRoot).stream()
                .filter(entry ->
                        entry.type() == NoFollowPathPolicy.EntryType.REGULAR_FILE)
                .map(NoFollowPathPolicy.TreeEntry::path)
                .filter(path -> path.getParent().equals(runRoot))
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith("prompt-debug-") && name.endsWith(".md");
                })
                .sorted()
                .toList();
    }

    private static Path requiredSourceLog(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path sourceLog
    ) throws IOException {
        NoFollowPathPolicy.EntryType type = packetFiles.inspect(sourceLog);
        if (type != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
            throw new IllegalStateException("llama.cpp source log is missing");
        }
        return packetFiles.requireRegularFile(sourceLog);
    }

    private static String boundedText(
            NoFollowPathPolicy.GuardedRoot root,
            Path path,
            long maxBytes,
            String label
    ) throws IOException {
        return new String(boundedBytes(root, path, maxBytes, label), StandardCharsets.UTF_8);
    }

    private static byte[] boundedBytes(
            NoFollowPathPolicy.GuardedRoot root,
            Path path,
            long maxBytes,
            String label
    ) throws IOException {
        root.requireRegularFile(path);
        long size = Files.size(path);
        if (size <= 0 || size > maxBytes) {
            throw new IllegalStateException(label + " size is outside the allowed range: " + size);
        }
        return root.readAllBytes(path);
    }

    static void writePair(
            NoFollowPathPolicy.GuardedRoot runFiles,
            Path promptOutput,
            byte[] promptBytes,
            Path modelOutput,
            byte[] modelBytes) throws IOException {
        // Never delete an already-published first member when the second fails;
        // exact retry is safe, while transactional pair visibility remains T1192.
        QualificationArtifactPublication.publishNewOrVerifyExact(
                runFiles,
                promptOutput, promptBytes, "provider prompt evidence");
        QualificationArtifactPublication.publishNewOrVerifyExact(
                runFiles,
                modelOutput, modelBytes, "provider model evidence");
    }
}
