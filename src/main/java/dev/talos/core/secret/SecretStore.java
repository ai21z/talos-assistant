package dev.talos.core.secret;

import java.util.Optional;

/**
 * Minimal SPI for local secrets.
 * Implementations must keep data local to the current user account.
 */
public interface SecretStore {
    /**
     * Store or overwrite a secret value.
     * @param scope logical namespace (e.g., "ollama", "openai"); use "default" if not needed
     * @param key   key name
     * @param value secret payload (caller retains ownership; implementation should copy/wipe)
     */
    void put(String scope, String key, char[] value) throws Exception;

    /** Retrieve a secret value. Caller must wipe the returned array when done. */
    Optional<char[]> get(String scope, String key) throws Exception;

    /** Delete a secret if present. Returns true when something was removed. */
    boolean delete(String scope, String key) throws Exception;
}
