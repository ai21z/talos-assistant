package dev.talos.tools;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, thread-safe undo stack for file operations.
 *
 * <p>Tools that modify workspace files push a snapshot of the previous
 * state before writing. The {@code /undo} command pops the most-recent
 * entry and restores the file.
 *
 * <p>Entries are kept in memory for the lifetime of the CLI session.
 * The stack is bounded (default {@value #DEFAULT_MAX_DEPTH}) — when
 * full, the oldest entry is silently dropped.
 */
public final class FileUndoStack {

    /** An undo entry representing one file mutation. */
    public record UndoEntry(
            Path path,
            String previousContent,
            boolean wasNew,
            String toolName,
            Instant timestamp
    ) {
        /** Human label, e.g. "write_file → src/Foo.java". */
        public String label() {
            String file = path.getFileName() == null ? path.toString() : path.getFileName().toString();
            return toolName + " → " + file;
        }
    }

    private static final int DEFAULT_MAX_DEPTH = 20;

    private final int maxDepth;
    private final Deque<UndoEntry> stack = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();

    public FileUndoStack() { this(DEFAULT_MAX_DEPTH); }

    public FileUndoStack(int maxDepth) {
        this.maxDepth = Math.max(1, maxDepth);
    }

    /** Push a snapshot. Evicts oldest if at capacity. */
    public void push(UndoEntry entry) {
        if (entry == null) return;
        stack.push(entry);
        if (size.incrementAndGet() > maxDepth) {
            stack.pollLast();      // drop oldest
            size.decrementAndGet();
        }
    }

    /** Pop the most-recent entry, or empty if the stack is empty. */
    public Optional<UndoEntry> pop() {
        UndoEntry e = stack.poll();
        if (e != null) size.decrementAndGet();
        return Optional.ofNullable(e);
    }

    /** Peek at the most-recent entry without removing. */
    public Optional<UndoEntry> peek() {
        return Optional.ofNullable(stack.peek());
    }

    public boolean isEmpty() { return stack.isEmpty(); }
    public int size()        { return size.get(); }
    public int maxDepth()    { return maxDepth; }

    /** Clear all entries. */
    public void clear() {
        stack.clear();
        size.set(0);
    }
}

