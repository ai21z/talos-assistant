package dev.talos.release;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedBuildIdentityContractTest {
    private static final Path JAR = Path.of("build/libs/talos.jar").toAbsolutePath().normalize();
    private static final String RESOURCE = "META-INF/talos-build.properties";

    @Test
    void packagedJarCarriesTheExactGitCommitAndBranch() throws Exception {
        assertTrue(Files.isRegularFile(JAR), "packaged Talos JAR is missing: " + JAR);
        Properties identity = new Properties();
        try (ZipFile jar = new ZipFile(JAR.toFile())) {
            ZipEntry entry = jar.getEntry(RESOURCE);
            assertNotNull(entry, RESOURCE + " is missing from the packaged Talos JAR");
            try (InputStream input = jar.getInputStream(entry)) {
                identity.load(input);
            }
        }

        Optional<String> headResult = git("rev-parse", "HEAD");
        Optional<String> branchResult = git("rev-parse", "--abbrev-ref", "HEAD");
        if (headResult.isEmpty() || branchResult.isEmpty()) {
            assertEquals("unknown", identity.getProperty("git.commit"));
            assertEquals("unknown", identity.getProperty("git.branch"));
            return;
        }
        String head = headResult.orElseThrow();
        String branch = branchResult.orElseThrow();
        boolean dirty = git("status", "--porcelain", "--untracked-files=no")
                .filter(output -> !output.isBlank())
                .isPresent();
        assertTrue(head.matches("[0-9a-f]{40}"), "git HEAD is not a full commit: " + head);
        assertEquals(dirty ? "unknown" : head, identity.getProperty("git.commit"));
        assertEquals(branch, identity.getProperty("git.branch"));
        assertEquals(Boolean.toString(dirty), identity.getProperty("git.dirty"));
    }

    private static Optional<String> git(String... arguments) throws Exception {
        ProcessBuilder command = new ProcessBuilder();
        command.command().add("git");
        command.command().addAll(java.util.List.of(arguments));
        command.redirectErrorStream(true);
        Process process;
        try {
            process = command.start();
        } catch (java.io.IOException unavailable) {
            return Optional.empty();
        }
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "git command timed out");
        return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
    }
}
