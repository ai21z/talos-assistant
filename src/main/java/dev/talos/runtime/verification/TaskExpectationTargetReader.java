package dev.talos.runtime.verification;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Reads task expectation target files while preserving expectation-specific failure wording. */
final class TaskExpectationTargetReader {

    private TaskExpectationTargetReader() {}

    static Result read(
            Path root,
            String targetPath,
            String resolveFailure,
            String unreadableFailure,
            String readFailurePrefix
    ) {
        String pathHint = normalizePath(targetPath);
        Path target;
        try {
            target = root.resolve(pathHint).normalize();
        } catch (InvalidPathException e) {
            return Result.problem(pathHint, pathHint + ": " + safe(resolveFailure));
        }
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return Result.problem(pathHint, pathHint + ": " + safe(unreadableFailure));
        }
        try {
            return Result.content(pathHint, Files.readString(target));
        } catch (Exception e) {
            return Result.problem(pathHint, pathHint + ": " + safe(readFailurePrefix)
                    + " (" + e.getMessage() + ")");
        }
    }

    private static String normalizePath(String path) {
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    record Result(
            String pathHint,
            String content,
            String problem
    ) {
        Result {
            pathHint = pathHint == null ? "" : pathHint;
            content = content == null ? "" : content;
            problem = problem == null ? "" : problem;
        }

        boolean hasProblem() {
            return !problem.isBlank();
        }

        private static Result content(String pathHint, String content) {
            return new Result(pathHint, content, "");
        }

        private static Result problem(String pathHint, String problem) {
            return new Result(pathHint, "", problem);
        }
    }
}
