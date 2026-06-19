package dev.talos.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHostLocalityPolicyTest {

    @Test
    void loopbackHostsAreAccepted() {
        assertTrue(ChatHostLocalityPolicy.isLoopback("http://localhost:11434"));
        assertTrue(ChatHostLocalityPolicy.isLoopback("http://127.0.0.1:11434"));
        assertTrue(ChatHostLocalityPolicy.isLoopback("http://[::1]:8080"));
        assertTrue(ChatHostLocalityPolicy.isLoopback("localhost:11434"));

        assertDoesNotThrow(() -> ChatHostLocalityPolicy.enforceLocalOrAllowed(
                "test",
                "http://[::1]:8080",
                false,
                "test.allow_remote"));
    }

    @Test
    void lookalikeRemoteHostsAreRejectedUnlessExplicitlyAllowed() {
        assertFalse(ChatHostLocalityPolicy.isLoopback("http://127.0.0.1.evil.example:11434"));
        assertFalse(ChatHostLocalityPolicy.isLoopback("http://remote.example.com:11434"));

        SecurityException ex = assertThrows(SecurityException.class,
                () -> ChatHostLocalityPolicy.enforceLocalOrAllowed(
                        "test",
                        "http://127.0.0.1.evil.example:11434",
                        false,
                        "test.allow_remote"));

        assertTrue(ex.getMessage().contains("Remote test chat host"));
        assertTrue(ex.getMessage().contains("test.allow_remote=true"));
        assertDoesNotThrow(() -> ChatHostLocalityPolicy.enforceLocalOrAllowed(
                "test",
                "http://127.0.0.1.evil.example:11434",
                true,
                "test.allow_remote"));
    }
}
