package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationScenarioResourceIdentityTest {
    private static final Map<Integer, String> EXPECTED_CATALOG_SHA256 = Map.of(
            1, "1e92d3820e1e6293bceb8ad5b7ab5b670d64484c19bb055ec84524459c7c6568",
            2, "2b903f0219734edd74a0c2eb00c020a98a394fc5be07f4f2aa42f639c65a4614",
            3, "da2c4558e43c1359025a408b4e12b30f4e1b08b9236ad1bee1940c86b3d40fa3",
            4, "d3df5675d1b764d846202012acc250caeb0324fe5ce81ec176928cc986a067ad",
            5, "28e3df731502dcbf3dbcdd4bd69131f93a71766aa6c42ebcebd5825619de7d6b",
            6, "b3e1b52ad5904bc110bfee42ef7fe7e0b2ee874b9011b1f0a4091194c27f1927");

    private static final Map<String, String> EXPECTED_RESOURCE_SHA256 = Map.ofEntries(
            Map.entry("model-qualification/banks/admission-v1.json", "54cd902f28399c09d9d52560db82d7dab7f5c6db7f3ebb30c43244ef99923a76"),
            Map.entry("model-qualification/banks/full-v1.json", "9dab2f8f300e0e2a3987fcfff8e34800bcb2ff542b0d5cd0080263c01145496e"),
            Map.entry("model-qualification/fixtures/basic-workspace-v1.json", "2f5d6a59bdcfd3021ea977908ba1b70b2ce1fc55e90e9c73b4dcae84dfbea689"),
            Map.entry("model-qualification/fixtures/context-workspace-v1.json", "49424edf18c5d9944a7286ee445f48e54485cb605322e69aeba6551b0f18b7b4"),
            Map.entry("model-qualification/fixtures/privacy-workspace-v1.json", "cc24ef90dc8e1174934651e95e98e79a7403e10e5d284965ea47c36ac373d86f"),
            Map.entry("model-qualification/fixtures/web-workspace-v1.json", "043a02de8972adb77e05bee448ffa80d3e0e868341b7b0cc1b3b8b4a64d7c666"),
            Map.entry("model-qualification/banks/admission-v2.json", "d3bc6872542da7a99706f875a39dfc235f2da139c15f2dea8b1c2240069254b8"),
            Map.entry("model-qualification/banks/full-v2.json", "acca037b0597c8c7b3c1c61e607d2cc46660c05949872f2b2944f2ef2011eb30"),
            Map.entry("model-qualification/fixtures/basic-workspace-v2.json", "79d5fe7950f8931c51acec39efc208eb573658f5ee7700971289f472db9d3e96"),
            Map.entry("model-qualification/fixtures/context-workspace-v2.json", "652ea2c65d4651342d17ba5389d0dae725329649ceca64abdd85ff1cff2a23c6"),
            Map.entry("model-qualification/fixtures/privacy-workspace-v2.json", "0b3aee1f0cca36823bd08f0adf05765e35f1a3a04ff0809fe90185be4ea97bf6"),
            Map.entry("model-qualification/fixtures/web-workspace-v2.json", "d66c74e4295af54a05eb81a422d3300ce7d1ca0bdbdfa4da15754004c6028592"),
            Map.entry("model-qualification/banks/admission-v3.json", "71f3093f5d44c14b59ac758cfaaecdf7837769c31c87c3d49a56fe6da6d3ff7c"),
            Map.entry("model-qualification/banks/full-v3.json", "956d89629b95dd63c7bfda61cea184fdb80f04c71a4c20284c01b71805fbd545"),
            Map.entry("model-qualification/fixtures/basic-workspace-v3.json", "8a425ad1de1cdcd641407e6e00862744bcc7f11f819fab6f4af938a76ae2aaa7"),
            Map.entry("model-qualification/fixtures/context-workspace-v3.json", "19852c9e8895827b5d9aead01df3367900ff41787d34f060ec47eeba6c86f39d"),
            Map.entry("model-qualification/fixtures/privacy-workspace-v3.json", "ccc9c8496c2278bcbf3c380df21a216b5765c702fa8c5dd5da1eea165b8dface"),
            Map.entry("model-qualification/fixtures/web-workspace-v3.json", "fb63451517715763426d6dc779f9b6165eb258ce4793fd64f009ca42f7884b87"),
            Map.entry("model-qualification/banks/admission-v4.json", "606726bf72f6e2a3f31d20cc46c8b41654d70aa42c06151c81f4a3be76909a6f"),
            Map.entry("model-qualification/banks/full-v4.json", "ccdba9307ea8d07c5b4e54ece9a295da0963363222bd664e8f50e6173ea76020"),
            Map.entry("model-qualification/fixtures/basic-workspace-v4.json", "31bc4929dfd5865b801ba73f46c74fee198102aec4ce47e14c77f4c68fc6cdae"),
            Map.entry("model-qualification/fixtures/context-workspace-v4.json", "e4708699fb0aca99518a811503ee235a021f76c2d6a18fba00bb004f21ce2530"),
            Map.entry("model-qualification/fixtures/privacy-workspace-v4.json", "ff1895c039041bc4b7e874cea643645a08ebe3366c25eee216572c68564e4bbf"),
            Map.entry("model-qualification/fixtures/web-workspace-v4.json", "536612d8d076f7f2654328cf5d3fe88de6ae0518ca5265b8537094ad41c79bf4"),
            Map.entry("model-qualification/banks/admission-v5.json", "9990a74ac870690e2c7ea97b6bfc60ce3e04f35d6d49bad41e3caf6975f0f741"),
            Map.entry("model-qualification/banks/full-v5.json", "e7136109e7066b64ff514f6a713c86d56ca3861b767720f90779c2b9047c9133"),
            Map.entry("model-qualification/fixtures/basic-workspace-v5.json", "da1a0dcb09af384c40e44dabc5bb357f5ec47c2d56e0328ea7c17b736aea5e37"),
            Map.entry("model-qualification/fixtures/context-workspace-v5.json", "2c27b3584fd4275517415fbc33074bd5bb93d81d0e7821a03fc98cc6eefe5e1e"),
            Map.entry("model-qualification/fixtures/privacy-workspace-v5.json", "3b885ceb6197b80442bb3b7b6bacb19a1dcfd7dac32fe37ad5e9e8673f4e0672"),
            Map.entry("model-qualification/fixtures/web-workspace-v5.json", "47c5f342965550a01c1d7764ae3059088e8eda8cdf037e54c84f4364afb7fd10"),
            Map.entry("model-qualification/banks/admission-v6.json", "64fb693757fc3d427f4aa9609ff0673eef4118d2c7645b01b8cefb37980de37c"),
            Map.entry("model-qualification/banks/full-v6.json", "ad94fd242fbe38c9809a267c112964ae0c8cdd816dd225260aaf2faeae814c7a"),
            Map.entry("model-qualification/fixtures/basic-workspace-v6.json", "6daaed724577acd6eee2c2e234252293ca48267ddc3d57a44e1e791b1db1d473"),
            Map.entry("model-qualification/fixtures/context-workspace-v6.json", "565d33c5451fde19803a69ffe15b880d314be5885eabe44c463fcb0736e2c6da"),
            Map.entry("model-qualification/fixtures/privacy-workspace-v6.json", "6b8c8157f131d61d31baecd356467d43cb02337eb7b7bc2de4fc6788e3a358f5"),
            Map.entry("model-qualification/fixtures/web-workspace-v6.json", "0c9a3090cb306277e8f057934e56c23651936bf488c42d90ac8c688171e75337"));

    @Test
    void lfCrlfAndPublishedRepresentationsResolveToExactPinnedIdentities() throws Exception {
        for (int version = 1; version <= QualificationProtocol.VERSION; version++) {
            List<QualificationScenarioBank.NamedBytes> canonicalResources = new ArrayList<>();
            for (String path : resourcePaths(version)) {
                byte[] checkoutBytes = resource(path);
                byte[] lf = normalizeLf(checkoutBytes);
                byte[] crlf = withCrlf(lf);
                byte[] expected = QualificationScenarioBank.canonicalResourceBytes(path, checkoutBytes, version);

                assertArrayEquals(expected,
                        QualificationScenarioBank.canonicalResourceBytes(path, lf, version), path + " LF");
                assertArrayEquals(expected,
                        QualificationScenarioBank.canonicalResourceBytes(path, crlf, version), path + " CRLF");
                assertArrayEquals(expected,
                        QualificationScenarioBank.canonicalResourceBytes(path, expected, version),
                        path + " published");
                assertEquals(EXPECTED_RESOURCE_SHA256.get(path), QualificationScenarioBank.sha256(expected), path);
                canonicalResources.add(new QualificationScenarioBank.NamedBytes(path, expected));
            }
            assertEquals(EXPECTED_CATALOG_SHA256.get(version),
                    QualificationScenarioBank.hash(canonicalResources), "protocol v" + version);
            QualificationScenarioBank.Catalog loaded = QualificationScenarioBank.load(version);
            assertEquals(EXPECTED_CATALOG_SHA256.get(version), loaded.sha256(), "loaded protocol v" + version);
            assertEquals(version, loaded.protocolVersion());
        }
    }

    @Test
    void v1ThroughV3AreLfAndV4ThroughV5ReconstructPublishedPhysicalSeparators() throws Exception {
        for (int version = 1; version <= 3; version++) {
            for (String path : resourcePaths(version)) {
                byte[] canonical = QualificationScenarioBank.canonicalResourceBytes(
                        path, withCrlf(normalizeLf(resource(path))), version);
                assertEquals(Set.of(), loneCrOffsets(canonical), path);
                assertEquals(Set.of(), crlfSeparatorOrdinals(canonical), path);
            }
        }

        for (int version = 4; version <= 5; version++) {
            for (String path : resourcePaths(version)) {
                byte[] canonical = QualificationScenarioBank.canonicalResourceBytes(
                        path, normalizeLf(resource(path)), version);
                assertEquals(Set.of(), loneCrOffsets(canonical), path);
                Set<Integer> loneLf = loneLfSeparatorOrdinals(canonical);
                if (path.equals("model-qualification/banks/full-v5.json")) {
                    assertEquals(Set.of(
                            780, 803, 804, 805, 806, 807, 808, 809, 810, 811, 812, 813,
                            817, 818, 819, 820, 821, 822, 823, 824, 825, 826, 827, 828,
                            829, 830, 831, 998), loneLf, path);
                } else {
                    assertEquals(Set.of(totalSeparators(canonical)), loneLf, path);
                }
            }
        }
    }

    @Test
    void v6CanonicalizesOnlyPhysicalLineEndingsToLfAndPreservesEscapedContent() throws Exception {
        String path = "model-qualification/fixtures/web-workspace-v6.json";
        byte[] expected = normalizeLf(resource(path));
        byte[] crlf = withCrlf(expected);

        byte[] canonical = QualificationScenarioBank.canonicalResourceBytes(path, crlf, 6);

        assertArrayEquals(expected, canonical);
        assertTrue(new String(canonical, StandardCharsets.UTF_8).contains("<!doctype html>\\n<html"));
        assertEquals(Set.of(), crlfSeparatorOrdinals(canonical));
    }

    @Test
    void physicalLineEndingNormalizationPreservesEscapesAndUtf8Bytes() {
        String text = "{\r\n  \"escaped\": \"first\\r\\nsecond\",\r\n"
                + "  \"unicode\": \"Mérida Ω\"\r\n}\r\n";
        byte[] expected = text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);

        byte[] normalized = QualificationScenarioBank.normalizePhysicalLineEndings(
                "synthetic-normalization-fixture", text.getBytes(StandardCharsets.UTF_8));

        assertArrayEquals(expected, normalized);
        String decoded = new String(normalized, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("first\\r\\nsecond"));
        assertTrue(decoded.contains("Mérida Ω"));
    }

    @Test
    void malformedOrHistoricallyUnrecognizedBytesFailClosed() throws Exception {
        String v6 = "model-qualification/banks/admission-v6.json";
        assertThrows(IllegalArgumentException.class, () -> QualificationScenarioBank.canonicalResourceBytes(
                v6, new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'}, 6));
        assertThrows(IllegalArgumentException.class, () -> QualificationScenarioBank.canonicalResourceBytes(
                v6, new byte[]{'{', '\n', (byte) 0xc3, 0x28, '\n', '}'}, 6));
        assertThrows(IllegalArgumentException.class, () -> QualificationScenarioBank.canonicalResourceBytes(
                v6, "{\r  \"value\": 1\n}\n".getBytes(StandardCharsets.UTF_8), 6));
        byte[] admissionV6 = normalizeLf(resource(v6));
        byte[] contentMutation = new String(admissionV6, StandardCharsets.UTF_8)
                .replaceFirst("What is 2 \\+ 2\\?", "What is 2 + 3?")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> QualificationScenarioBank.canonicalResourceBytes(
                v6, contentMutation, 6));
        assertThrows(IllegalArgumentException.class, () -> QualificationScenarioBank.canonicalResourceBytes(
                "model-qualification/banks/full-v6.json", admissionV6, 6));

        String historical = "model-qualification/banks/admission-v5.json";
        String original = new String(normalizeLf(resource(historical)), StandardCharsets.UTF_8);
        byte[] semanticallyEquivalent = original.replaceFirst("\\\"schemaName\\\":", "\\\"schemaName\\\" :")
                .getBytes(StandardCharsets.UTF_8);
        assertNotEquals(original.getBytes(StandardCharsets.UTF_8).length, semanticallyEquivalent.length);
        assertThrows(IllegalArgumentException.class, () -> QualificationScenarioBank.canonicalResourceBytes(
                historical, semanticallyEquivalent, 5));
        assertThrows(IllegalArgumentException.class, () -> QualificationScenarioBank.canonicalResourceBytes(
                "model-qualification/banks/not-published-v5.json", normalizeLf(resource(historical)), 5));
        assertThrows(IllegalArgumentException.class, () -> QualificationScenarioBank.canonicalResourceBytes(
                historical, normalizeLf(resource(historical)), 4));
    }

    private static List<String> resourcePaths(int version) {
        return List.of(
                "model-qualification/banks/admission-v" + version + ".json",
                "model-qualification/banks/full-v" + version + ".json",
                "model-qualification/fixtures/basic-workspace-v" + version + ".json",
                "model-qualification/fixtures/context-workspace-v" + version + ".json",
                "model-qualification/fixtures/privacy-workspace-v" + version + ".json",
                "model-qualification/fixtures/web-workspace-v" + version + ".json");
    }

    private static byte[] resource(String path) throws Exception {
        try (var input = QualificationScenarioResourceIdentityTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("missing test resource: " + path);
            return input.readAllBytes();
        }
    }

    private static byte[] normalizeLf(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\r' && index + 1 < bytes.length && bytes[index + 1] == '\n') {
                out.write('\n');
                index++;
            } else {
                out.write(bytes[index]);
            }
        }
        return out.toByteArray();
    }

    private static byte[] withCrlf(byte[] lf) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(lf.length);
        for (byte value : lf) {
            if (value == '\n') out.write('\r');
            out.write(value);
        }
        return out.toByteArray();
    }

    private static Set<Integer> crlfSeparatorOrdinals(byte[] bytes) {
        Map<Integer, Boolean> ordinals = separatorKinds(bytes);
        ordinals.entrySet().removeIf(entry -> !entry.getValue());
        return Set.copyOf(ordinals.keySet());
    }

    private static Set<Integer> loneLfSeparatorOrdinals(byte[] bytes) {
        Map<Integer, Boolean> ordinals = separatorKinds(bytes);
        ordinals.entrySet().removeIf(Map.Entry::getValue);
        return Set.copyOf(ordinals.keySet());
    }

    private static Map<Integer, Boolean> separatorKinds(byte[] bytes) {
        Map<Integer, Boolean> ordinals = new LinkedHashMap<>();
        int ordinal = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\r' && index + 1 < bytes.length && bytes[index + 1] == '\n') {
                ordinals.put(++ordinal, true);
                index++;
            } else if (bytes[index] == '\n') {
                ordinals.put(++ordinal, false);
            }
        }
        return ordinals;
    }

    private static Set<Integer> loneCrOffsets(byte[] bytes) {
        Set<Integer> offsets = new java.util.LinkedHashSet<>();
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\r' && (index + 1 >= bytes.length || bytes[index + 1] != '\n')) {
                offsets.add(index);
            }
        }
        return Set.copyOf(offsets);
    }

    private static int totalSeparators(byte[] bytes) {
        return separatorKinds(bytes).size();
    }
}
