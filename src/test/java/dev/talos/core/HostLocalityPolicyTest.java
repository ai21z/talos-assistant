package dev.talos.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostLocalityPolicyTest {

    @Test
    void loopbackHostsAreAccepted() {
        assertTrue(HostLocalityPolicy.isLoopback("http://localhost:11434"));
        assertTrue(HostLocalityPolicy.isLoopback("http://127.0.0.1:11434"));
        assertTrue(HostLocalityPolicy.isLoopback("http://[::1]:8080"));
        assertTrue(HostLocalityPolicy.isLoopback("localhost:11434"));

        assertDoesNotThrow(() -> HostLocalityPolicy.enforceLocalOrAllowed(
                "test host",
                "http://[::1]:8080",
                false,
                "test.allow_remote"));
    }

    @Test
    void lookalikeRemoteHostsAreRejectedUnlessExplicitlyAllowed() {
        assertFalse(HostLocalityPolicy.isLoopback("http://127.0.0.1.evil.example:11434"));
        assertFalse(HostLocalityPolicy.isLoopback("http://remote.example.com:11434"));

        SecurityException ex = assertThrows(SecurityException.class,
                () -> HostLocalityPolicy.enforceLocalOrAllowed(
                        "test host",
                        "http://127.0.0.1.evil.example:11434",
                        false,
                        "test.allow_remote"));

        assertTrue(ex.getMessage().contains("Remote test host"));
        assertTrue(ex.getMessage().contains("test.allow_remote=true"));
        assertDoesNotThrow(() -> HostLocalityPolicy.enforceLocalOrAllowed(
                "test host",
                "http://127.0.0.1.evil.example:11434",
                true,
                "test.allow_remote"));
    }
}
