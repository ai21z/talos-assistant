package dev.talos.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7 — Coverage for the build-identity helper.
 *
 * <p>Tests run from exploded class files in the Gradle test classpath, so the
 * jar-manifest attributes that {@link BuildInfo#version()} etc. read through
 * {@link Package} metadata are typically <em>absent</em>. That is still the
 * interesting case to pin down: version should fall back to generated build
 * metadata, while other fields must still gracefully fall back to
 * {@code "unknown"} rather than NPE or fabrication.
 *
 * <p>These tests do <b>not</b> require git to be available — the optional
 * {@code META-INF/talos-build.properties} resource is not shipped on the
 * test classpath by default, so {@link BuildInfo#commitSha()} and
 * {@link BuildInfo#branch()} are expected to return {@code "unknown"}.
 */
@DisplayName("R7 — BuildInfo")
class BuildInfoTest {

    @Test
    @DisplayName("version() never returns null and resolves from generated metadata in test classpath")
    void versionFallsBackGracefully() {
        String v = BuildInfo.version();
        assertNotNull(v, "version() must not return null");
        assertTrue(!v.isBlank(), "version() must not return blank");
        assertEquals("0.9.0-beta", v,
                "Exploded-class test runs should resolve version from generated build metadata.");
    }

    @Test
    @DisplayName("buildTimestamp() never returns null; defaults to 'unknown' in test classpath")
    void buildTimestampFallsBackGracefully() {
        String ts = BuildInfo.buildTimestamp();
        assertNotNull(ts, "buildTimestamp() must not return null");
        assertTrue(!ts.isBlank(), "buildTimestamp() must not return blank");
    }

    @Test
    @DisplayName("commitSha() returns 'unknown' when build-props resource is absent")
    void commitShaUnknownWithoutResource() {
        // The test classpath does not ship META-INF/talos-build.properties,
        // so this MUST be the fallback value. If a future change adds that
        // resource to tests, this assertion will correctly flag it.
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.commitSha(),
                "No META-INF/talos-build.properties on test classpath — "
                + "commitSha() must fall back to 'unknown'.");
    }

    @Test
    @DisplayName("branch() returns 'unknown' when build-props resource is absent")
    void branchUnknownWithoutResource() {
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.branch(),
                "No META-INF/talos-build.properties on test classpath — "
                + "branch() must fall back to 'unknown'.");
    }

    @Test
    @DisplayName("summary() is a single non-empty line containing all four fields")
    void summaryContainsAllFields() {
        String s = BuildInfo.summary();
        assertNotNull(s);
        assertTrue(s.startsWith("talos v"), "summary must start with 'talos v': " + s);
        assertTrue(s.contains("build "),  "summary must contain 'build ': " + s);
        assertTrue(s.contains("commit "), "summary must contain 'commit ': " + s);
        assertTrue(s.contains("branch "), "summary must contain 'branch ': " + s);
        assertTrue(!s.contains("\n"), "summary must be a single line (no newlines): " + s);
    }

    @Test
    @DisplayName("buildProp() returns 'unknown' for unknown keys (no resource, no fabrication)")
    void buildPropMissingKeyIsUnknown() {
        // Covers the resource-missing branch directly (package-private seam).
        assertEquals(BuildInfo.UNKNOWN,
                BuildInfo.buildProp("no.such.key.ever"));
    }
}

