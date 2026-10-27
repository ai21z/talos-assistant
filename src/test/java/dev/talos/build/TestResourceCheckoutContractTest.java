package dev.talos.build;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestResourceCheckoutContractTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"true, crlf", "false, lf", "input, crlf"})
    void fixtureBytesSurviveGitAddAndCheckout(String autoCrlf, String eol) throws Exception {
        Path repository = Files.createDirectory(directory.resolve("repository"));
        Files.copy(Path.of(".gitattributes"), repository.resolve(".gitattributes"));
        git(repository, "init", "--quiet");
        git(repository, "config", "core.autocrlf", autoCrlf);
        git(repository, "config", "core.eol", eol);

        Map<String, byte[]> fixtures = new LinkedHashMap<>();
        for (String root : List.of("src/test/resources/", "src/e2eTest/resources/")) {
            fixtures.put(root + "nested/answer.txt", bytes("alpha\nbeta\n"));
            fixtures.put(root + "nested/windows.txt", bytes("alpha\r\nbeta\r\n"));
            fixtures.put(root + "nested/mixed.txt", bytes("alpha\r\nbeta\ngamma\rdelta"));
            fixtures.put(root + "nested/empty.txt", new byte[0]);
            fixtures.put(root + "nested/unicode.md", bytes("\uFEFF\u03B1\r\n\u03B2\n"));
            fixtures.put(root + "nested/ascii.pdf", bytes("%PDF-1.4\n1 0 obj\nendobj\n%%EOF\n"));
            fixtures.put(root + "nested/raw.dat", new byte[]{0, 13, 10, 10, (byte) 0xFF, 13});
            fixtures.put(root + "nested/future.fixture", bytes("unknown format\r\n"));
        }
        fixtures.put("scripts/control.sh", bytes("first\nsecond\n"));
        fixtures.put("scripts/control.cmd", bytes("first\r\nsecond\r\n"));
        for (var fixture : fixtures.entrySet()) {
            Path path = repository.resolve(fixture.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, fixture.getValue());
        }
        git(repository, "add", "--", ".gitattributes", "src", "scripts");
        git(repository, "checkout-index", "--all", "--prefix=checkout/");

        for (var fixture : fixtures.entrySet()) {
            assertArrayEquals(fixture.getValue(),
                    Files.readAllBytes(repository.resolve("checkout").resolve(fixture.getKey())),
                    "Checkout must retain fixture bytes for " + fixture.getKey());
            if (fixture.getKey().startsWith("src/")) {
                assertArrayEquals(fixture.getValue(), git(repository, "show", ":" + fixture.getKey()),
                        "Index must retain fixture bytes for " + fixture.getKey());
            }
        }
        assertEquals(0, git(repository, "diff", "--cached", "--numstat", "--",
                "checkout").length, "The round-trip output must not be staged");
    }

    private byte[] git(Path repository, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.attributesFile=",
                "-c", "core.safecrlf=false"));
        command.addAll(List.of(arguments));
        Path stdout = Files.createTempFile(directory, "git-output-", ".bin");
        Path stderr = Files.createTempFile(directory, "git-error-", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(repository.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().keySet().removeIf(name -> name.regionMatches(true, 0, "GIT_", 0, 4));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", directory.resolve("no-global-config").toString());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Git fixture command timed out");
            assertTrue(Files.size(stdout) <= 65_536 && Files.size(stderr) <= 65_536,
                    "Git fixture diagnostics exceeded their bound");
            assertEquals(0, process.exitValue(), () -> diagnostic(stderr));
            return Files.readAllBytes(stdout);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Git fixture process did not exit");
            }
        }
    }

    private static String diagnostic(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            return "Git fixture diagnostics could not be read";
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
