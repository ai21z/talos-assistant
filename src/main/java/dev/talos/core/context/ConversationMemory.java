package dev.talos.core.context;

import dev.talos.spi.types.ChatMessage;

import java.util.List;

/** Core conversation-history storage port used by {@link ConversationManager}. */
public interface ConversationMemory {
    String get();

    List<ChatMessage> getTurns();

    void update(String userInput, String answer);

    void pruneOldest(int count);

    boolean hasContent();

    void clear();
}
