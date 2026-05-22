package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.extract.DocumentExtractionResult;
import dev.talos.core.extract.DocumentExtractionService;
import dev.talos.core.extract.DocumentExtractionStatus;
import dev.talos.core.ingest.FileWalker;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.core.ingest.UnsupportedDocumentFormats;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GrepCommand implements Command {
    private final Path workspace;

    public GrepCommand(Path workspace) {
        this.workspace = workspace;
    }

    @Override public CommandSpec spec() {
        return new CommandSpec("grep",
                List.of(),
                "/grep <regex>",
                "Search workspace files.",
                CommandGroup.KNOWLEDGE);
    }

    @Override public Result execute(String args, Context ctx) {
        if (args == null || args.trim().isEmpty()) {
            return new Result.Error("Usage: /grep <regex>", 400);
        }

        String regex = args.trim();

        // Strip one layer of surrounding quotes if present (handles both single and double quotes)
        if (regex.length() > 1) {
            if ((regex.startsWith("\"") && regex.endsWith("\"")) ||
                (regex.startsWith("'") && regex.endsWith("'"))) {
                regex = regex.substring(1, regex.length() - 1);
            }
        }

        try {
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            var sb = new StringBuilder();
            int totalMatches = 0;
            int fileCount = 0;
            int skippedProtected = 0;
            java.util.ArrayList<String> skippedUnsupported = new java.util.ArrayList<>();
            boolean privateMode = ProtectedReadScopePolicy.privateMode(cfg(ctx));

            // Get files using broader filtering that includes scripts, configs, and markup
            var fs = workspace.getFileSystem();

            // Broader file patterns matching user's local validated behavior
            // Code files (source, scripts, shell)
            PathMatcher codeMatcher = fs.getPathMatcher("glob:**/*.{java,kt,kts,py,rb,go,rs,cpp,c,h,hpp,js,ts,jsx,tsx,php,cs,sh,bat,cmd,ps1,psm1,gradle}");
            PathMatcher codeRootMatcher = fs.getPathMatcher("glob:*.{java,kt,kts,py,rb,go,rs,cpp,c,h,hpp,js,ts,jsx,tsx,php,cs,sh,bat,cmd,ps1,psm1,gradle}");

            // Documentation and markup files
            PathMatcher docMatcher = fs.getPathMatcher("glob:**/*.{md,markdown,txt,html,htm,xml,css,scss,sass,less}");
            PathMatcher docRootMatcher = fs.getPathMatcher("glob:*.{md,markdown,txt,html,htm,xml,css,scss,sass,less}");

            // Configuration files
            PathMatcher configMatcher = fs.getPathMatcher("glob:**/*.{yaml,yml,json,properties,ini,conf,config,toml,env}");
            PathMatcher configRootMatcher = fs.getPathMatcher("glob:*.{yaml,yml,json,properties,ini,conf,config,toml,env}");

            var files = FileWalker.listFiles(workspace, p -> {
                Path rel = workspace.relativize(p);
                // Skip build, target, .git directories
                String pathStr = rel.toString().replace('\\', '/');
                if (pathStr.startsWith("build/") || pathStr.startsWith("target/") ||
                    pathStr.startsWith(".git/") || pathStr.startsWith(".idea/")) {
                    return false;
                }
                FileCapabilityPolicy.FormatInfo capability = FileCapabilityPolicy
                        .describe(p, cfg(ctx))
                        .orElse(null);
                if (ProtectedContentPolicy.isProtectedPath(workspace, p)
                        || capability != null && capability.enabled()
                        || UnsupportedDocumentFormats.isUnsupported(p)) {
                    return true;
                }

                // Match both nested files and root-level files
                return codeMatcher.matches(rel) || codeRootMatcher.matches(rel) ||
                       docMatcher.matches(rel) || docRootMatcher.matches(rel) ||
                       configMatcher.matches(rel) || configRootMatcher.matches(rel);
            });

            for (Path file : files) {
                if (Files.size(file) > 100_000) continue; // Skip very large files
                if (ProtectedContentPolicy.isProtectedPath(workspace, file)) {
                    skippedProtected++;
                    continue;
                }
                FileCapabilityPolicy.FormatInfo capability = FileCapabilityPolicy
                        .describe(file, cfg(ctx))
                        .orElse(null);
                if (capability != null && capability.enabled()) {
                    DocumentExtractionResult extraction = new DocumentExtractionService(cfg(ctx))
                            .extract(DocumentExtractionRequest.search(file, workspace));
                    if (extraction.status() != DocumentExtractionStatus.SUCCESS
                            && extraction.status() != DocumentExtractionStatus.PARTIAL) {
                        skippedUnsupported.add(workspace.relativize(file).toString().replace('\\', '/'));
                        continue;
                    }

                    String[] lines = extraction.safeText().split("\\R", -1);
                    boolean hasMatches = false;
                    for (int i = 0; i < lines.length; i++) {
                        Matcher m = pattern.matcher(lines[i]);
                        if (m.find()) {
                            if (!hasMatches) {
                                sb.append("\n").append(workspace.relativize(file)).append(":\n");
                                hasMatches = true;
                                fileCount++;
                            }
                            String safeLine = safeExtractedSearchLine(lines[i], privateMode, extraction);
                            sb.append(String.format("  %d: %s\n", i + 1,
                                    safeLine.length() > 120 ? safeLine.substring(0, 120) + "..." : safeLine));
                            totalMatches++;
                            if (totalMatches >= 50) break;
                        }
                    }
                    if (totalMatches >= 50) break;
                    continue;
                }
                if (UnsupportedDocumentFormats.isUnsupported(file) || looksLikeBinary(file)) {
                    skippedUnsupported.add(workspace.relativize(file).toString().replace('\\', '/'));
                    continue;
                }

                String content = Files.readString(file);
                String[] lines = content.split("\\r?\\n");
                boolean hasMatches = false;

                for (int i = 0; i < lines.length; i++) {
                    Matcher m = pattern.matcher(lines[i]);
                    if (m.find()) {
                        if (!hasMatches) {
                            sb.append("\n").append(workspace.relativize(file)).append(":\n");
                            hasMatches = true;
                            fileCount++;
                        }
                        String safeLine = safeSearchLine(lines[i], privateMode);
                        sb.append(String.format("  %d: %s\n", i + 1,
                            safeLine.length() > 120 ? safeLine.substring(0, 120) + "..." : safeLine));
                        totalMatches++;

                        // Limit matches per file
                        if (totalMatches >= 50) break;
                    }
                }
                if (totalMatches >= 50) break;
            }

            if (totalMatches == 0) {
                return new Result.Info("No matches found in searchable non-protected text files for pattern: "
                        + ProtectedContentPolicy.sanitizeText(regex)
                        + ProtectedContentPolicy.protectedContentNote(skippedProtected)
                        + unsupportedNote(skippedUnsupported));
            } else {
                sb.insert(0, String.format("Found %d matches in %d files:\n", totalMatches, fileCount));
                sb.append(ProtectedContentPolicy.protectedContentNote(skippedProtected));
                sb.append(unsupportedNote(skippedUnsupported));
                return new Result.Ok(sb.toString());
            }

        } catch (Exception e) {
            return new Result.Error("Grep failed: " + e.getMessage(), 500);
        }
    }

    private static String unsupportedNote(List<String> skippedUnsupported) {
        if (skippedUnsupported == null || skippedUnsupported.isEmpty()) return "";
        int limit = Math.min(5, skippedUnsupported.size());
        StringBuilder out = new StringBuilder();
        out.append("\n\nSearch was limited to searchable text files. Skipped unsupported/binary files: ");
        out.append(String.join(", ", skippedUnsupported.subList(0, limit)));
        if (skippedUnsupported.size() > limit) {
            out.append(", ... ").append(skippedUnsupported.size() - limit).append(" more");
        }
        out.append(".");
        return out.toString();
    }

    private static Config cfg(Context ctx) {
        return ctx == null || ctx.cfg() == null ? new Config(null) : ctx.cfg();
    }

    private static String safeSearchLine(String line, boolean privateMode) {
        String safeLine = ProtectedContentPolicy.sanitizeSearchLine(line);
        if (privateMode && !safeLine.equals(line)) {
            return "[line content withheld by private-mode search policy]";
        }
        return safeLine;
    }

    private static String safeExtractedSearchLine(
            String line,
            boolean privateMode,
            DocumentExtractionResult extraction) {
        if (privateMode && extraction != null && !extraction.modelHandoffAllowed()) {
            return "[extracted document match withheld from model context by private-document policy]";
        }
        return safeSearchLine(line, privateMode);
    }

    private static boolean looksLikeBinary(Path file) {
        try (var is = Files.newInputStream(file)) {
            byte[] head = is.readNBytes(512);
            int nullCount = 0;
            for (byte b : head) {
                if (b == 0) nullCount++;
            }
            return nullCount > 4;
        } catch (IOException e) {
            return true;
        }
    }
}
