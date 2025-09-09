package dev.loqj.core.ingest;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class FileWalker {
    public static List<Path> listFiles(Path root, Predicate<Path> include) throws IOException {
        try (var s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).filter(include).collect(Collectors.toList());
        }
    }
}
