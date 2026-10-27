package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.extract.FakeOcrCli;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GrepToolTest {

    @TempDir Path workspace;
    private GrepTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        tool = new GrepTool();
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ctx = new ToolContext(workspace, sandbox, new Config());

        Files.writeString(workspace.resolve("App.java"),
                "package com.example;\npublic class App {\n    public void run() {}\n}\n");
        Files.writeString(workspace.resolve("README.md"),
                "# My Project\nThis is a demo project.\nSee App.java for details.\n");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Util.java"),
                "package com.example;\npublic class Util {\n    public static String hello() { return \"hello\"; }\n}\n");
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve(".git/config"), "some git config with public");
    }

    @Test void descriptor() {
        assertEquals("talos.grep", tool.name());
        assertNotNull(tool.descriptor().parametersSchema());
    }

    @Test void grep_uses_neutral_safety_for_protected_content_path_and_sanitizer_ownership()
            throws IOException {
        String source = Files.readString(Path.of("src/main/java/dev/talos/tools/impl/GrepTool.java"));
        assertTrue(source.contains("import dev.talos.safety.ProtectedContentMessages;"), source);
        assertTrue(source.contains("import dev.talos.safety.ProtectedContentSanitizer;"), source);
        assertTrue(source.contains("import dev.talos.safety.ProtectedWorkspacePaths;"), source);
        assertFalse(source.contains("import dev.talos.runtime.policy.ProtectedContentPolicy;"), source);
        assertFalse(source.contains("ProtectedContentPolicy."), source);

        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));
        assertFalse(baseline.contains(
                "tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedContentPolicy"),
                baseline);
    }

    @Test void grep_uses_core_privacy_facts_for_private_mode_ownership() throws IOException {
        String source = Files.readString(Path.of("src/main/java/dev/talos/tools/impl/GrepTool.java"));
        assertTrue(source.contains("import dev.talos.core.privacy.PrivacyConfigFacts;"), source);
        assertFalse(source.contains("import dev.talos.runtime.policy.ProtectedReadScopePolicy;"), source);
        assertFalse(source.contains("ProtectedReadScopePolicy."), source);
        assertTrue(source.contains("PrivacyConfigFacts.privateMode(ctx.config())"), source);

        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));
        assertFalse(baseline.contains(
                "tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedReadScopePolicy"),
                baseline);
    }

    @Test void plainTextSearch() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "public class")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("App.java"));
        assertTrue(r.output().contains("Util.java"));
        assertEquals(List.of("App.java", "src/Util.java"), sourcePaths(r));
    }

    @Test void inverseCreationOrderProducesIdenticalUncappedOutputAndMetadata()
            throws IOException {
        Path forward = createDeterminismFixture("forward-uncapped", false);
        Path reverse = createDeterminismFixture("reverse-uncapped", true);

        GrepSnapshot first = grepSnapshot(forward, Map.of("pattern", "ordered needle"));
        GrepSnapshot second = grepSnapshot(reverse, Map.of("pattern", "ordered needle"));

        assertEquals(first, second);
        assertEquals(
                List.of("alpha.txt", "nested/beta.txt", "zeta.txt"),
                first.sourcePaths());
        assertOrdered(first.output(),
                "alpha.txt:1",
                "alpha.txt:2",
                "nested/beta.txt:1",
                "zeta.txt:1");
    }

    @Test void inverseCreationOrderProducesIdenticalCappedMembershipAndMetadata()
            throws IOException {
        Path forward = createDeterminismFixture("forward-capped", false);
        Path reverse = createDeterminismFixture("reverse-capped", true);

        GrepSnapshot first = grepSnapshot(forward, Map.of(
                "pattern", "ordered needle",
                "max_results", "1"));
        GrepSnapshot second = grepSnapshot(reverse, Map.of(
                "pattern", "ordered needle",
                "max_results", "1"));

        assertEquals(first, second);
        assertEquals(List.of("alpha.txt"), first.sourcePaths());
        assertTrue(first.output().contains("alpha.txt:1"), first.output());
        assertFalse(first.output().contains("nested/beta.txt:"), first.output());
        assertFalse(first.output().contains("zeta.txt:"), first.output());
    }

    @Test void inverseCreationOrderPreservesMultiMatchOrderingBeforeCap()
            throws IOException {
        Path forward = createDeterminismFixture("forward-multi", false);
        Path reverse = createDeterminismFixture("reverse-multi", true);

        GrepSnapshot first = grepSnapshot(forward, Map.of(
                "pattern", "ordered needle",
                "max_results", "3"));
        GrepSnapshot second = grepSnapshot(reverse, Map.of(
                "pattern", "ordered needle",
                "max_results", "3"));

        assertEquals(first, second);
        assertEquals(List.of("alpha.txt", "nested/beta.txt"), first.sourcePaths());
        assertOrdered(first.output(),
                "alpha.txt:1",
                "alpha.txt:2",
                "nested/beta.txt:1");
        assertFalse(first.output().contains("zeta.txt:"), first.output());
    }

    @Test void inverseCreationOrderKeepsGlobProtectionUnsupportedUnreadableAndNestedBehavior()
            throws IOException {
        Path forward = createDeterminismFixture("forward-policy", false);
        Path reverse = createDeterminismFixture("reverse-policy", true);

        GrepSnapshot first = grepSnapshot(forward, Map.of(
                "pattern", "ordered needle",
                "include", "*.txt"));
        GrepSnapshot second = grepSnapshot(reverse, Map.of(
                "pattern", "ordered needle",
                "include", "*.txt"));

        assertEquals(first, second);
        assertEquals(
                List.of("alpha.txt", "nested/beta.txt", "zeta.txt"),
                first.sourcePaths());
        assertFalse(first.output().contains("DO_NOT_EXPOSE_PROTECTED"), first.output());
        assertFalse(first.output().contains("DO_NOT_SEARCH_SKIPPED"), first.output());
        assertFalse(first.output().contains("invalid-utf8.txt:"), first.output());
        assertTrue(first.output().contains("protected content"), first.output());

        GrepSnapshot unsupportedFirst = grepSnapshot(
                forward, Map.of("pattern", "no-file-matches-this"));
        GrepSnapshot unsupportedSecond = grepSnapshot(
                reverse, Map.of("pattern", "no-file-matches-this"));
        assertEquals(unsupportedFirst, unsupportedSecond);
        assertTrue(unsupportedFirst.output().contains(
                "Skipped unsupported binary document(s):"), unsupportedFirst.output());
        assertOrdered(unsupportedFirst.output(), "alpha.docx", "zeta.pdf");
        assertEquals(List.of(), unsupportedFirst.sourcePaths());
    }

    @Test void validMatchingLineBeforeMalformedUtf8PublishesNoPartialMatchOrSource()
            throws IOException {
        Path root = Files.createDirectory(workspace.resolve("malformed-after-match"));
        byte[] prefix = "ordered needle must remain unpublished\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] invalidText = Arrays.copyOf(prefix, prefix.length + 2);
        invalidText[prefix.length] = (byte) 0xc3;
        invalidText[prefix.length + 1] = (byte) 0x28;
        Files.write(root.resolve("invalid-utf8.txt"), invalidText);

        GrepSnapshot snapshot = grepSnapshot(root, Map.of("pattern", "ordered needle"));

        assertEquals(List.of(), snapshot.sourcePaths());
        assertTrue(snapshot.output().contains("No matches found"), snapshot.output());
        assertFalse(snapshot.output().contains("invalid-utf8.txt:"), snapshot.output());
        assertFalse(snapshot.output().contains("must remain unpublished"), snapshot.output());
    }

    @Test void linkedExternalFileIsNotReadOrReportedAsSource() throws IOException {
        Path root = Files.createDirectory(workspace.resolve("linked-file-root"));
        Path external = Files.createDirectory(workspace.resolve("linked-file-external"));
        Path externalFile = external.resolve("outside.txt");
        Files.writeString(externalFile, "ordered needle EXTERNAL_LINK_FILE_BYTES\n");
        Files.writeString(root.resolve("safe.txt"), "ordered needle internal bytes\n");
        createSymbolicLinkOrSkip(root.resolve("linked.txt"), externalFile);

        GrepSnapshot snapshot = grepSnapshot(root, Map.of("pattern", "ordered needle"));

        assertEquals(List.of("safe.txt"), snapshot.sourcePaths());
        assertTrue(snapshot.output().contains("safe.txt:1"), snapshot.output());
        assertFalse(snapshot.output().contains("linked.txt:"), snapshot.output());
        assertFalse(snapshot.output().contains("EXTERNAL_LINK_FILE_BYTES"), snapshot.output());
    }

    @Test void linkedExternalDirectoryIsNotTraversedOrReportedAsSource() throws IOException {
        Path root = Files.createDirectory(workspace.resolve("linked-directory-root"));
        Path external = Files.createDirectory(workspace.resolve("linked-directory-external"));
        Files.writeString(
                external.resolve("outside.txt"),
                "ordered needle EXTERNAL_LINK_DIRECTORY_BYTES\n");
        Files.writeString(root.resolve("safe.txt"), "ordered needle internal bytes\n");
        createSymbolicLinkOrSkip(root.resolve("linked-directory"), external);

        GrepSnapshot snapshot = grepSnapshot(root, Map.of("pattern", "ordered needle"));

        assertEquals(List.of("safe.txt"), snapshot.sourcePaths());
        assertTrue(snapshot.output().contains("safe.txt:1"), snapshot.output());
        assertFalse(snapshot.output().contains("linked-directory/"), snapshot.output());
        assertFalse(snapshot.output().contains(
                "EXTERNAL_LINK_DIRECTORY_BYTES"), snapshot.output());
    }

    @Test void windowsExternalDirectoryJunctionIsNotTraversedOrReportedAsSource()
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name", "").startsWith("Windows"),
                "Windows junction fixture");
        Path root = Files.createDirectory(workspace.resolve("junction-root"));
        Path external = Files.createDirectory(workspace.resolve("junction-external"));
        Files.writeString(
                external.resolve("outside.txt"),
                "ordered needle EXTERNAL_JUNCTION_BYTES\n");
        Files.writeString(root.resolve("safe.txt"), "ordered needle internal bytes\n");
        Path junction = root.resolve("linked-directory");
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), external.toString())
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String commandOutput = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                exited && !process.isAlive() && process.exitValue() == 0,
                "Windows junction fixture unavailable: " + commandOutput);

        GrepSnapshot snapshot = grepSnapshot(root, Map.of("pattern", "ordered needle"));

        assertEquals(List.of("safe.txt"), snapshot.sourcePaths());
        assertTrue(snapshot.output().contains("safe.txt:1"), snapshot.output());
        assertFalse(snapshot.output().contains("linked-directory/"), snapshot.output());
        assertFalse(snapshot.output().contains("EXTERNAL_JUNCTION_BYTES"), snapshot.output());
    }

    @Test void inverseCreationOrderSkipsUnreadablePosixEntriesDeterministically()
            throws IOException {
        Path forward = createUnreadableFixture("forward-unreadable", false);
        Path reverse = createUnreadableFixture("reverse-unreadable", true);
        Path forwardUnreadable = forward.resolve("zeta-unreadable.txt");
        Path reverseUnreadable = reverse.resolve("zeta-unreadable.txt");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(forwardUnreadable)
                        .supportsFileAttributeView("posix"),
                "POSIX unreadable-file fixture");
        Set<PosixFilePermission> forwardOriginal =
                Files.getPosixFilePermissions(forwardUnreadable);
        Set<PosixFilePermission> reverseOriginal =
                Files.getPosixFilePermissions(reverseUnreadable);
        try {
            Files.setPosixFilePermissions(forwardUnreadable, Set.of());
            Files.setPosixFilePermissions(reverseUnreadable, Set.of());
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    !Files.isReadable(forwardUnreadable)
                            && !Files.isReadable(reverseUnreadable),
                    "Current account can still read mode-000 fixture");

            GrepSnapshot first = grepSnapshot(
                    forward, Map.of("pattern", "ordered needle"));
            GrepSnapshot second = grepSnapshot(
                    reverse, Map.of("pattern", "ordered needle"));

            assertEquals(first, second);
            assertEquals(List.of("alpha.txt"), first.sourcePaths());
            assertFalse(first.output().contains("zeta-unreadable.txt:"), first.output());
            assertFalse(first.output().contains("EXTERNAL_UNREADABLE_BYTES"), first.output());
        } finally {
            Files.setPosixFilePermissions(forwardUnreadable, forwardOriginal);
            Files.setPosixFilePermissions(reverseUnreadable, reverseOriginal);
        }
    }

    @Test void regexSearch() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "class\\s+\\w+", "regex", "true")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("App.java"));
    }

    @Test void includeGlobFilter() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "public", "include", "*.java")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains(".java"));
        assertFalse(r.output().contains("README.md"));
    }

    @Test void pathQualifiedFilenameGlobsAreRejectedInsteadOfSilentlySearchingZeroFiles()
            throws IOException {
        Files.writeString(workspace.resolve("config.json"), "{\"port\": 8080}\n");

        for (String include : List.of("/*.json", "src/*.json", "src\\*.json")) {
            var r = tool.execute(new ToolCall("talos.grep", Map.of(
                    "pattern", "port",
                    "include", include)), ctx);

            assertFalse(r.success(), include);
            assertEquals(ToolError.INVALID_PARAMS, r.error().code(), include);
            assertTrue(r.error().message().contains("filename"), r.error().message());
            assertTrue(r.error().message().contains("*.json"), r.error().message());
        }
    }

    @Test void filenameOnlyJsonGlobStillFindsRootJsonFile() throws IOException {
        Files.writeString(workspace.resolve("config.json"), "{\"port\": 8080}\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of(
                "pattern", "port",
                "include", "*.json")), ctx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("config.json"), r.output());
        assertEquals(List.of("config.json"), sourcePaths(r));
    }

    @Test void commaSeparatedIncludeGlobIsRejectedInsteadOfSilentFalseNegative() throws IOException {
        Files.writeString(workspace.resolve("script.js"),
                "const button = document.querySelector('.missing-button');\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of(
                "pattern", "\\.missing-button",
                "include", "*.html, *.css",
                "regex", "true")), ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.error().message().contains("include"), r.error().message());
        assertTrue(r.error().message().contains("comma-separated"), r.error().message());
    }

    @Test void noMatchesFound() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "xyznonexistentxyz")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("No matches"));
        assertEquals(List.of(), sourcePaths(r));
    }

    private static List<String> sourcePaths(ToolResult result) {
        try {
            Method method = result.contentMetadata().getClass().getMethod("sourcePaths");
            @SuppressWarnings("unchecked")
            List<String> paths = (List<String>) method.invoke(result.contentMetadata());
            return paths;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Tool content metadata must expose structured source paths", e);
        }
    }

    private Path createDeterminismFixture(String directory, boolean reverse)
            throws IOException {
        Path root = Files.createDirectory(workspace.resolve(directory));
        List<String> relativePaths = new ArrayList<>(List.of(
                "zeta.txt",
                "zeta.pdf",
                "nested/beta.txt",
                "invalid-utf8.txt",
                "alpha.txt",
                "alpha.docx",
                "secrets/private.txt",
                ".git/ignored.txt"));
        if (reverse) {
            Collections.reverse(relativePaths);
        }
        for (String relativePath : relativePaths) {
            Path file = root.resolve(relativePath);
            Files.createDirectories(file.getParent());
            switch (relativePath) {
                case "alpha.txt" -> Files.writeString(
                        file, "ordered needle alpha one\nordered needle alpha two\n");
                case "nested/beta.txt" -> Files.writeString(
                        file, "ordered needle nested beta\n");
                case "zeta.txt" -> Files.writeString(
                        file, "ordered needle zeta\n");
                case "invalid-utf8.txt" -> {
                    byte[] prefix = "ordered needle must not survive partial decode\n"
                            .getBytes(StandardCharsets.UTF_8);
                    byte[] invalidText = Arrays.copyOf(prefix, prefix.length + 2);
                    invalidText[prefix.length] = (byte) 0xc3;
                    invalidText[prefix.length + 1] = (byte) 0x28;
                    Files.write(file, invalidText);
                }
                case "alpha.docx", "zeta.pdf" -> Files.writeString(
                        file, "unsupported document placeholder\n");
                case "secrets/private.txt" -> Files.writeString(
                        file, "ordered needle DO_NOT_EXPOSE_PROTECTED\n");
                case ".git/ignored.txt" -> Files.writeString(
                        file, "ordered needle DO_NOT_SEARCH_SKIPPED\n");
                default -> throw new IllegalStateException("Unknown fixture path: " + relativePath);
            }
        }
        return root;
    }

    private Path createUnreadableFixture(String directory, boolean reverse)
            throws IOException {
        Path root = Files.createDirectory(workspace.resolve(directory));
        List<String> relativePaths = new ArrayList<>(List.of(
                "zeta-unreadable.txt", "alpha.txt"));
        if (reverse) {
            Collections.reverse(relativePaths);
        }
        for (String relativePath : relativePaths) {
            String content = relativePath.equals("alpha.txt")
                    ? "ordered needle readable bytes\n"
                    : "ordered needle EXTERNAL_UNREADABLE_BYTES\n";
            Files.writeString(root.resolve(relativePath), content);
        }
        return root;
    }

    private GrepSnapshot grepSnapshot(Path root, Map<String, String> parameters) {
        ToolContext fixtureContext = new ToolContext(
                root, new Sandbox(root, Map.of()), new Config());
        ToolResult result = tool.execute(new ToolCall("talos.grep", parameters), fixtureContext);
        assertTrue(result.success(), result.errorMessage());
        return new GrepSnapshot(result.output(), sourcePaths(result));
    }

    private static void assertOrdered(String value, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = value.indexOf(fragment);
            assertTrue(current > previous,
                    "Expected ordered fragment '" + fragment + "' after offset "
                            + previous + " in:\n" + value);
            previous = current;
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false,
                    "Symbolic-link fixture unavailable: "
                            + unavailable.getClass().getSimpleName());
        }
    }

    private record GrepSnapshot(String output, List<String> sourcePaths) {}

    @Test void includeGlobReportsUnsupportedBinaryDocuments() throws IOException {
        Files.writeString(workspace.resolve("sample.xlsx"), "fake excel payload");

        var r = tool.execute(new ToolCall("talos.grep", Map.of(
                "pattern", "budget",
                "include", "*.xlsx")), ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("No matches found"));
        assertTrue(r.output().contains("Skipped unsupported binary document(s): sample.xlsx"));
        assertTrue(r.output().contains("cannot extract PDF/Office binary contents"));
    }

    @Test void enabledPdfExtractionGrepFindsKnownText() throws IOException {
        writePdf(workspace.resolve("report.pdf"), "Quarterly budget alpha");
        ToolContext extractionCtx = extractionCtx("pdf");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "budget alpha")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("report.pdf"), r.output());
        assertTrue(r.output().contains("Quarterly budget alpha"), r.output());
    }

    @Test void enabledPdfExtractionGrepReportsNoTextPdfAsSkipped() throws IOException {
        writeEmptyPdf(workspace.resolve("scan.pdf"));
        ToolContext extractionCtx = extractionCtx("pdf");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "budget alpha")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("No matches found"), r.output());
        assertTrue(r.output().contains("Skipped unsupported binary document(s): scan.pdf"), r.output());
        assertTrue(r.output().contains("OCR_REQUIRED"), r.output());
        assertFalse(r.output().contains("scan.pdf:"), r.output());
    }

    @Test void enabledDocxExtractionGrepFindsKnownText() throws IOException {
        writeDocx(workspace.resolve("brief.docx"), "Word roadmap beta");
        ToolContext extractionCtx = extractionCtx("word");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "roadmap beta")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("brief.docx"), r.output());
        assertTrue(r.output().contains("Word roadmap beta"), r.output());
    }

    @Test void privateModeDocxExtractionGrepWithholdsOrdinaryPrivateFacts() throws IOException {
        writeDocx(workspace.resolve("medical-notes.docx"), "Patient name: Marina Stavrou");
        ToolContext privateExtractionCtx = extractionCtx("word", Map.of("mode", "private"));

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "Marina Stavrou")), privateExtractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("medical-notes.docx"), r.output());
        assertTrue(r.output().contains("withheld from model context by private-document policy"), r.output());
        assertFalse(r.output().contains("Marina Stavrou"), r.output());
        assertFalse(r.output().contains("Patient name"), r.output());
    }

    @Test void enabledXlsxExtractionGrepFindsKnownCellText() throws IOException {
        writeXlsx(workspace.resolve("budget.xlsx"), "Excel revenue gamma");
        ToolContext extractionCtx = extractionCtx("excel");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "revenue gamma")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("budget.xlsx"), r.output());
        assertTrue(r.output().contains("B2: Excel revenue gamma"), r.output());
    }

    @Test void enabledImageOcrGrepFindsConfiguredOcrText() throws IOException {
        Files.write(workspace.resolve("scan.png"), new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        Config cfg = extractionEnabled("image_ocr");
        family(cfg, "image_ocr").put("command", javaExecutable());
        family(cfg, "image_ocr").put("args", List.of(
                "-cp",
                System.getProperty("java.class.path"),
                FakeOcrCli.class.getName(),
                "{input}"));
        ToolContext extractionCtx = new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg);

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "visible text")), extractionCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("scan.png"), r.output());
        assertTrue(r.output().contains("OCR fixture visible text"), r.output());
        assertFalse(r.output().contains("t267-token-should-not-appear"), r.output());
    }

    @Test void grep_does_not_leak_env_canary() throws IOException {
        Files.writeString(workspace.resolve(".env"), "TALOS_SECRET=DO_NOT_LEAK_T267_ENV\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK_T267_ENV")), ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("DO_NOT_LEAK_T267_ENV"));
        assertTrue(r.output().contains("protected content") || r.output().contains("[redacted"));
    }

    @Test void grep_does_not_leak_env_local_canary() throws IOException {
        Files.writeString(workspace.resolve(".env.local"), "TALOS_SECRET=DO_NOT_LEAK_T267_ENV\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK_T267_ENV")), ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("DO_NOT_LEAK_T267_ENV"));
        assertTrue(r.output().contains("protected content") || r.output().contains("[redacted"));
    }

    @Test void grep_does_not_leak_secrets_directory_canary() throws IOException {
        Files.createDirectories(workspace.resolve("secrets"));
        Files.writeString(workspace.resolve("secrets/private-notes.md"), "DO_NOT_LEAK_T267_SECRETS\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK_T267_SECRETS")), ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("DO_NOT_LEAK_T267_SECRETS"));
        assertTrue(r.output().contains("protected content") || r.output().contains("[redacted"));
    }

    @Test void grep_does_not_leak_protected_directory_canary() throws IOException {
        Files.createDirectories(workspace.resolve("protected"));
        Files.writeString(workspace.resolve("protected/private-notes.md"), "DO_NOT_LEAK_T267_PROTECTED_DIR\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK_T267_PROTECTED_DIR")), ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("DO_NOT_LEAK_T267_PROTECTED_DIR"));
        assertTrue(r.output().contains("protected content") || r.output().contains("[redacted"));
    }

    @Test void grep_redacts_secret_like_assignment_in_normal_file() throws IOException {
        Files.writeString(workspace.resolve("notes.md"), "API_TOKEN=t267-token-should-not-appear\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "API_TOKEN")), ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("API_TOKEN=[redacted]"));
        assertFalse(r.output().contains("t267-token-should-not-appear"));
    }

    @Test void grep_redacts_private_marker_in_normal_file() throws IOException {
        Files.writeString(workspace.resolve("notes.md"),
                "PRIVATE_MARKER = DO_NOT_LEAK_T267_PRIVATE_MARKER\nordinary searchable text\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "PRIVATE_MARKER")), ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("PRIVATE_MARKER=[redacted]"));
        assertFalse(r.output().contains("DO_NOT_LEAK_T267_PRIVATE_MARKER"));
    }

    @Test void grep_renders_redacted_private_marker_locator_without_question_substitution()
            throws IOException {
        Files.writeString(workspace.resolve("notes.md"),
                "ordinary searchable text\nPRIVATE_MARKER = DO_NOT_LEAK_T870_PRIVATE_MARKER\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "PRIVATE_MARKER")), ctx);

        assertTrue(r.success(), r.errorMessage());
        String output = r.output();
        assertTrue(output.contains("notes.md:2 | PRIVATE_MARKER=[redacted]"), output);
        assertFalse(output.contains("line?2"), output);
        assertFalse(output.contains("PRIVATE_MARKER?"), output);
        assertFalse(output.contains("[redacted?"), output);
        assertFalse(output.contains("DO_NOT_LEAK_T870_PRIVATE_MARKER"), output);
    }

    @Test void grep_truncates_long_match_with_ascii_marker_to_avoid_glyph_fallback()
            throws IOException {
        String longLine = "SEARCH_TARGET " + "x".repeat(240);
        Files.writeString(workspace.resolve("notes.md"), "ordinary searchable text\n" + longLine + "\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "SEARCH_TARGET")), ctx);

        assertTrue(r.success(), r.errorMessage());
        String output = r.output();
        assertTrue(output.contains("notes.md:2 | "), output);
        assertTrue(output.contains("..."), output);
        assertFalse(output.contains("…"), output);
        assertFalse(output.contains("?"), output);
    }

    @Test void privateModeGrepDoesNotExposeNeighborFieldsAroundCanaryMatches() throws IOException {
        Files.writeString(workspace.resolve("bank.csv"),
                "account,balance,marker\nchecking,4812.44,DO_NOT_LEAK_PRIVATE_ROW\n");
        Config cfg = new Config();
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of("mode", "private")));
        ToolContext privateCtx = new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg);

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK_PRIVATE_ROW")), privateCtx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("bank.csv"), r.output());
        assertFalse(r.output().contains("DO_NOT_LEAK_PRIVATE_ROW"), r.output());
        assertFalse(r.output().contains("4812.44"), r.output());
        assertFalse(r.output().contains("checking"), r.output());
        assertTrue(r.output().contains("withheld by private-mode search policy"), r.output());
    }

    @Test void unsupported_binary_grep_skips_and_reports_without_include_glob() throws IOException {
        Files.writeString(workspace.resolve("report.docx"), "budget canary in fake docx payload\n");

        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "budget")), ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("fake docx payload"));
        assertTrue(r.output().contains("Search was limited to searchable text files")
                || r.output().contains("Skipped unsupported"));
    }

    @Test void maxResultsRespected() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "public", "max_results", "1")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("1 match"));
    }

    @Test void skipsGitDirectory() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "git config")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("No matches"));
    }

    @Test void missingPatternParam() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of()), ctx);
        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test void invalidRegexReturnsError() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "[invalid", "regex", "true")), ctx);
        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test void matchesIncludeLineNumbers() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "class App", "include", "*.java")), ctx);
        assertTrue(r.success());
        // GrepTool format: "path:line | content"
        assertTrue(r.output().contains(":2 "), "Expected line number in output: " + r.output());
    }

    @Test void caseInsensitiveByDefault() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "PUBLIC CLASS")), ctx);
        assertTrue(r.success());
        assertFalse(r.output().contains("No matches"));
    }

    private ToolContext extractionCtx(String family) {
        return new ToolContext(workspace, new Sandbox(workspace, Map.of()), extractionEnabled(family));
    }

    private ToolContext extractionCtx(String family, Map<String, Object> privacy) {
        Config cfg = extractionEnabled(family);
        cfg.data.put("privacy", new LinkedHashMap<>(privacy));
        return new ToolContext(workspace, new Sandbox(workspace, Map.of()), cfg);
    }

    private static Config extractionEnabled(String family) {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        Map<String, Object> familyCfg = new LinkedHashMap<>();
        familyCfg.put("enabled", Boolean.TRUE);
        documentExtraction.put(family, familyCfg);
        cfg.data.put("document_extraction", documentExtraction);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> family(Config cfg, String family) {
        return (Map<String, Object>) ((Map<String, Object>) cfg.data.get("document_extraction")).get(family);
    }

    private static String javaExecutable() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static void writePdf(Path path, String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            document.save(path.toFile());
        }
    }

    private static void writeEmptyPdf(Path path) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(path.toFile());
        }
    }

    private static void writeDocx(Path path, String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(text);
            try (var out = Files.newOutputStream(path)) {
                document.write(out);
            }
        }
    }

    private static void writeXlsx(Path path, String text) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Budget");
            var row = sheet.createRow(1);
            row.createCell(1).setCellValue(text);
            try (var out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
        }
    }
}
