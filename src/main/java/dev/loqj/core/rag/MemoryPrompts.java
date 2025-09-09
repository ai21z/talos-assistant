package dev.loqj.core.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loqj.core.llm.LlmClient;

import java.util.List;
import java.util.Map;

final class MemoryPrompts {
    private MemoryPrompts() {}
    private static final ObjectMapper M = new ObjectMapper();

    static MemoryManager.Memory refresh(MemoryManager.Memory previous,
                                        String question,
                                        String answer,
                                        List<String> citations,
                                        LlmClient llm) {
        String sys = """
            You maintain short conversation memory for a local developer CLI.
            Always return compact JSON with exactly these keys:
            {
              "sketch": "<one-sentence recap of the user's current goal/context>",
              "entities": ["Token", "Class", "File", ...]   // at most 6 items, plain strings
            }
            Do NOT include chain-of-thought or any fields other than those shown above.
            """;

        String user = """
            Prior sketch:
            %s

            Prior entities:
            %s

            Latest turn:
            Q: %s
            A: %s

            Citations:
            %s

            Return only JSON exactly matching the schema.
            """.formatted(
                safe(previous.sketch()),
                (previous.entities() == null || previous.entities().isEmpty()) ? "[]" : previous.entities().toString(),
                safe(question),
                safe(answer),
                (citations == null || citations.isEmpty()) ? "[]" : String.join(", ", citations)
        );

        try {
            String content = llm.chatPlain(sys, user); // plain text, no JSON wrapper
            Map<String, Object> obj = M.readValue(content.strip(), new TypeReference<>() {});
            String sketch = String.valueOf(obj.getOrDefault("sketch", previous.sketch() == null ? "" : previous.sketch()));
            @SuppressWarnings("unchecked")
            List<String> entities = (List<String>) obj.getOrDefault("entities", previous.entities());
            if (entities != null && entities.size() > 6) entities = entities.subList(0, 6);
            return new MemoryManager.Memory(sketch, entities == null ? List.of() : entities);
        } catch (Exception e) {
            return previous;
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
