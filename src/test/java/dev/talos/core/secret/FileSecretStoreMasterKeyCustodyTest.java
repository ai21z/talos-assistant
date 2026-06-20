package dev.talos.core.secret;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.*;

class FileSecretStoreMasterKeyCustodyTest {
    private static final byte[] DPAPI_MASTER_KEY_HEADER =
            "TALOS-DPAPI-MASTER-KEY-1\n".getBytes(StandardCharsets.US_ASCII);
    private static final String ENTRY_MAGIC = "LQJ1";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    @TempDir
    Path tempDir;

    @Test
    void deleteMissingSecretDoesNotCreateMasterKey() throws Exception {
        FileSecretStore store = new FileSecretStore(tempDir);

        assertFalse(store.delete("default", "missing"));

        assertFalse(Files.exists(tempDir.resolve(".master.key")),
                "slash-command registration and missing deletes must not create key material");
    }

    @Test
    void getMissingSecretDoesNotCreateMasterKey() throws Exception {
        FileSecretStore store = new FileSecretStore(tempDir);

        assertTrue(store.get("default", "missing").isEmpty());

        assertFalse(Files.exists(tempDir.resolve(".master.key")),
                "missing reads must not create or DPAPI-load key material");
    }

    @Test
    void freshWindowsStoreWritesDpapiProtectedMasterKeyBlob() throws Exception {
        assumeWindows();
        FileSecretStore store = new FileSecretStore(tempDir);

        store.put("default", "api", "value".toCharArray());

        byte[] masterFile = Files.readAllBytes(tempDir.resolve(".master.key"));
        assertDpapiBlob(masterFile);
        assertNotEquals(32, masterFile.length, "Windows master key file must not be legacy raw AES bytes");
        assertArrayEquals("value".toCharArray(), store.get("default", "api").orElseThrow());
    }

    @Test
    void protectedWindowsMasterKeyCanBeReopened() throws Exception {
        assumeWindows();
        new FileSecretStore(tempDir).put("default", "api", "value".toCharArray());

        Optional<char[]> reopened = new FileSecretStore(tempDir).get("default", "api");

        assertTrue(reopened.isPresent());
        assertArrayEquals("value".toCharArray(), reopened.orElseThrow());
    }

    @Test
    void legacyRawWindowsMasterKeyMigratesWithoutReencryptingEntriesOrLeavingPlaintextBackup() throws Exception {
        assumeWindows();
        byte[] legacyRawKey = deterministicLegacyKey();
        Files.write(tempDir.resolve(".master.key"), legacyRawKey, CREATE, TRUNCATE_EXISTING, WRITE);
        writeLegacyEntry(tempDir, "default", "legacy", legacyRawKey, "legacy-value");

        Optional<char[]> migrated = new FileSecretStore(tempDir).get("default", "legacy");

        assertTrue(migrated.isPresent());
        assertArrayEquals("legacy-value".toCharArray(), migrated.orElseThrow());
        byte[] migratedMaster = Files.readAllBytes(tempDir.resolve(".master.key"));
        assertDpapiBlob(migratedMaster);
        assertFalse(Arrays.equals(legacyRawKey, migratedMaster),
                "migration must replace raw AES key bytes with a protected blob");
        assertFalse(Files.exists(tempDir.resolve(".master.key.legacy")),
                "successful migration must not leave a plaintext raw-key backup beside ciphertext");
    }

    private static void assumeWindows() {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase().contains("win"),
                "DPAPI CurrentUser custody is Windows-specific");
    }

    private static void assertDpapiBlob(byte[] actual) {
        assertTrue(actual.length > DPAPI_MASTER_KEY_HEADER.length + 32,
                "protected master-key blob should include a header and DPAPI payload");
        assertArrayEquals(
                DPAPI_MASTER_KEY_HEADER,
                Arrays.copyOf(actual, DPAPI_MASTER_KEY_HEADER.length),
                "protected master-key file must start with the DPAPI version header");
    }

    private static byte[] deterministicLegacyKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    private static void writeLegacyEntry(
            Path baseDir,
            String scope,
            String key,
            byte[] rawKey,
            String value) throws Exception {
        Path dir = baseDir.resolve(scope);
        Files.createDirectories(dir);
        Path file = dir.resolve(key + ".bin");
        byte[] iv = new byte[GCM_IV_LEN];
        for (int i = 0; i < iv.length; i++) {
            iv[i] = (byte) (0x41 + i);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(rawKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD((scope + ":" + key).getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

        ByteBuffer out = ByteBuffer.allocate(ENTRY_MAGIC.length() + 4 + iv.length + encrypted.length);
        out.put(ENTRY_MAGIC.getBytes(StandardCharsets.US_ASCII));
        out.putInt(iv.length);
        out.put(iv);
        out.put(encrypted);
        Files.write(file, out.array(), CREATE, TRUNCATE_EXISTING, WRITE);
    }
}
