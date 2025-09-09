package dev.loqj.core.spi;

import java.util.List;
import java.util.Map;

public interface LanguageModel {
    /**
     * Generate the final answer. Implementations must NOT return chain-of-thought.
     */
    String chat(String system, String question, List<Map<String,String>> snippets);
}
