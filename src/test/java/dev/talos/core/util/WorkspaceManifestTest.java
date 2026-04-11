package dev.talos.core.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceManifestTest {

    @TempDir Path tmp;

    @Nested class Build {

        @Test
        void returnsEmptyForNullWorkspace() {
            assertEquals("", WorkspaceManifest.build(null));
        }

        @Test
        void returnsEmptyForNonexistentPath() {
            assertEquals("", WorkspaceManifest.build(tmp.resolve("nope")));
        }

        @Test
        void includesWorkspacePath() {
            String manifest = WorkspaceManifest.build(tmp);
            assertTrue(manifest.startsWith("Workspace: "), "Should start with Workspace:");
        }

        @Test
        void includesFileStructureSection() throws IOException {
            Files.createFile(tmp.resolve("hello.txt"));
            String manifest = WorkspaceManifest.build(tmp);
            assertTrue(manifest.contains("File structure:"), "Should have file tree section");
            assertTrue(manifest.contains("hello.txt"), "Should list the file");
        }

        @Test
        void includesReadmeExcerpt() throws IOException {
            Files.writeString(tmp.resolve("README.md"), "# My Project\nThis is a test project.");
            String manifest = WorkspaceManifest.build(tmp);
            assertTrue(manifest.contains("README (excerpt):"), "Should have README section");
            assertTrue(manifest.contains("My Project"), "Should include README content");
        }

        @Test
        void respectsManifestMaxChars() throws IOException {
            // Create a README that's very long
            String longContent = "# Big README\n" + "x".repeat(3000);
            Files.writeString(tmp.resolve("README.md"), longContent);
            // Create many files
            for (int i = 0; i < 50; i++) {
                Files.createFile(tmp.resolve("file-" + i + ".java"));
            }

            String manifest = WorkspaceManifest.build(tmp);
            assertTrue(manifest.length() <= 2010, // 2000 + "..." suffix
                    "Manifest should be capped: " + manifest.length());
        }
    }

    @Nested class BuildTree {

        @Test
        void emptyDirReturnsEmptyTree() {
            assertEquals("", WorkspaceManifest.buildTree(tmp));
        }

        @Test
        void listsFilesAndDirs() throws IOException {
            Files.createDirectory(tmp.resolve("src"));
            Files.createFile(tmp.resolve("build.gradle"));
            Files.createFile(tmp.resolve("src/Main.java"));

            String tree = WorkspaceManifest.buildTree(tmp);
            assertTrue(tree.contains("src/"), "Should list directory with trailing /");
            assertTrue(tree.contains("build.gradle"), "Should list file");
            assertTrue(tree.contains("src/Main.java"), "Should list nested file");
        }

        @Test
        void skipsGitDirectory() throws IOException {
            Files.createDirectories(tmp.resolve(".git/objects"));
            Files.createFile(tmp.resolve("app.js"));

            String tree = WorkspaceManifest.buildTree(tmp);
            assertFalse(tree.contains(".git"), "Should skip .git");
            assertTrue(tree.contains("app.js"), "Should include normal files");
        }

        @Test
        void skipsNodeModules() throws IOException {
            Files.createDirectories(tmp.resolve("node_modules/lodash"));
            Files.createFile(tmp.resolve("index.js"));

            String tree = WorkspaceManifest.buildTree(tmp);
            assertFalse(tree.contains("node_modules"), "Should skip node_modules");
            assertTrue(tree.contains("index.js"), "Should include normal files");
        }

        @Test
        void skipsBuildDirectory() throws IOException {
            Files.createDirectories(tmp.resolve("build/classes"));
            Files.createFile(tmp.resolve("Main.java"));

            String tree = WorkspaceManifest.buildTree(tmp);
            assertFalse(tree.contains("build"), "Should skip build dir");
        }

        @Test
        void keepsGithubDirectory() throws IOException {
            Files.createDirectories(tmp.resolve(".github/workflows"));
            Files.createFile(tmp.resolve(".github/workflows/ci.yml"));

            String tree = WorkspaceManifest.buildTree(tmp);
            assertTrue(tree.contains(".github"), "Should keep .github");
        }

        @Test
        void truncatesLargeDirectories() throws IOException {
            for (int i = 0; i < 90; i++) {
                Files.createFile(tmp.resolve(String.format("file-%03d.txt", i)));
            }
            String tree = WorkspaceManifest.buildTree(tmp);
            assertTrue(tree.contains("... (truncated)"), "Should truncate at 80 entries");
        }
    }

    @Nested class ReadReadme {

        @Test
        void returnsEmptyWhenNoReadme() {
            assertEquals("", WorkspaceManifest.readReadme(tmp));
        }

        @Test
        void readsReadmeMd() throws IOException {
            Files.writeString(tmp.resolve("README.md"), "# Hello World");
            assertEquals("# Hello World", WorkspaceManifest.readReadme(tmp));
        }

        @Test
        void readsReadmeTxt() throws IOException {
            Files.writeString(tmp.resolve("README.txt"), "Hello from txt");
            assertEquals("Hello from txt", WorkspaceManifest.readReadme(tmp));
        }

        @Test
        void truncatesLongReadme() throws IOException {
            String content = "# Title\n" + "a".repeat(1000);
            Files.writeString(tmp.resolve("README.md"), content);

            String result = WorkspaceManifest.readReadme(tmp);
            assertTrue(result.length() <= 610, // 600 + "\n..." suffix
                    "Should truncate long README: " + result.length());
            assertTrue(result.endsWith("..."), "Should end with ...");
        }
    }
}

