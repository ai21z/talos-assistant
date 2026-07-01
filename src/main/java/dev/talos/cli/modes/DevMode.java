package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Limits;
import dev.talos.runtime.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local file ops: open/show/view + ls/list/dir, bounded by Limits and Sandbox.
 *
 * <p><strong>Deprecation notice:</strong> The file read ({@code open/show/view})
 * and directory list ({@code ls/list/dir}) operations in this mode duplicate
 * the functionality of {@code talos.read_file} and {@code talos.list_dir} tools
 * in the tool registry. Once tool reliability is validated in production, these
 * operations should be delegated to the tool registry rather than re-implemented
 * here. See doc-24 Wave 3 #16.
 *
 * @see dev.talos.tools.impl.ReadFileTool
 * @see dev.talos.tools.impl.ListDirTool
 */
public final class DevMode implements Mode {
    @Override public String name() { return "dev"; }

    @Override public boolean canHandle(String raw) {
        if (raw == null) return false;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("open ") || s.startsWith("show ") || s.startsWith("view ")
                || s.startsWith("ls ") || s.startsWith("dir ")
                || isDirectListCommand(s)
                || s.equals("ls") || s.equals("dir");
    }

    @Override
    public Optional<Result> handle(String raw, Path ws, Context ctx) {
        String s = raw.trim();
        // Normalize "show me [the] X" → "show X" for correct path extraction
        s = s.replaceFirst("(?i)^show\\s+me\\s+(?:the\\s+)?", "show ");
        Limits lim = ctx.limits();

        boolean isList = isListIntent(s);
        Path target = isList && isNaturalRootListRequest(s) ? null : extractPathArg(ws, s);
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

    private static boolean isNaturalRootListRequest(String s) {
        if (s == null || s.isBlank()) return false;
        String lower = s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return lower.matches("^(?:ls|list|dir) (?:the )?(?:files|folder|directory|workspace|contents)(?: here)?$")
                || lower.matches("^(?:ls|list|dir) (?:the )?(?:files|contents) in (?:this|the current) (?:folder|directory|workspace)$")
                || lower.matches("^(?:ls|list|dir) (?:this|the current) (?:folder|directory|workspace)$");
    }

    private static boolean isDirectListCommand(String lower) {
        if (lower == null) return false;
        String s = lower.trim();
        if (s.equals("list")) return true;
        if (!s.startsWith("list ")) return false;
        if (isNaturalRootListRequest(s)) return true;

        String arg = s.substring("list ".length()).trim();
        if (arg.isEmpty()) return true;
        if (arg.matches("^(?:all|the|every|files?|folders?|directories|items|entries|names|me)\\b.*")) {
            return false;
        }
        if (isQuotedSingleArgument(arg)) return true;
        return !arg.matches(".*\\s+.*");
    }

    private static boolean isQuotedSingleArgument(String arg) {
        if (arg.length() < 2) return false;
        char first = arg.charAt(0);
        char last = arg.charAt(arg.length() - 1);
        return (first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '`' && last == '`');
    }

    private static final Pattern ARG = Pattern.compile("^[^\\s:]++\\s++(?:\"([^\"]++)\"|'([^']++)'|`([^`]++)`|(\\S++))");

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
