package dev.talos.qualification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class QualificationJson {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private QualificationJson() {}

    static <T> T read(Path path, Class<T> type) throws IOException {
        return MAPPER.readValue(path.toFile(), type);
    }

    static <T> T read(byte[] bytes, Class<T> type) throws IOException {
        return MAPPER.readValue(bytes, type);
    }

    static JsonNode readTree(Path path) throws IOException {
        return MAPPER.readTree(path.toFile());
    }

    static String write(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize qualification JSON", e);
        }
    }

    static void write(Path path, Object value) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, write(value), StandardCharsets.UTF_8);
    }

    static byte[] bytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

}
