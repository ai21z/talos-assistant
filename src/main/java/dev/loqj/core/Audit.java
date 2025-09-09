package dev.loqj.core;
 
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.*; import java.nio.file.*; import java.time.*; import java.util.*;
 
public class Audit {
    private final Path logPath = Paths.get(System.getProperty("user.home"), ".loqj", "audit.jsonl");
    private final ObjectMapper mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
 
    public void log(String event, Map<String,Object> payload) {
        try {
            Files.createDirectories(logPath.getParent());
            var rec = new LinkedHashMap<String,Object>();
            rec.put("ts", Instant.now().toString());
            rec.put("event", event);
            rec.put("payload", payload == null ? Map.of() : payload);
            String line = mapper.writeValueAsString(rec) + System.lineSeparator();
            Files.writeString(logPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new RuntimeException(e); }
    }
}
