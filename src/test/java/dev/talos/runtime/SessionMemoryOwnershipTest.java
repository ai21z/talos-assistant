package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SessionMemoryOwnershipTest {

    @Test
    void sessionMemoryIsRuntimeOwnedAndCoreUsesConversationMemoryPort() throws Exception {
        Path runtimeMemory = Path.of("src/main/java/dev/talos/runtime/SessionMemory.java");
        Path cliMemory = Path.of("src/main/java/dev/talos/cli/repl/SessionMemory.java");
        String conversationManager = Files.readString(
                Path.of("src/main/java/dev/talos/core/context/ConversationManager.java"));
        String session = Files.readString(Path.of("src/main/java/dev/talos/runtime/Session.java"));
        String listener = Files.readString(Path.of("src/main/java/dev/talos/runtime/MemoryUpdateListener.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(Files.exists(runtimeMemory), "SessionMemory should be runtime-owned");
        assertFalse(Files.exists(cliMemory), "SessionMemory should not live under CLI ownership");
        assertTrue(conversationManager.contains("private final ConversationMemory memory;"), conversationManager);
        assertFalse(conversationManager.contains("dev.talos.cli.repl.SessionMemory"), conversationManager);
        assertFalse(conversationManager.contains("dev.talos.runtime.SessionMemory"), conversationManager);
        assertFalse(session.contains("dev.talos.cli.repl.SessionMemory"), session);
        assertFalse(listener.contains("dev.talos.cli.repl.SessionMemory"), listener);
        assertFalse(baseline.contains("|dev.talos.cli.repl.SessionMemory"), baseline);
    }
}
