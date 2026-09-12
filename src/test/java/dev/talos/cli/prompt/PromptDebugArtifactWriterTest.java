package dev.talos.cli.prompt;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PromptDebugArtifactWriterTest {
    @TempDir Path directory;

    @Test
    void latestExportsHaveIsolatedGroupsAndPreserveEarlierBytes() throws Exception {
        var first = PromptDebugArtifactWriter.writeLatest(directory, snapshot("first request", true));
        byte[] firstRender = Files.readAllBytes(first.renderPath());
        byte[] firstBody = Files.readAllBytes(first.providerBodyPath().orElseThrow());

        var second = PromptDebugArtifactWriter.writeLatest(directory, snapshot("second request", true));

        assertNotEquals(directory, first.renderPath().getParent(), "Each export owns a new group");
        assertNotEquals(first.renderPath().getParent(), second.renderPath().getParent());
        assertEquals(first.renderPath().getParent(), first.providerBodyPath().orElseThrow().getParent());
        assertEquals(second.renderPath().getParent(), second.providerBodyPath().orElseThrow().getParent());
        assertArrayEquals(firstRender, Files.readAllBytes(first.renderPath()));
        assertArrayEquals(firstBody, Files.readAllBytes(first.providerBodyPath().orElseThrow()));
        assertTrue(Files.readString(second.renderPath()).contains("second request"));
        assertLatestMembership(first);
        assertLatestMembership(second);
    }

    @Test
    void latestWithoutBodyCannotInheritAnEarlierSidecar() throws Exception {
        var first = PromptDebugArtifactWriter.writeLatest(directory, snapshot("with body", true));
        byte[] firstBody = Files.readAllBytes(first.providerBodyPath().orElseThrow());
        var second = PromptDebugArtifactWriter.writeLatest(directory, snapshot("without body", false));

        assertTrue(second.providerBodyPath().isEmpty());
        assertNotEquals(first.renderPath().getParent(), second.renderPath().getParent());
        assertEquals(Set.of(second.renderPath().getFileName().toString(), "index.md"),
                memberNames(second.renderPath().getParent()));
        assertTrue(Files.readString(second.renderPath().getParent().resolve("index.md"))
                .contains("provider: absent"));
        assertArrayEquals(firstBody, Files.readAllBytes(first.providerBodyPath().orElseThrow()));
        assertLatestMembership(second);
    }

    @Test
    void shorterHistoryPreservesOldIndexAndDeclaresOnlyItsOwnMembers() throws Exception {
        var first = PromptDebugArtifactWriter.writeHistory(directory,
                List.of(snapshot("history one", true), snapshot("history two", true)));
        List<Path> oldPaths = new ArrayList<>(List.of(first.indexPath()));
        for (var capture : first.captures()) {
            oldPaths.add(capture.renderPath());
            capture.providerBodyPath().ifPresent(oldPaths::add);
        }
        List<byte[]> oldBytes = new ArrayList<>();
        for (Path path : oldPaths) oldBytes.add(Files.readAllBytes(path));

        var second = PromptDebugArtifactWriter.writeHistory(directory,
                List.of(snapshot("short history", false)));

        assertNotEquals(first.indexPath().getParent(), second.indexPath().getParent());
        assertEquals(1, second.captures().size());
        assertEquals(Set.of(second.captures().getFirst().renderPath().getFileName().toString(), "index.md"),
                memberNames(second.indexPath().getParent()));
        String index = Files.readString(second.indexPath());
        assertTrue(index.contains(second.captures().getFirst().renderPath().getFileName().toString()), index);
        assertTrue(index.contains("provider: absent"), index);
        assertFalse(index.contains("2. "), index);
        for (int i = 0; i < oldPaths.size(); i++) {
            assertArrayEquals(oldBytes.get(i), Files.readAllBytes(oldPaths.get(i)), oldPaths.get(i).toString());
        }
    }

    @Test
    void concurrentExportsNeverShareOrOverwriteAGroup() throws Exception {
        int count = 8;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(count)) {
            List<Future<PromptDebugArtifactWriter.LatestArtifact>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String text = "concurrent request " + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IOException("start latch expired");
                    return PromptDebugArtifactWriter.writeLatest(directory, snapshot(text, true));
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            Set<Path> groups = new HashSet<>();
            for (int i = 0; i < count; i++) {
                var artifact = futures.get(i).get(10, TimeUnit.SECONDS);
                assertTrue(groups.add(artifact.renderPath().getParent()), "Group was reused");
                assertTrue(Files.readString(artifact.renderPath()).contains("concurrent request " + i));
                assertLatestMembership(artifact);
            }
            assertEquals(count, groups.size());
        }
    }

    @Test
    void invalidDestinationFailsWithoutReplacingExistingFile() throws Exception {
        Path file = directory.resolve("not-a-directory");
        Files.writeString(file, "preserved destination");
        assertThrows(IOException.class,
                () -> PromptDebugArtifactWriter.writeLatest(file, snapshot("cannot save", true)));
        assertEquals("preserved destination", Files.readString(file));
    }

    @Test
    void preExistingMemberFailsNoClobberWithoutCompletingGroup() throws Exception {
        AtomicReference<Path> collision = new AtomicReference<>();

        assertThrows(FileAlreadyExistsException.class, () -> PromptDebugArtifactWriter.writeLatest(
                directory, snapshot("must not replace", true), (path, content) -> {
                    collision.set(path);
                    Files.writeString(path, "existing member", StandardOpenOption.CREATE_NEW);
                    PromptDebugArtifactWriter.writeNewFile(path, content);
                }));

        assertEquals("existing member", Files.readString(collision.get()));
        assertEquals(Set.of(collision.get().getFileName().toString()), memberNames(collision.get().getParent()));
        assertFalse(Files.exists(collision.get().resolveSibling("index.md")));
    }

    @Test
    void preExistingSymlinkMemberCannotOverwriteItsTarget() throws Exception {
        Path target = directory.resolve("unrelated-evidence.txt");
        Files.writeString(target, "preserve unrelated evidence");
        Path capabilityProbe = directory.resolve("symlink-capability-probe");
        try {
            Files.createSymbolicLink(capabilityProbe, target.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException unsupported) {
            assumeTrue(false, "Symlink creation is unavailable: " + unsupported.getClass().getSimpleName());
        }
        Files.delete(capabilityProbe);
        AtomicReference<Path> collision = new AtomicReference<>();

        assertThrows(FileAlreadyExistsException.class, () -> PromptDebugArtifactWriter.writeLatest(
                directory, snapshot("must not follow", true), (path, content) -> {
                    collision.set(path);
                    Files.createSymbolicLink(path, target.toAbsolutePath());
                    PromptDebugArtifactWriter.writeNewFile(path, content);
                }));

        assertTrue(Files.isSymbolicLink(collision.get()));
        assertEquals("preserve unrelated evidence", Files.readString(target));
        assertFalse(Files.exists(collision.get().resolveSibling("index.md")));
    }

    @Test
    void midBodyWriteFailurePreservesPartialGroupAndPreviousExportWithoutIndex() throws Exception {
        var previous = PromptDebugArtifactWriter.writeLatest(directory, snapshot("previous export", true));
        byte[] previousRender = Files.readAllBytes(previous.renderPath());
        byte[] previousBody = Files.readAllBytes(previous.providerBodyPath().orElseThrow());
        byte[] previousIndex = Files.readAllBytes(previous.indexPath());
        AtomicReference<Path> failedBody = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();

        IOException failure = assertThrows(IOException.class, () -> PromptDebugArtifactWriter.writeLatest(
                directory, snapshot("interrupted export", true), (path, content) -> {
                    calls.incrementAndGet();
                    assertFalse(Files.exists(path.resolveSibling("index.md")));
                    if (path.getFileName().toString().endsWith(".provider-body.json")) {
                        failedBody.set(path);
                        Files.writeString(path, "partial body", StandardOpenOption.CREATE_NEW);
                        throw new IOException("injected mid-body write failure");
                    }
                    PromptDebugArtifactWriter.writeNewFile(path, content);
                }));

        assertEquals("injected mid-body write failure", failure.getMessage());
        assertEquals(2, calls.get(), "No subsequent write or retry after failure");
        assertEquals("partial body", Files.readString(failedBody.get()));
        assertTrue(Files.isRegularFile(failedBody.get().resolveSibling("capture-1.md")));
        assertFalse(Files.exists(failedBody.get().resolveSibling("index.md")));
        assertArrayEquals(previousRender, Files.readAllBytes(previous.renderPath()));
        assertArrayEquals(previousBody, Files.readAllBytes(previous.providerBodyPath().orElseThrow()));
        assertArrayEquals(previousIndex, Files.readAllBytes(previous.indexPath()));
    }

    @Test
    void historyCannotExposeIndexBeforeAllListedMembersFinish() throws Exception {
        AtomicReference<Path> failedRender = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();

        assertThrows(AccessDeniedException.class, () -> PromptDebugArtifactWriter.writeHistory(directory,
                List.of(snapshot("first history capture", true), snapshot("denied capture", true)),
                (path, content) -> {
                    calls.incrementAndGet();
                    assertFalse(Files.exists(path.resolveSibling("index.md")));
                    if (path.getFileName().toString().equals("capture-2.md")) {
                        failedRender.set(path);
                        throw new AccessDeniedException(path.toString(), null, "injected permission failure");
                    }
                    PromptDebugArtifactWriter.writeNewFile(path, content);
                }));

        assertEquals(3, calls.get());
        assertEquals(Set.of("capture-1.md", "capture-1.provider-body.json"),
                memberNames(failedRender.get().getParent()));
    }

    @Test
    void indexWriteFailureDoesNotReturnCompletionOrRemoveWrittenMembers() throws Exception {
        AtomicReference<Path> partialIndex = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IOException.class, () -> PromptDebugArtifactWriter.writeLatest(directory,
                snapshot("index failure", true), (path, content) -> {
                    calls.incrementAndGet();
                    if (path.getFileName().toString().equals("index.md")) {
                        partialIndex.set(path);
                        assertTrue(Files.isRegularFile(path.resolveSibling("capture-1.md")));
                        assertTrue(Files.isRegularFile(path.resolveSibling("capture-1.provider-body.json")));
                        Files.writeString(path, "partial index", StandardOpenOption.CREATE_NEW);
                        throw new IOException("injected mid-index write failure");
                    }
                    PromptDebugArtifactWriter.writeNewFile(path, content);
                }));

        assertEquals(3, calls.get());
        assertEquals("partial index", Files.readString(partialIndex.get()));
        assertEquals(Set.of("capture-1.md", "capture-1.provider-body.json", "index.md"),
                memberNames(partialIndex.get().getParent()));
    }

    @Test
    void latestRecordRetainsTwoArgumentSourceCompatibility() {
        Path render = directory.resolve("capture.md");
        var artifact = new PromptDebugArtifactWriter.LatestArtifact(render, java.util.Optional.empty());
        assertEquals(render, artifact.renderPath());
        assertEquals(directory.resolve("index.md"), artifact.indexPath());
    }

    private static void assertLatestMembership(PromptDebugArtifactWriter.LatestArtifact artifact)
            throws IOException {
        Path group = artifact.renderPath().getParent();
        Path index = group.resolve("index.md");
        assertTrue(Files.isRegularFile(index), "Successful export must expose its membership index");
        String content = Files.readString(index);
        assertTrue(content.contains(artifact.renderPath().getFileName().toString()), content);
        Set<String> expected = new HashSet<>(Set.of("index.md", artifact.renderPath().getFileName().toString()));
        if (artifact.providerBodyPath().isPresent()) {
            String bodyName = artifact.providerBodyPath().orElseThrow().getFileName().toString();
            expected.add(bodyName);
            assertTrue(content.contains(bodyName), content);
        }
        assertEquals(expected, memberNames(group));
    }

    private static Set<String> memberNames(Path group) throws IOException {
        try (var entries = Files.list(group)) {
            return entries.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        }
    }

    private static PromptDebugSnapshot snapshot(String text, boolean providerBody) {
        return new PromptDebugSnapshot("COMPAT_CHAT_HTTP_BODY", "synthetic", "fixture", false,
                Instant.parse("2026-09-06T21:13:00Z"), List.of(ChatMessage.user(text)), List.of(),
                ChatRequestControls.defaults(), providerBody
                ? "{\"messages\":[{\"role\":\"user\",\"content\":\"" + text + "\"}]}" : "");
    }
}
