package dev.talos.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.*;

class WindowsDpapiKeyCustodyTest {
    @TempDir
    Path tempDir;

    @Test
    void protectFailureDuringNewKeyCreationDoesNotWritePlaintextMasterKey() {
        Path masterKey = tempDir.resolve(".master.key");
        byte[] generated = deterministicKey();
        WindowsDpapiKeyCustody custody = new WindowsDpapiKeyCustody(true, new FailingDpapiClient("protect"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> custody.loadOrCreateMasterKey(masterKey, () -> generated.clone()));

        assertTrue(error.getMessage().contains("DPAPI"), error.getMessage());
        assertFalse(Files.exists(masterKey), "failed Windows protect must not write a raw plaintext key");
    }

    @Test
    void protectFailureDuringLegacyMigrationLeavesLegacyKeyUntouched() throws Exception {
        Path masterKey = tempDir.resolve(".master.key");
        byte[] legacy = deterministicKey();
        Files.write(masterKey, legacy, CREATE, TRUNCATE_EXISTING, WRITE);
        WindowsDpapiKeyCustody custody = new WindowsDpapiKeyCustody(true, new FailingDpapiClient("protect"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> custody.loadOrCreateMasterKey(masterKey, () -> {
                    fail("existing legacy key should be used instead of generating a new key");
                    return new byte[32];
                }));

        assertTrue(error.getMessage().contains("DPAPI"), error.getMessage());
        assertArrayEquals(legacy, Files.readAllBytes(masterKey),
                "failed migration must leave the original raw key untouched for explicit recovery");
        assertFalse(Files.exists(tempDir.resolve(".master.key.legacy")),
                "migration must not create a persistent plaintext backup");
    }

    @Test
    void unprotectFailureForProtectedBlobDoesNotOverwriteIt() throws Exception {
        Path masterKey = tempDir.resolve(".master.key");
        byte[] blob = protectedBlob(new byte[] { 9, 8, 7, 6 });
        Files.write(masterKey, blob, CREATE, TRUNCATE_EXISTING, WRITE);
        WindowsDpapiKeyCustody custody = new WindowsDpapiKeyCustody(true, new FailingDpapiClient("unprotect"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> custody.loadOrCreateMasterKey(masterKey, () -> deterministicKey()));

        assertTrue(error.getMessage().contains("DPAPI"), error.getMessage());
        assertArrayEquals(blob, Files.readAllBytes(masterKey),
                "failed unprotect must not replace the existing protected master-key blob");
    }

    private static byte[] deterministicKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (0x30 + i);
        }
        return key;
    }

    private static byte[] protectedBlob(byte[] payload) {
        byte[] out = Arrays.copyOf(
                WindowsDpapiKeyCustody.PROTECTED_MASTER_KEY_HEADER,
                WindowsDpapiKeyCustody.PROTECTED_MASTER_KEY_HEADER.length + payload.length);
        System.arraycopy(payload, 0, out, WindowsDpapiKeyCustody.PROTECTED_MASTER_KEY_HEADER.length, payload.length);
        return out;
    }

    private static final class FailingDpapiClient implements WindowsDpapiKeyCustody.DpapiClient {
        private final String failingOperation;

        private FailingDpapiClient(String failingOperation) {
            this.failingOperation = failingOperation;
        }

        @Override
        public byte[] protect(byte[] plaintext) {
            if ("protect".equals(failingOperation)) {
                throw new IllegalStateException("simulated DPAPI protect failure");
            }
            return plaintext.clone();
        }

        @Override
        public byte[] unprotect(byte[] protectedBlob) {
            if ("unprotect".equals(failingOperation)) {
                throw new IllegalStateException("simulated DPAPI unprotect failure");
            }
            return protectedBlob.clone();
        }
    }
}
