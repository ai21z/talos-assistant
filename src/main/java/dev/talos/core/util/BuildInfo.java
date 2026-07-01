package dev.talos.core.util;

import java.io.InputStream;
import java.util.Properties;

/**
 * Build-identity helper - surfaces which build produced a transcript.
 *
 * <p>Sources (in priority order, with graceful {@code "unknown"} fallback):
 * <ul>
 *   <li>{@code version} - {@link Package#getImplementationVersion()} (from JAR manifest
 *       {@code Implementation-Version}); fallback generated classpath resource
 *       {@code META-INF/talos-version.properties}; final fallback {@code "unknown"}.</li>
 *   <li>{@code buildTimestamp} - {@link Package#getImplementationVendor()}, which the
 *       Gradle build stores as a build-time millis string in {@code Implementation-Vendor}.
 *       Fallback {@code "unknown"}.</li>
 *   <li>{@code commitSha}, {@code branch} - optional classpath resource
 *       {@code META-INF/talos-build.properties} with keys {@code git.commit} and
 *       {@code git.branch}. When the resource is absent (current default build),
 *       both return {@code "unknown"}.</li>
 * </ul>
 *
 * <p>R7 - this helper exists so runtime logs and the startup banner can record
 * which build was actually running, without requiring git to be installed at
 * runtime and without fabricating metadata when it is not present.
 *
 * <p>All methods are null-safe. Callers can rely on {@link #summary()} to
 * produce one compact, log-safe identity line.
 */
public final class BuildInfo {

    /** Sentinel returned when a metadata field cannot be resolved. */
    public static final String UNKNOWN = "unknown";

    /** Classpath path for optional git-identity properties produced at build time. */
    static final String BUILD_PROPS_RESOURCE = "META-INF/talos-build.properties";
    /** Classpath path for generated version metadata used in exploded-class runs. */
    static final String VERSION_PROPS_RESOURCE = "META-INF/talos-version.properties";

    private BuildInfo() {}

    // ── Core readers ────────────────────────────────────────────────

    /** @return the jar-manifest {@code Implementation-Version}, or {@value #UNKNOWN}. */
    public static String version() {
        String manifest = manifestAttr(Package::getImplementationVersion);
        if (!UNKNOWN.equals(manifest)) return manifest;
        return resourceProp(VERSION_PROPS_RESOURCE, "version");
    }

    /** @return the jar-manifest {@code Implementation-Vendor} (build timestamp), or {@value #UNKNOWN}. */
    public static String buildTimestamp() {
        return manifestAttr(Package::getImplementationVendor);
    }

    /**
     * @return short (7-char) git commit SHA from {@code META-INF/talos-build.properties},
     *         or {@value #UNKNOWN} if the resource is absent or the key is missing.
     */
    public static String commitSha() {
        String full = buildProp("git.commit");
        if (UNKNOWN.equals(full)) return UNKNOWN;
        return full.length() > 7 ? full.substring(0, 7) : full;
    }

    /** @return git branch from {@code META-INF/talos-build.properties}, or {@value #UNKNOWN}. */
    public static String branch() {
        return buildProp("git.branch");
    }

    /**
     * One compact identity line suitable for startup logs and banners.
     *
     * <p>Format (fields with value {@value #UNKNOWN} are still included so
     * callers can detect absence without string comparison gymnastics):
     * <pre>
     *   talos v&lt;version&gt; - build &lt;timestamp&gt; - commit &lt;sha&gt; - branch &lt;branch&gt;
     * </pre>
     */
    public static String summary() {
        return "talos v" + version()
                + " - build " + buildTimestamp()
                + " - commit " + commitSha()
                + " - branch " + branch();
    }

    // ── Internals (package-private for testing) ─────────────────────

    /**
     * Reads a manifest attribute via the given accessor, falling back to
     * {@value #UNKNOWN} when the package metadata is absent (e.g. running
     * from exploded classes during tests).
     */
    private static String manifestAttr(java.util.function.Function<Package, String> accessor) {
        Package pkg = BuildInfo.class.getPackage();
        if (pkg == null) return UNKNOWN;
        String value = accessor.apply(pkg);
        return (value == null || value.isBlank()) ? UNKNOWN : value;
    }

    /**
     * Reads a property from {@link #BUILD_PROPS_RESOURCE} on the classpath.
     * Returns {@value #UNKNOWN} when the resource is missing, unreadable, or
     * does not contain the key.
     */
    static String buildProp(String key) {
        return resourceProp(BUILD_PROPS_RESOURCE, key);
    }

    static String resourceProp(String resourcePath, String key) {
        try (InputStream in = BuildInfo.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) return UNKNOWN;
            Properties props = new Properties();
            props.load(in);
            String value = props.getProperty(key);
            return (value == null || value.isBlank()) ? UNKNOWN : value.trim();
        } catch (Exception ignored) {
            return UNKNOWN;
        }
    }
}

