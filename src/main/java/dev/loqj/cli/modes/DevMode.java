package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Limits;
import dev.loqj.cli.repl.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local file ops: open/show/view + ls/list/dir, bounded by Limits and Sandbox. */
public final class DevMode implements Mode {
    @Override public String name() { return "dev"; }

    @Override public boolean canHandle(String raw) {
        if (raw == null) return false;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("open ") || s.startsWith("show ") || s.startsWith("view ")
                || s.startsWith("ls ") || s.startsWith("list ") || s.startsWith("dir ")
                || s.equals("ls") || s.equals("list") || s.equals("dir");
    }

    @Override
    public Optional<Result> handle(String raw, Path ws, Context ctx) {
        String s = raw.trim();
        // Normalize "show me [the] X" → "show X" for correct path extraction
        s = s.replaceFirst("(?i)^show\\s+me\\s+(?:the\\s+)?", "show ");
        Limits lim = ctx.limits();

        boolean isList = isListIntent(s);
        Path target = extractPathArg(ws, s);
        if (isList) {
            Path dir = (target == null ? ws : target);
            if (!ctx.sandbox().allowedPath(dir)) {
                return Optional.of(new Result.Info("Refusing to list outside workspace.\n"));
            }
            if (!Files.exists(dir)) return Optional.of(new Result.Info("Not found: " + rel(ws, dir) + "\n"));
            if (!Files.isDirectory(dir)) return Optional.of(new Result.Info("Not a directory: " + rel(ws, dir) + "\n"));

            List<Path> entries = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.limit(lim.dirEntriesMax() + 1L).forEach(entries::add);
            } catch (Exception e) {
                return Optional.of(new Result.Error("List error: " + safe(e.getMessage()), 500));
            }
            boolean clipped = entries.size() > lim.dirEntriesMax();
            if (clipped) entries = entries.subList(0, lim.dirEntriesMax());

            List<Path> dirs = new ArrayList<>(), files = new ArrayList<>();
            for (Path p : entries) {
                if (Files.isDirectory(p)) dirs.add(p); else files.add(p);
            }
            dirs.sort(Comparator.comparing(x -> x.getFileName().toString().toLowerCase(Locale.ROOT)));
            files.sort(Comparator.comparing(x -> x.getFileName().toString().toLowerCase(Locale.ROOT)));

            StringBuilder out = new StringBuilder();
            out.append("\n── dir: ").append(rel(ws, dir)).append("\n\n");
            for (Path d : dirs)  out.append("  [DIR]  ").append(d.getFileName()).append("\n");
            for (Path f : files) out.append("  [FILE] ").append(f.getFileName()).append("\n");
            if (clipped) out.append("\n(showing first ").append(lim.dirEntriesMax()).append(" entries)\n\n");
            else out.append("\n");
            return Optional.of(new Result.Ok(out.toString()));
        }

        // open/show/view -> file read
        if (target == null) return Optional.of(new Result.Info("File not found or invalid path.\n"));
        if (!ctx.sandbox().allowedPath(target)) {
            return Optional.of(new Result.Info("Refusing to read outside workspace.\n"));
        }
        if (!Files.exists(target)) return Optional.of(new Result.Info("Not found: " + rel(ws, target) + "\n"));
        if (Files.isDirectory(target)) {
            return Optional.of(new Result.Info("Path is a directory. Try 'ls " + rel(ws, target) + "'.\n"));
        }

        StringBuilder out = new StringBuilder();
        try {
            long size = Files.size(target);
            out.append("\n── file: ").append(rel(ws, target)).append(" (").append(String.format("%,d", size)).append(" bytes)\n\n");

            int bytes = 0, lines = 0;
            try (var reader = Files.newBufferedReader(target)) {
                String ln;
                while ((ln = reader.readLine()) != null && lines < lim.fileLinesMax() && bytes < lim.fileBytesMax()) {
                    out.append(ln).append("\n");
                    lines++;
                    bytes += ln.length() + 1;
                }
            }
            if (lines >= lim.fileLinesMax() || size > lim.fileBytesMax()) {
                out.append("\n… (truncated)\n\n");
            } else {
                out.append("\n");
            }
        } catch (Exception e) {
            return Optional.of(new Result.Error("Read error: " + safe(e.getMessage()), 500));
        }
        return Optional.of(new Result.Ok(out.toString()));
    }

    private static String rel(Path base, Path p) {
        try { return base.relativize(p).toString().replace('\\','/'); }
        catch(Exception e){ return p.getFileName().toString(); }
    }

    private static boolean isListIntent(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.startsWith("ls") || lower.startsWith("list") || lower.startsWith("dir");
    }

    private static final Pattern ARG = Pattern.compile("^[^\\s:]++\\s++(?:\"([^\"]++)\"|'([^']++)'|`([^`++]++)`|(\\S++))");

    private static Path extractPathArg(Path ws, String s) {
        Matcher m = ARG.matcher(s);
        if (m.find()) {
            String raw = m.group(1); if (raw == null) raw = m.group(2);
            if (raw == null) raw = m.group(3);
            if (raw == null) raw = m.group(4);
            if (raw != null && !raw.isBlank()) {
                Path cand = Path.of(expandTilde(raw));
                if (!cand.isAbsolute()) cand = ws.resolve(cand);
                return cand.normalize();
            }
        }
        return null;
    }

    private static String expandTilde(String raw) {
        if (raw == null) return null;
        if (raw.equals("~")) return home();
        if (raw.startsWith("~" + java.io.File.separator) || raw.startsWith("~/")) {
            return home() + raw.substring(1);
        }
        return raw;
    }
    private static String home() {
        String h = System.getProperty("user.home");
        return (h == null || h.isBlank()) ? System.getProperty("user.dir", ".") : h;
    }

    private static String safe(String msg) {
        if (msg == null) return "(no details)";
        return msg.replaceAll("([A-Za-z]:)?[\\\\/][^\\\\/]+(?:[\\\\/][^\\\\/]+)*", "[path]");
    }
}
