package dev.loqj.cli.commands;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.ingest.FileWalker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public final class GrepCommand implements Command {
    private final Path workspace;

    public GrepCommand(Path workspace) {
        this.workspace = workspace;
    }

    @Override public CommandSpec spec() {
        return new CommandSpec("grep",
                List.of(),
                ":grep <regex>",
                "Search for regex patterns in workspace files with line numbers.");
    }

    @Override public Result execute(String args, Context ctx) {
        if (args == null || args.trim().isEmpty()) {
            return new Result.Error("Usage: :grep <regex>", 400);
        }

        String regex = args.trim();
        try {
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            var sb = new StringBuilder();
            int totalMatches = 0;
            int fileCount = 0;

            // Get files using similar filtering as the indexer
            var fs = workspace.getFileSystem();
            PathMatcher javaMatcher = fs.getPathMatcher("glob:**/*.java");
            PathMatcher txtMatcher = fs.getPathMatcher("glob:**/*.{md,txt,yaml,yml,json,properties}");

            var files = FileWalker.listFiles(workspace, p -> {
                Path rel = workspace.relativize(p);
                // Skip build, target, .git directories
                String pathStr = rel.toString().replace('\\', '/');
                if (pathStr.startsWith("build/") || pathStr.startsWith("target/") ||
                    pathStr.startsWith(".git/") || pathStr.startsWith(".idea/")) {
                    return false;
                }
                return javaMatcher.matches(rel) || txtMatcher.matches(rel);
            });

            for (Path file : files) {
                if (Files.size(file) > 100_000) continue; // Skip very large files

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
                        sb.append(String.format("  %d: %s\n", i + 1,
                            lines[i].length() > 120 ? lines[i].substring(0, 120) + "..." : lines[i]));
                        totalMatches++;

                        // Limit matches per file
                        if (totalMatches >= 50) break;
                    }
                }
                if (totalMatches >= 50) break;
            }

            if (totalMatches == 0) {
                return new Result.Info("No matches found for pattern: " + regex);
            } else {
                sb.insert(0, String.format("Found %d matches in %d files:\n", totalMatches, fileCount));
                return new Result.Ok(sb.toString());
            }

        } catch (Exception e) {
            return new Result.Error("Grep failed: " + e.getMessage(), 500);
        }
    }
}
