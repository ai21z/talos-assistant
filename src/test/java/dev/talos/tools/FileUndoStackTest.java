package dev.talos.tools;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FileUndoStack}.
 */
class FileUndoStackTest {

    private static FileUndoStack.UndoEntry entry(String file, String prev, boolean wasNew) {
        return new FileUndoStack.UndoEntry(
                Path.of(file), prev, wasNew, "talos.write_file", Instant.now());
    }

    @Nested class BasicOperations {

        @Test void newStack_isEmpty() {
            var stack = new FileUndoStack();
            assertTrue(stack.isEmpty());
            assertEquals(0, stack.size());
        }

        @Test void push_thenPop_returnsEntry() {
            var stack = new FileUndoStack();
            stack.push(entry("a.txt", "old", false));
            assertFalse(stack.isEmpty());
            assertEquals(1, stack.size());

            var opt = stack.pop();
            assertTrue(opt.isPresent());
            assertEquals("a.txt", opt.get().path().toString());
            assertEquals("old", opt.get().previousContent());
            assertTrue(stack.isEmpty());
        }

        @Test void pop_emptyStack_returnsEmpty() {
            var stack = new FileUndoStack();
            assertTrue(stack.pop().isEmpty());
        }

        @Test void peek_doesNotRemove() {
            var stack = new FileUndoStack();
            stack.push(entry("a.txt", "old", false));

            var peeked = stack.peek();
            assertTrue(peeked.isPresent());
            assertEquals(1, stack.size(), "Peek should not remove");
        }

        @Test void lifo_order() {
            var stack = new FileUndoStack();
            stack.push(entry("first.txt", "1", false));
            stack.push(entry("second.txt", "2", false));
            stack.push(entry("third.txt", "3", false));

            assertEquals("third.txt", stack.pop().get().path().toString());
            assertEquals("second.txt", stack.pop().get().path().toString());
            assertEquals("first.txt", stack.pop().get().path().toString());
            assertTrue(stack.isEmpty());
        }

        @Test void push_null_isIgnored() {
            var stack = new FileUndoStack();
            stack.push(null);
            assertTrue(stack.isEmpty());
        }

        @Test void clear_emptiesStack() {
            var stack = new FileUndoStack();
            stack.push(entry("a.txt", "1", false));
            stack.push(entry("b.txt", "2", false));
            assertEquals(2, stack.size());

            stack.clear();
            assertTrue(stack.isEmpty());
            assertEquals(0, stack.size());
        }
    }

    @Nested class BoundedCapacity {

        @Test void evicts_oldest_whenFull() {
            var stack = new FileUndoStack(3);
            assertEquals(3, stack.maxDepth());

            stack.push(entry("a.txt", "1", false));
            stack.push(entry("b.txt", "2", false));
            stack.push(entry("c.txt", "3", false));
            assertEquals(3, stack.size());

            // Push a 4th — should evict "a.txt" (oldest)
            stack.push(entry("d.txt", "4", false));
            assertEquals(3, stack.size());

            assertEquals("d.txt", stack.pop().get().path().toString());
            assertEquals("c.txt", stack.pop().get().path().toString());
            assertEquals("b.txt", stack.pop().get().path().toString());
            assertTrue(stack.isEmpty());
        }

        @Test void defaultMaxDepth_is20() {
            var stack = new FileUndoStack();
            assertEquals(20, stack.maxDepth());
        }

        @Test void minDepth_isOne() {
            var stack = new FileUndoStack(0); // clamps to 1
            assertEquals(1, stack.maxDepth());
        }
    }

    @Nested class UndoEntryRecord {

        @Test void wasNew_tracksCreation() {
            var created = entry("new.txt", null, true);
            assertTrue(created.wasNew());
            assertNull(created.previousContent());
        }

        @Test void wasExisting_hasPreviousContent() {
            var existing = entry("old.txt", "old content", false);
            assertFalse(existing.wasNew());
            assertEquals("old content", existing.previousContent());
        }

        @Test void label_formatsCorrectly() {
            var e = entry("src/main/Foo.java", "x", false);
            assertEquals("talos.write_file → Foo.java", e.label());
        }
    }
}

