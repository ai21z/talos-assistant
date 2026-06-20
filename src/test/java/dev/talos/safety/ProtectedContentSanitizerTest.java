package dev.talos.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedContentSanitizerTest {

    @Test
    void redactsBareSecretShapes() {
        String ghp = "ghp_AbCdEfGhIjKlMnOpQrStUvWxYz1234567890";
        String openAi = "sk-abcdefghijklmnopqrstuvwxyz1234567890";
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                + "eyJzdWIiOiJ0YWxvcyIsIm5hbWUiOiJUZXN0In0."
                + "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String pem = """
                -----BEGIN PRIVATE KEY-----
                MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDfakeLineOne
                bW9yZUZha2VLZXlNYXRlcmlhbEZvclRhbG9zVGVzdE9ubHk=
                -----END PRIVATE KEY-----""";
        String postgres = "jdbc:postgresql://talos_user:Sup3rSecretPassword@localhost:5432/appdb";
        String entropy = "N7qP4mV9zK2xR8sT5cL1aB6dF3hJ0uY2wE9rQ4tP8nM6";

        String sanitized = ProtectedContentSanitizer.sanitizeText(String.join("\n",
                "github=" + ghp,
                "openai=" + openAi,
                "jwt=" + jwt,
                pem,
                "db=" + postgres,
                "blob=" + entropy));

        assertAll(
                () -> assertFalse(sanitized.contains(ghp), sanitized),
                () -> assertFalse(sanitized.contains(openAi), sanitized),
                () -> assertFalse(sanitized.contains(jwt), sanitized),
                () -> assertFalse(sanitized.contains("MIIEvQIBADAN"), sanitized),
                () -> assertFalse(sanitized.contains("Sup3rSecretPassword"), sanitized),
                () -> assertFalse(sanitized.contains(entropy), sanitized),
                () -> assertTrue(sanitized.contains(ProtectedContentSanitizer.REDACTED_VALUE), sanitized));
    }

    @Test
    void preservesExistingCanaryAndLabeledSecretRedaction() {
        String sanitized = ProtectedContentSanitizer.sanitizeText("""
                PRIVATE_MARKER = DO_NOT_LEAK_T834_MARKER
                api_key=sk-test-DO-NOT-LEAK
                raw canary: TALOS_CANARY_T834
                """);

        assertAll(
                () -> assertFalse(sanitized.contains("DO_NOT_LEAK_T834_MARKER"), sanitized),
                () -> assertFalse(sanitized.contains("sk-test-DO-NOT-LEAK"), sanitized),
                () -> assertFalse(sanitized.contains("TALOS_CANARY_T834"), sanitized),
                () -> assertTrue(sanitized.contains("PRIVATE_MARKER=[redacted]"), sanitized),
                () -> assertTrue(sanitized.contains("api_key=[redacted]"), sanitized),
                () -> assertTrue(sanitized.contains("[redacted-canary]"), sanitized));
    }

    @Test
    void negativeCorpusIsNotMangled() {
        String gitSha = "0123456789abcdef0123456789abcdef01234567";
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        String prose = "The release notes discuss entropy, jwt parsing, and github tokens without including one.";
        String input = String.join("\n", prose, gitSha, uuid);

        assertEquals(input, ProtectedContentSanitizer.sanitizeText(input));
    }
}
