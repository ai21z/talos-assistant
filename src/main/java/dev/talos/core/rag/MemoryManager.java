package dev.talos.core.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.util.Hash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** File-backed memory per workspace under ~/.talos/sessions/<workspace-hash>.json */
public class MemoryManager implements AutoCloseable {
    private static final ObjectMapper M = new ObjectMapper();

    private final Path file;

    public MemoryManager(Path workspaceAbs) {
        String hex = Hash.sha1Hex(workspaceAbs.toAbsolutePath().normalize().toString());
        Path base = Path.of(System.getProperty("user.home"), ".talos", "sessions");
        try { Files.createDirectories(base); } catch (IOException ignore) {}
        this.file = base.resolve(hex + ".json");
    }

    public Memory load() {
        try {
            if (!Files.exists(file)) return new Memory("", List.of());
            Map<String,Object> root = M.readValue(Files.readString(file), new TypeReference<>() {});
            String sketch = String.valueOf(root.getOrDefault("sketch", ""));
            @SuppressWarnings("unchecked")
            List<String> entities = (List<String>) root.getOrDefault("entities", List.of());
            return new Memory(sketch, entities);
        } catch (Exception e) {
            return new Memory("", List.of());
        }
    }

    public void save(Memory m) {
        try {
            Map<String,Object> root = Map.of(
                    "sketch", m.sketch() == null ? "" : m.sketch(),
                    "entities", m.entities() == null ? List.of() : m.entities()
            );
            String s = M.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Files.writeString(file, s);
        } catch (Exception ignore) {}
    }

    @Override public void close() {}

    public record Memory(String sketch, List<String> entities) {
        public List<String> entitiesOrEmpty() { return entities == null ? List.of() : entities; }
    }
}
