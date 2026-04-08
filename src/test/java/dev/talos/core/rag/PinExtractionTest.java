package dev.talos.core.rag;

import dev.talos.cli.modes.RagMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for robust pin extraction across various path formats:
 * - Backslashes vs forward slashes
 * - Quoted paths with spaces
 * - Extensionless files (LICENSE)
 * - Dotfiles (.editorconfig)
 * - Uppercase extensions (README.MD)
 */
public class PinExtractionTest {

    @Test
    public void testBackslashPaths(@TempDir Path tempDir) throws Exception {
        // Create test files
        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(docsDir);
        Path landingFile = docsDir.resolve("landing.md");
        Files.writeString(landingFile, "# Landing\nSome content");

        // Test backslash path
        String query = "Summarize docs\\landing.md";
        List<?> pinned = invokePinFiles(tempDir, query, 3, 1600, 10);

        assertTrue(pinned.size() > 0, "Should pin file with backslash path");
        String pinnedPath = extractPath(pinned.get(0));
        assertEquals("docs/landing.md#0", pinnedPath, "Path should be normalized to forward slashes");
    }

    @Test
    public void testForwardSlashPaths(@TempDir Path tempDir) throws Exception {
        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(docsDir);
        Path landingFile = docsDir.resolve("landing.md");
        Files.writeString(landingFile, "# Landing\nSome content");

        String query = "Summarize docs/landing.md";
        List<?> pinned = invokePinFiles(tempDir, query, 3, 1600, 10);

        assertTrue(pinned.size() > 0, "Should pin file with forward slash path");
        String pinnedPath = extractPath(pinned.get(0));
        assertEquals("docs/landing.md#0", pinnedPath);
    }

    @Test
    public void testQuotedPathsWithSpaces(@TempDir Path tempDir) throws Exception {
        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(docsDir);
        Path myNotesDir = docsDir.resolve("My Notes");
        Files.createDirectories(myNotesDir);
        Path introFile = myNotesDir.resolve("intro.md");
        Files.writeString(introFile, "# Introduction");

        String query = "Compare \"docs/My Notes/intro.md\" with README";
        List<?> pinned = invokePinFiles(tempDir, query, 3, 1600, 10);

        assertTrue(pinned.size() > 0, "Should pin quoted file with spaces");
        String pinnedPath = extractPath(pinned.get(0));
        assertTrue(pinnedPath.contains("My Notes"), "Should preserve directory name with spaces");
    }

    @Test
    public void testExtensionlessFiles(@TempDir Path tempDir) throws Exception {
        Path licenseFile = tempDir.resolve("LICENSE");
        Files.writeString(licenseFile, "MIT License\nCopyright...");

        String query = "What does LICENSE say?";
        List<?> pinned = invokePinFiles(tempDir, query, 3, 1600, 10);

        assertTrue(pinned.size() > 0, "Should pin extensionless LICENSE file");
        String pinnedPath = extractPath(pinned.get(0));
        assertEquals("LICENSE#0", pinnedPath);
    }

    @Test
    public void testDotfiles(@TempDir Path tempDir) throws Exception {
        Path editorConfig = tempDir.resolve(".editorconfig");
        Files.writeString(editorConfig, "root = true\n[*]\nindent_style = space");

        String query = "Show me .editorconfig";
        List<?> pinned = invokePinFiles(tempDir, query, 3, 1600, 10);

        assertTrue(pinned.size() > 0, "Should pin dotfile .editorconfig");
        String pinnedPath = extractPath(pinned.get(0));
        assertEquals(".editorconfig#0", pinnedPath);
    }

    @Test
    public void testUppercaseExtensions(@TempDir Path tempDir) throws Exception {
        Path readmeFile = tempDir.resolve("README.MD");
        Files.writeString(readmeFile, "# README\nProject info");

        String query = "Check README.MD";
        List<?> pinned = invokePinFiles(tempDir, query, 3, 1600, 10);

        assertTrue(pinned.size() > 0, "Should pin file with uppercase extension");
        String pinnedPath = extractPath(pinned.get(0));
        assertEquals("README.MD#0", pinnedPath);
    }

    @Test
    public void testPowerShellScripts(@TempDir Path tempDir) throws Exception {
        Path scriptFile = tempDir.resolve("final-test.ps1");
        Files.writeString(scriptFile, "# PowerShell script\nWrite-Host 'Hello'");

        String query = "Explain final-test.ps1";
        List<?> pinned = invokePinFiles(tempDir, query, 3, 1600, 10);

        assertTrue(pinned.size() > 0, "Should pin .ps1 file");
        String pinnedPath = extractPath(pinned.get(0));
        assertEquals("final-test.ps1#0", pinnedPath);
    }

    @Test
    public void testMixedSeparators(@TempDir Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("src").resolve("main");
        Files.createDirectories(srcDir);
        Path javaFile = srcDir.resolve("App.java");
        Files.writeString(javaFile, "public class App {}");

        // Mix backslashes and forward slashes
        String query = "Compare src\\main/App.java";
        List<?> pinned = invokePinFiles(tempDir, query, 3, 1600, 10);

        assertTrue(pinned.size() > 0, "Should pin file with mixed separators");
        String pinnedPath = extractPath(pinned.get(0));
        assertEquals("src/main/App.java#0", pinnedPath, "Should normalize to forward slashes");
    }

    @Test
    public void testTwoFileComparison(@TempDir Path tempDir) throws Exception {
        Path readme = tempDir.resolve("README.md");
        Files.writeString(readme, "# README");

        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(docsDir);
        Path landing = docsDir.resolve("landing.md");
        Files.writeString(landing, "# Landing");

        String query = "Compare README.md and docs\\landing.md";
        List<?> pinned = invokePinFiles(tempDir, query, 3, 1600, 10);

        assertEquals(2, pinned.size(), "Should pin both files");
        String path1 = extractPath(pinned.get(0));
        String path2 = extractPath(pinned.get(1));

        assertTrue(path1.equals("README.md#0") || path2.equals("README.md#0"), "Should pin README.md");
        assertTrue(path1.equals("docs/landing.md#0") || path2.equals("docs/landing.md#0"), "Should pin docs/landing.md");
    }

    // Helper to invoke private pinFiles method via reflection
    private List<?> invokePinFiles(Path workspace, String query, int maxPins, int maxChars, int maxDepth) throws Exception {
        Method method = RagMode.class.getDeclaredMethod("pinFiles", Path.class, String.class, int.class, int.class, int.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(null, workspace, query, maxPins, maxChars, maxDepth);
    }

    // Helper to extract path from Snippet object
    private String extractPath(Object snippet) throws Exception {
        Method pathMethod = snippet.getClass().getDeclaredMethod("path");
        pathMethod.setAccessible(true);
        return (String) pathMethod.invoke(snippet);
    }
}

