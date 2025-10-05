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

        // Strip surrounding quotes if present (handles both single and double quotes)
        if ((regex.startsWith("\"") && regex.endsWith("\"") && regex.length() > 1) ||
            (regex.startsWith("'") && regex.endsWith("'") && regex.length() > 1)) {
            regex = regex.substring(1, regex.length() - 1);
        }

        try {
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            var sb = new StringBuilder();
            int totalMatches = 0;
            int fileCount = 0;

            // Get files using filtering that matches the indexer's include patterns
            var fs = workspace.getFileSystem();

            // Create matchers for both nested files (**/*.ext) and root-level files (*.ext)
            PathMatcher codeMatcher = fs.getPathMatcher("glob:**/*.{java,kt,kts,py,rb,go,rs,cpp,c,h,hpp,js,ts,jsx,tsx,php,cs,sh,bat,ps1}");
            PathMatcher codeRootMatcher = fs.getPathMatcher("glob:*.{java,kt,kts,py,rb,go,rs,cpp,c,h,hpp,js,ts,jsx,tsx,php,cs,sh,bat,ps1}");

            PathMatcher docMatcher = fs.getPathMatcher("glob:**/*.{md,markdown,txt,html,htm,xml}");
            PathMatcher docRootMatcher = fs.getPathMatcher("glob:*.{md,markdown,txt,html,htm,xml}");

            PathMatcher configMatcher = fs.getPathMatcher("glob:**/*.{yaml,yml,json,properties,ini,conf,config,toml,env,gradle}");
            PathMatcher configRootMatcher = fs.getPathMatcher("glob:*.{yaml,yml,json,properties,ini,conf,config,toml,env,gradle}");

            var files = FileWalker.listFiles(workspace, p -> {
                Path rel = workspace.relativize(p);
                // Skip build, target, .git directories
                String pathStr = rel.toString().replace('\\', '/');
                if (pathStr.startsWith("build/") || pathStr.startsWith("target/") ||
                    pathStr.startsWith(".git/") || pathStr.startsWith(".idea/")) {
                    return false;
                }

                // Match both nested files and root-level files
                return codeMatcher.matches(rel) || codeRootMatcher.matches(rel) ||
                       docMatcher.matches(rel) || docRootMatcher.matches(rel) ||
                       configMatcher.matches(rel) || configRootMatcher.matches(rel);
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
