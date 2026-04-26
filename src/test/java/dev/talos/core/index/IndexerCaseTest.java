package dev.talos.core.index;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for case-sensitive/case-insensitive file matching in the Indexer.
 */
class IndexerCaseTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testWindowsCaseInsensitiveMatching(@TempDir Path tempDir) throws Exception {
        // Create test files with uppercase extensions
        Path indexHtml = tempDir.resolve("INDEX.HTML");
        Path readmeTxt = tempDir.resolve("README.TXT");
        Path testJava = tempDir.resolve("Test.JAVA");

        Files.writeString(indexHtml, "<html><body>Test HTML content</body></html>");
        Files.writeString(readmeTxt, "This is a test README file");
        Files.writeString(testJava, "public class Test { }");

        // Create config and override with test data
        Config config = createTestConfig();
        Indexer indexer = new Indexer(config);

        // Create a simple predicate to test file matching
        var includeGlobs = java.util.List.of("**/*.html", "**/*.txt", "**/*.java");
        var excludeGlobs = java.util.List.<String>of();

        // Use reflection to access the private method for testing
        var method = Indexer.class.getDeclaredMethod("createFileFilter", Path.class, java.util.List.class, java.util.List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.function.Predicate<Path> predicate =
            (java.util.function.Predicate<Path>) method.invoke(indexer, tempDir, includeGlobs, excludeGlobs);

        // On Windows, these uppercase files should match lowercase patterns
        assertTrue(predicate.test(indexHtml), "INDEX.HTML should match **/*.html on Windows");
        assertTrue(predicate.test(readmeTxt), "README.TXT should match **/*.txt on Windows");
        assertTrue(predicate.test(testJava), "Test.JAVA should match **/*.java on Windows");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testNonWindowsCaseSensitiveMatching(@TempDir Path tempDir) throws Exception {
        // Create test files with uppercase extensions
        Path indexHtml = tempDir.resolve("INDEX.HTML");
        Path readmeTxt = tempDir.resolve("README.TXT");

        Files.writeString(indexHtml, "<html><body>Test HTML content</body></html>");
        Files.writeString(readmeTxt, "This is a test README file");

        // Create config and override with test data
        Config config = createTestConfig();
        Indexer indexer = new Indexer(config);

        // Create a simple predicate to test file matching
        var includeGlobs = java.util.List.of("**/*.html", "**/*.txt");
        var excludeGlobs = java.util.List.<String>of();

        // Use reflection to access the private method for testing
        var method = Indexer.class.getDeclaredMethod("createFileFilter", Path.class, java.util.List.class, java.util.List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.function.Predicate<Path> predicate =
            (java.util.function.Predicate<Path>) method.invoke(indexer, tempDir, includeGlobs, excludeGlobs);

        // On Linux/macOS, these uppercase files should NOT match lowercase patterns
        assertFalse(predicate.test(indexHtml), "INDEX.HTML should NOT match **/*.html on Linux/macOS");
        assertFalse(predicate.test(readmeTxt), "README.TXT should NOT match **/*.txt on Linux/macOS");
    }

    @Test
    void testExcludePatternsBehavior(@TempDir Path tempDir) throws Exception {
        // Create files in various directories
        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(buildDir);
        Path buildHtml = buildDir.resolve("index.html");
        Path rootHtml = tempDir.resolve("main.html");

        Files.writeString(buildHtml, "<html>Build content</html>");
        Files.writeString(rootHtml, "<html>Main content</html>");

        Config config = createTestConfig();
        Indexer indexer = new Indexer(config);

        var includeGlobs = java.util.List.of("**/*.html");
        var excludeGlobs = java.util.List.of("**/build/**");

        // Use reflection to access the private method for testing
        var method = Indexer.class.getDeclaredMethod("createFileFilter", Path.class, java.util.List.class, java.util.List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.function.Predicate<Path> predicate =
            (java.util.function.Predicate<Path>) method.invoke(indexer, tempDir, includeGlobs, excludeGlobs);

        // Root HTML should be included, build HTML should be excluded
        assertTrue(predicate.test(rootHtml), "main.html should be included");
        assertFalse(predicate.test(buildHtml), "build/index.html should be excluded");
    }

    @Test
    void defaultIncludesMatchCsvAndTsvFiles(@TempDir Path tempDir) throws Exception {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Path csv = dataDir.resolve("metrics.csv");
        Path tsv = dataDir.resolve("metrics.tsv");
        Files.writeString(csv, "name,value\nrequests,42\n");
        Files.writeString(tsv, "name\tvalue\nrequests\t42\n");

        Config config = new Config();
        Indexer indexer = new Indexer(config);
        @SuppressWarnings("unchecked")
        Map<String, Object> rag = (Map<String, Object>) config.data.get("rag");
        @SuppressWarnings("unchecked")
        List<String> includeGlobs = (List<String>) rag.get("includes");
        @SuppressWarnings("unchecked")
        List<String> excludeGlobs = (List<String>) rag.get("excludes");

        var method = Indexer.class.getDeclaredMethod("createFileFilter", Path.class, java.util.List.class, java.util.List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.function.Predicate<Path> predicate =
                (java.util.function.Predicate<Path>) method.invoke(indexer, tempDir, includeGlobs, excludeGlobs);

        assertTrue(predicate.test(csv), "metrics.csv should match default RAG includes");
        assertTrue(predicate.test(tsv), "metrics.tsv should match default RAG includes");
    }

    private Config createTestConfig() throws Exception {
        // Create a default config and then override its data for testing
        Config config = new Config();

        // Use reflection to access the data field and override it
        Field dataField = Config.class.getField("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) dataField.get(config);

        // Override with test data
        data.put("rag", Map.of(
            "includes", java.util.List.of("**/*.html", "**/*.txt", "**/*.java"),
            "excludes", java.util.List.of("**/build/**", "**/.git/**")
        ));

        return config;
    }
}
