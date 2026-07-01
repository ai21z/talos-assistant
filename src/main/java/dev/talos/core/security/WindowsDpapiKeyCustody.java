package dev.talos.core.security;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Custody for the FileSecretStore master key.
 *
 * <p>Windows protects the raw 32-byte AES key using DPAPI CurrentUser before it
 * reaches disk. Non-Windows keeps the legacy raw-key behavior until platform
 * key custody is explicitly implemented for those hosts.
 */
public final class WindowsDpapiKeyCustody {
    static final byte[] PROTECTED_MASTER_KEY_HEADER =
            "TALOS-DPAPI-MASTER-KEY-1\n".getBytes(StandardCharsets.US_ASCII);

    private static final int RAW_MASTER_KEY_BYTES = 32;
    private static final Duration DPAPI_TIMEOUT = Duration.ofSeconds(15);

    private final boolean windows;
    private final DpapiClient dpapiClient;

    public WindowsDpapiKeyCustody() {
        this(isWindows(), new PowerShellDpapiClient());
    }

    WindowsDpapiKeyCustody(boolean windows, DpapiClient dpapiClient) {
        this.windows = windows;
        this.dpapiClient = Objects.requireNonNull(dpapiClient, "dpapiClient");
    }

    public byte[] loadOrCreateMasterKey(Path path, Supplier<byte[]> keyGenerator) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(keyGenerator, "keyGenerator");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(path)) {
                return loadExisting(path);
            }
            byte[] raw = requireRawKey(keyGenerator.get(), "generated master key");
            if (!windows) {
                Files.write(path, raw);
                trySetOwnerOnly(path);
                return raw;
            }
            writeProtectedBlobAtomically(path, raw);
            return raw;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load or create master key: " + safeMessage(e), e);
        }
    }

    private byte[] loadExisting(Path path) throws Exception {
        byte[] stored = Files.readAllBytes(path);
        if (hasProtectedHeader(stored)) {
            if (!windows) {
                throw new IllegalStateException(
                        "DPAPI-protected master key cannot be loaded on this non-Windows host");
            }
            byte[] raw = null;
            try {
                raw = dpapiClient.unprotect(payload(stored));
                return requireRawKey(raw, "DPAPI-unprotected master key");
            } catch (RuntimeException e) {
                throw new IllegalStateException("Unable to unprotect master key with DPAPI: " + safeMessage(e), e);
            } finally {
                if (raw != null) {
                    Arrays.fill(raw, (byte) 0);
                }
            }
        }
        if (stored.length == RAW_MASTER_KEY_BYTES) {
            if (!windows) {
                trySetOwnerOnly(path);
                return stored;
            }
            return migrateLegacyRawKey(path, stored);
        }
        throw new IllegalStateException("Unsupported master key format");
    }

    private byte[] migrateLegacyRawKey(Path path, byte[] legacyRaw) throws Exception {
        byte[] raw = requireRawKey(legacyRaw, "legacy raw master key");
        writeProtectedBlobAtomically(path, raw);
        return raw;
    }

    private void writeProtectedBlobAtomically(Path path, byte[] raw) throws Exception {
        byte[] protectedPayload;
        try {
            protectedPayload = dpapiClient.protect(raw);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unable to protect master key with DPAPI: " + safeMessage(e), e);
        }
        byte[] protectedBlob = withHeader(protectedPayload);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temp, protectedBlob, CREATE_NEW, WRITE);
            trySetOwnerOnly(temp);
            verifyProtectedBlob(Files.readAllBytes(temp), raw);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            trySetOwnerOnly(path);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // Preserve the original failure; temp contains only a protected DPAPI blob.
            }
            throw e;
        } finally {
            Arrays.fill(protectedPayload, (byte) 0);
            Arrays.fill(protectedBlob, (byte) 0);
        }
    }

    private void verifyProtectedBlob(byte[] protectedBlob, byte[] expectedRaw) {
        if (!hasProtectedHeader(protectedBlob)) {
            throw new IllegalStateException("DPAPI master-key blob is missing its version header");
        }
        byte[] roundTrip = null;
        try {
            roundTrip = requireRawKey(dpapiClient.unprotect(payload(protectedBlob)), "DPAPI round-trip master key");
            if (!Arrays.equals(expectedRaw, roundTrip)) {
                throw new IllegalStateException("DPAPI master-key round trip did not preserve the key bytes");
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unable to verify DPAPI-protected master key: " + safeMessage(e), e);
        } finally {
            if (roundTrip != null) {
                Arrays.fill(roundTrip, (byte) 0);
            }
        }
    }

    private static byte[] withHeader(byte[] protectedPayload) {
        byte[] out = Arrays.copyOf(PROTECTED_MASTER_KEY_HEADER,
                PROTECTED_MASTER_KEY_HEADER.length + protectedPayload.length);
        System.arraycopy(protectedPayload, 0, out, PROTECTED_MASTER_KEY_HEADER.length, protectedPayload.length);
        return out;
    }

    private static boolean hasProtectedHeader(byte[] stored) {
        if (stored.length <= PROTECTED_MASTER_KEY_HEADER.length) {
            return false;
        }
        for (int i = 0; i < PROTECTED_MASTER_KEY_HEADER.length; i++) {
            if (stored[i] != PROTECTED_MASTER_KEY_HEADER[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] payload(byte[] stored) {
        return Arrays.copyOfRange(stored, PROTECTED_MASTER_KEY_HEADER.length, stored.length);
    }

    private static byte[] requireRawKey(byte[] raw, String description) {
        if (raw == null || raw.length != RAW_MASTER_KEY_BYTES) {
            throw new IllegalStateException(description + " must be exactly 32 bytes");
        }
        return raw.clone();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void trySetOwnerOnly(Path p) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(p, perms);
        } catch (Exception ignore) {
            // Non-POSIX (Windows): DPAPI owns key custody; filesystem ACL tuning is best-effort.
        }
    }

    private static String safeMessage(Throwable e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        if (message.length() > 240) {
            return message.substring(0, 240) + "...";
        }
        return message.isBlank()
                ? (e == null ? "unknown error" : e.getClass().getSimpleName())
                : message;
    }

    interface DpapiClient {
        byte[] protect(byte[] plaintext);

        byte[] unprotect(byte[] protectedBlob);
    }

    private static final class PowerShellDpapiClient implements DpapiClient {
        private static final String PROTECT_SCRIPT = """
                $ErrorActionPreference = 'Stop'
                try { Add-Type -AssemblyName System.Security } catch { }
                $inputText = [Console]::In.ReadToEnd().Trim()
                $bytes = [Convert]::FromBase64String($inputText)
                $scope = [System.Security.Cryptography.DataProtectionScope]::CurrentUser
                $out = [System.Security.Cryptography.ProtectedData]::Protect($bytes, $null, $scope)
                [Console]::Out.Write([Convert]::ToBase64String($out))
                """;

        private static final String UNPROTECT_SCRIPT = """
                $ErrorActionPreference = 'Stop'
                try { Add-Type -AssemblyName System.Security } catch { }
                $inputText = [Console]::In.ReadToEnd().Trim()
                $bytes = [Convert]::FromBase64String($inputText)
                $scope = [System.Security.Cryptography.DataProtectionScope]::CurrentUser
                $out = [System.Security.Cryptography.ProtectedData]::Unprotect($bytes, $null, $scope)
                [Console]::Out.Write([Convert]::ToBase64String($out))
                """;

        @Override
        public byte[] protect(byte[] plaintext) {
            return run("protect", PROTECT_SCRIPT, plaintext);
        }

        @Override
        public byte[] unprotect(byte[] protectedBlob) {
            return run("unprotect", UNPROTECT_SCRIPT, protectedBlob);
        }

        private byte[] run(String operation, String script, byte[] input) {
            Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-Command",
                        script);
                builder.redirectInput(ProcessBuilder.Redirect.PIPE);
                builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
                builder.redirectError(ProcessBuilder.Redirect.PIPE);
                process = builder.start();
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(Base64.getEncoder().encode(input));
                }
                if (!process.waitFor(DPAPI_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("PowerShell DPAPI " + operation + " timed out");
                }
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (process.exitValue() != 0) {
                    throw new IllegalStateException("PowerShell DPAPI " + operation
                            + " failed with exit " + process.exitValue() + ": " + scrub(stderr));
                }
                return Base64.getDecoder().decode(stdout);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("PowerShell DPAPI " + operation + " failed: " + safeMessage(e), e);
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }

        private static String scrub(String stderr) {
            if (stderr == null || stderr.isBlank()) {
                return "";
            }
            String cleaned = stderr.replaceAll("[\\r\\n]+", " ").trim();
            cleaned = cleaned.replaceAll("[A-Za-z0-9+/=]{32,}", "[redacted]");
            if (cleaned.length() > 240) {
                return cleaned.substring(0, 240) + "...";
            }
            return cleaned;
        }
    }
}
