package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.CfgUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Functional extraction for "dev" behaviors: list directory / open file. */
public final class DevMode extends BaseMode implements Mode {

    @Override public String name() { return "dev"; }

    @Override public boolean canHandle(String rawLine) {
        if (rawLine == null) return false;
        String lower = rawLine.toLowerCase(Locale.ROOT);
        return isOpenIntent(lower) || isListIntent(lower);
    }

    @Override
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        if (rawLine == null || rawLine.isBlank()) return Optional.empty();
        String lower = rawLine.toLowerCase(Locale.ROOT);

        var lim = CfgUtil.map(ctx.cfg().data.get("limits"));
        int dirDepthMax   = CfgUtil.intAt(lim, "dir_depth_max", 10);
        int dirEntriesMax = CfgUtil.intAt(lim, "dir_entries_max", 1000);
        int fileBytesMax  = CfgUtil.intAt(lim, "file_bytes_max", 20_000);
        int fileLinesMax  = CfgUtil.intAt(lim, "file_lines_max", 500);
        long fileTimeoutMs= CfgUtil.longAt(lim, "file_timeout_ms", 10_000L);

        Path ws = (workspace == null ? Path.of(".") : workspace);
        Path target = resolveFirstPathToken(ws, rawLine, dirDepthMax);

        if (isOpenIntent(lower)) {
            if (target == null) return Optional.of(new Result.Error("File not found or invalid path.", 201));
            if (!allowed(ctx, target) || !under(ws, target)) return Optional.of(new Result.Error("Refusing path outside workspace.", 203));
            if (Files.isDirectory(target)) return Optional.of(new Result.Info("Path is a directory. Use a list command on: " + relativize(ws, target)));

            Duration timeout = Duration.ofMillis(fileTimeoutMs);
            String text = readFileWithCaps(ws, target, fileBytesMax, fileLinesMax, timeout);
            return Optional.of(new Result.Ok(text));
        }

        if (isListIntent(lower)) {
            Path dir = (target == null ? ws : target);
            if (!allowed(ctx, dir) || !under(ws, dir)) return Optional.of(new Result.Error("Refusing path outside workspace.", 203));
            if (!Files.isDirectory(dir)) return Optional.of(new Result.Error("Not a directory: " + relativize(ws, dir), 201));

            Duration timeout = Duration.ofMillis(fileTimeoutMs);
            String text = listDirWithCaps(ws, dir, dirEntriesMax, timeout);
            return Optional.of(new Result.Ok(text));
        }

        return Optional.empty();
    }

    /* ================= helpers ================= */

    private static String readFileWithCaps(Path ws, Path path, int maxBytes, int maxLines, Duration timeout) throws Exception {
        CompletableFuture<String> fut = CompletableFuture.supplyAsync(() -> {
            try {
                Path abs = toRealOrNorm(path);
                if (!Files.exists(abs)) return "Not found: " + relativize(ws, path) + System.lineSeparator();
                long size = Files.size(abs);

                StringBuilder sb = new StringBuilder();
                sb.append("\n── file: ").append(relativize(ws, abs)).append(" (").append(String.format("%,d", size)).append(" bytes)").append("\n\n");

                List<String> lines = new ArrayList<>();
                try (var reader = Files.newBufferedReader(abs)) {
                    String ln;
                    int totalBytes = 0;
                    while ((ln = reader.readLine()) != null && lines.size() < maxLines && totalBytes < maxBytes) {
                        lines.add(ln);
                        totalBytes += ln.length() + 1;
                    }
                }
                for (String ln : lines) sb.append(ln).append("\n");

                if (lines.size() >= maxLines || size > maxBytes) sb.append("\n… (truncated)\n\n");
                else sb.append("\n");
                return sb.toString();
            } catch (Exception e) {
                return "Read error: " + (e.getMessage() == null ? "(no details)" : e.getMessage()) + "\n";
            }
        });
        try { return fut.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException te) { return "File operation timed out\n"; }
    }

    private static String listDirWithCaps(Path ws, Path dir, int maxEntries, Duration timeout) throws Exception {
        CompletableFuture<String> fut = CompletableFuture.supplyAsync(() -> {
            try {
                Path abs = toRealOrNorm(dir);
                if (!Files.exists(abs)) return "Not found: " + relativize(ws, dir) + "\n";

                StringBuilder sb = new StringBuilder();
                sb.append("\n── dir: ").append(relativize(ws, abs)).append("\n\n");

                List<Path> entries = new ArrayList<>();
                try (var stream = Files.list(abs)) {
                    stream.limit(maxEntries + 1).forEach(entries::add);
                }
                boolean clipped = entries.size() > maxEntries;
                if (clipped) entries = entries.subList(0, maxEntries);

                List<Path> dirs = new ArrayList<>();
                List<Path> files = new ArrayList<>();
                for (Path e : entries) {
                    if (Files.isDirectory(e)) dirs.add(e); else files.add(e);
                }
                dirs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
                files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));

                for (Path d : dirs)  sb.append("  [DIR]  ").append(d.getFileName()).append("\n");
                for (Path f : files) sb.append("  [FILE] ").append(f.getFileName()).append("\n");

                if (clipped) sb.append("\n(showing first ").append(maxEntries).append(" entries)\n\n");
                else sb.append("\n");
                return sb.toString();
            } catch (Exception e) {
                return "List error: " + (e.getMessage() == null ? "(no details)" : e.getMessage()) + "\n";
            }
        });
        try { return fut.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException te) { return "Directory operation timed out\n"; }
    }
}
