package dev.talos.core.index;

import dev.talos.core.Config;
import dev.talos.core.cache.CacheDb;
import dev.talos.core.embed.EmbeddingsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The real indexer and store are exercised, with Git itself where it is available. */
class IndexerGitExclusionsTest {
    @TempDir Path temp;
    private Path workspace;
    private Path indexDir;

    @BeforeEach
    void workspace() throws IOException {
        workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.writeString(workspace.resolve("README.md"), "The public loom catalog.\n");
        Files.writeString(workspace.resolve("notes.md"), "An untracked loom note.\n");
        Files.createDirectory(workspace.resolve("local"));
        Files.writeString(workspace.resolve("local").resolve("report.md"), "A local loom report.\n");
    }

    @AfterEach
    void cleanIndex() throws IOException {
        if (indexDir != null && Files.exists(indexDir)) {
            try (var paths = Files.walk(indexDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void gitIgnoredFilesAreNotIndexedInARepository() throws Exception {
        initRepository();
        Indexer indexer = new Indexer(safeRagConfig(true));
        index(indexer);
        assertIndexed(indexer, "README.md", true);
        assertIndexed(indexer, "notes.md", true);
        assertIndexed(indexer, "local/report.md", false);
    }

    @Test
    void theInjectedScopeIntersectsTheConfiguredWalk() throws Exception {
        Indexer indexer = indexer(safeRagConfig(true), root -> new GitExclusions.Scope(Set.of("README.md"), "applied"));
        index(indexer);
        assertIndexed(indexer, "README.md", true);
        assertIndexed(indexer, "notes.md", false);
        assertIndexed(indexer, "local/report.md", false);
    }

    @Test
    void anUnansweredScopeKeepsTheConfiguredWalk() throws Exception {
        Indexer indexer = indexer(safeRagConfig(true), root -> new GitExclusions.Scope(null, "git is not available"));
        index(indexer);
        assertIndexed(indexer, "README.md", true);
        assertIndexed(indexer, "notes.md", true);
        assertIndexed(indexer, "local/report.md", true);
    }

    @Test
    void theSettingTurnsGitExclusionsOff() throws Exception {
        Indexer indexer = indexer(safeRagConfig(false), root -> new GitExclusions.Scope(Set.of("README.md"), "applied"));
        index(indexer);
        assertIndexed(indexer, "README.md", true);
        assertIndexed(indexer, "local/report.md", true);
    }

    @Test
    void gitScopeListsKeptFilesAndAnswersNothingOutsideARepository() throws Exception {
        initRepository();
        GitExclusions.Scope scope = GitExclusions.resolve(workspace);
        assertTrue(scope.applies(), scope.note());
        assertTrue(scope.keeps("README.md"));
        assertTrue(scope.keeps("notes.md"));
        assertTrue(scope.keeps(".gitignore"));
        assertFalse(scope.keeps("local/report.md"));
        assertFalse(scope.keeps("local\\report.md"));

        Path outside = Files.createDirectory(temp.resolve("outside"));
        GitExclusions.Scope none = GitExclusions.resolve(outside);
        assertFalse(none.applies(), none.note());
        assertTrue(none.keeps("anything.md"));
        assertTrue(none.note().contains("did not list this workspace"), none.note());
        assertFalse(none.note().contains(outside.toString()), "paths in git output stay masked: " + none.note());
    }

    @Test
    void anIgnoredWorkspaceKeepsTheConfiguredWalk() throws Exception {
        initRepository();
        Path ignored = workspace.resolve("local");
        GitExclusions.Scope scope = GitExclusions.resolve(ignored);
        assertFalse(scope.applies(), scope.note());
        assertTrue(scope.note().contains("no kept files"), scope.note());
    }

    @Test
    void aNestedRepositoryIsListedUnderItsPrefix() throws Exception {
        initRepository();
        Path nested = Files.createDirectory(workspace.resolve("nested"));
        Files.writeString(nested.resolve("inner.md"), "An inner loom note.\n");
        Files.writeString(nested.resolve("secret.log"), "ignored inside\n");
        git(nested, "init", "-q");
        Files.writeString(nested.resolve(".gitignore"), "*.log\n");
        GitExclusions.Scope scope = GitExclusions.resolve(workspace);
        assertTrue(scope.applies(), scope.note());
        assertTrue(scope.keeps("nested/inner.md"));
        assertFalse(scope.keeps("nested/secret.log"));
        assertTrue(scope.keeps("README.md"));
    }

    @Test
    void chunksOfFilesThatLeaveTheCorpusAreRemovedOnTheNextRun() throws Exception {
        Config cfg = safeRagConfig(true);
        Indexer wide = indexer(cfg, root -> new GitExclusions.Scope(Set.of("README.md", "notes.md", "local/report.md"), "applied"));
        index(wide);
        assertIndexed(wide, "local/report.md", true);
        Indexer narrow = indexer(cfg, root -> new GitExclusions.Scope(Set.of("README.md", "notes.md"), "applied"));
        narrow.index(workspace, false);
        assertIndexed(narrow, "README.md", true);
        assertIndexed(narrow, "local/report.md", false);
    }

    @Test
    void gitIsResolvedFromAnAbsolutePathEntryAndNotFromTheWorkspace() throws Exception {
        initRepository();
        Files.writeString(workspace.resolve("git.exe"), "not a program\n");
        Files.writeString(workspace.resolve("git"), "not a program\n");
        Path git = GitExclusions.executable();
        assertNotNull(git);
        assertTrue(git.isAbsolute(), git.toString());
        assertFalse(git.startsWith(workspace), git.toString());
        GitExclusions.Scope scope = GitExclusions.resolve(workspace);
        assertTrue(scope.applies(), scope.note());
        assertTrue(scope.keeps("README.md"));
    }

    @Test
    void aFileNameWithAHashMarkKeepsItsChunksAcrossRuns() throws Exception {
        Files.writeString(workspace.resolve("notes#1.md"), "A hashed loom note.\n");
        Config cfg = safeRagConfig(false);
        Indexer indexer = indexer(cfg, root -> new GitExclusions.Scope(null, "off"));
        index(indexer);
        assertIndexed(indexer, "notes#1.md", true);
        indexer.index(workspace, false);
        assertIndexed(indexer, "notes#1.md", true);
    }

    @Test
    void aNestedWorktreeWithAGitFileIsListedUnderItsPrefix() throws Exception {
        initRepository();
        Path nested = Files.createDirectory(workspace.resolve("worktree"));
        Files.writeString(nested.resolve("inner.md"), "An inner loom note.\n");
        git(nested, "init", "-q");
        Path separate = Files.createDirectory(temp.resolve("separate-git-dir"));
        try (var paths = Files.walk(nested.resolve(".git"))) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        git(temp, "init", "-q", "--separate-git-dir", separate.toString(), nested.toString());
        assertTrue(Files.isRegularFile(nested.resolve(".git")), "the nested repository must use a .git file");
        GitExclusions.Scope scope = GitExclusions.resolve(workspace);
        assertTrue(scope.applies(), scope.note());
        assertTrue(scope.keeps("worktree/inner.md"), scope.kept().toString());
    }

    private void initRepository() throws Exception {
        try {
            git(workspace, "init", "-q");
        } catch (IOException unavailable) {
            Assumptions.abort("git is not available on this machine");
            return;
        }
        // A developer's global ignore file must not change what this repository keeps.
        Path noGlobalExcludes = Files.createFile(temp.resolve("no-global-excludes"));
        git(workspace, "config", "core.excludesFile", noGlobalExcludes.toString());
        Files.writeString(workspace.resolve(".gitignore"), "local/\n");
        git(workspace, "add", "README.md");
    }

    private static void git(Path directory, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("git", "-C", directory.toString()));
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertEquals(0, process.waitFor(), "git " + String.join(" ", arguments));
    }

    private Indexer indexer(Config cfg, Function<Path, GitExclusions.Scope> scope) {
        return new Indexer(cfg, () -> EmbeddingsFactory.forDocument(cfg), CacheDb::new, scope);
    }

    private void index(Indexer indexer) {
        indexDir = indexer.indexDirFor(workspace);
        indexer.index(workspace, true);
    }

    private void assertIndexed(Indexer indexer, String path, boolean expected) throws Exception {
        try (LuceneStore store = new LuceneStore(indexer.indexDirFor(workspace), 0)) {
            assertEquals(expected, store.getTextByPath(path + "#0") != null, path);
        }
    }

    private static Config safeRagConfig(boolean gitExclusions) {
        Config cfg = new Config(null);
        cfg.data.put("embed", new LinkedHashMap<>(Map.of("provider", "disabled", "model", "disabled")));
        @SuppressWarnings("unchecked")
        Map<String, Object> rag = new LinkedHashMap<>((Map<String, Object>) cfg.data.get("rag"));
        rag.put("vectors", new LinkedHashMap<>(Map.of("enabled", false)));
        rag.put("git_exclusions", gitExclusions);
        cfg.data.put("rag", rag);
        cfg.data.put("net", new LinkedHashMap<>(Map.of("enabled", false)));
        return cfg;
    }
}
