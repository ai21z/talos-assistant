package dev.talos.core.llm;

import dev.talos.core.cache.CacheDb;
import dev.talos.core.spi.LanguageModel;
import dev.talos.core.util.Hash;

import java.util.List;
import java.util.Map;

public class CachingLanguageModel implements LanguageModel, AutoCloseable {
    private final LanguageModel delegate;
    private final CacheDb db;
    private final String modelName;

    public CachingLanguageModel(LanguageModel delegate, CacheDb db, String modelName) {
        this.delegate = delegate;
        this.db = db;
        this.modelName = modelName;
    }

    @Override
    public String chat(String system, String question, List<Map<String, String>> snippets) {
        StringBuilder sb = new StringBuilder();
        sb.append("m=").append(modelName).append("\n");
        sb.append("sys=").append(system).append("\n");
        sb.append("q=").append(question).append("\n");
        for (var s : snippets) {
            sb.append("p=").append(s.getOrDefault("path","")).append("\n");
            String t = s.getOrDefault("text","");
            if (t.length() > 256) t = t.substring(0,256);
            sb.append("t=").append(t).append("\n");
        }
        String key = Hash.sha1Hex(sb.toString());

        String cached = db.getAnswer(key);
        if (cached != null && !cached.isBlank()) return cached;

        String ans = delegate.chat(system, question, snippets);
        if (ans != null && !ans.isBlank()) db.putAnswer(key, ans);
        return ans;
    }

    @Override public void close() { db.close(); }
}
