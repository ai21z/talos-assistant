package dev.talos.runtime.verification;

import dev.talos.core.security.NoFollowPathPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** No-follow access boundary for workspace verification inputs. */
final class StaticWebFileAccess {
    private StaticWebFileAccess() {}

    static String readString(Path root, Path requested) throws IOException {
        return bind(root).readString(requested, StandardCharsets.UTF_8);
    }

    static BufferedReader newBufferedReader(Path root, Path requested) throws IOException {
        return new BufferedReader(new InputStreamReader(
                bind(root).openRegularFile(requested),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)));
    }

    static boolean isRegularFile(Path root, Path requested) {
        try {
            return bind(root).inspect(requested)
                    == NoFollowPathPolicy.EntryType.REGULAR_FILE;
        } catch (IOException rejected) {
            return false;
        }
    }

    static boolean isDirectory(Path root, Path requested) {
        try {
            return bind(root).inspect(requested)
                    == NoFollowPathPolicy.EntryType.DIRECTORY;
        } catch (IOException rejected) {
            return false;
        }
    }

    static NoFollowPathPolicy.EntryType inspect(Path root, Path requested)
            throws IOException {
        return bind(root).inspect(requested);
    }

    static List<Path> immediateRegularFiles(Path root) throws IOException {
        NoFollowPathPolicy.GuardedRoot files = bind(root);
        List<Path> regular = new ArrayList<>();
        for (NoFollowPathPolicy.TreeEntry entry : files.list(files.path())) {
            if (entry.type() == NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                regular.add(entry.path());
            }
        }
        return List.copyOf(regular);
    }

    static Set<String> immediateRegularFileNames(Path root) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (Path file : immediateRegularFiles(root)) {
            names.add(file.getFileName().toString());
        }
        return Set.copyOf(names);
    }

    private static NoFollowPathPolicy.GuardedRoot bind(Path root) throws IOException {
        return NoFollowPathPolicy.bindExistingDirectory(root);
    }
}
