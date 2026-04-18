package dev.talos.runtime;

import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Point 4 — in-flight tool-result compaction helpers in
 * {@link ToolCallLoop}.
 *
 * <p>These tests exercise the pure static helpers directly so they don't
 * need a scripted LLM or full loop wiring. Integration behavior (the
 * compaction firing on iterations ≥ 3) is covered by the existing
 * {@link ToolCallLoopTest} end-to-end scenarios.
 */
class ToolCallLoopCompactionTest {

    @Nested
    class SummarizeToolResult {

        @Test
        void extractsToolNameFromHeader() {
            String body = "[tool_result: talos.read_file]\n<html>...22KB of content...</html>\n[/tool_result]";
            String summary = ToolCallLoop.summarizeToolResult(body);
            assertTrue(summary.contains("talos.read_file"), "summary must preserve tool name");
            assertTrue(summary.contains("result"), "summary must indicate it was a successful result");
            assertTrue(summary.contains(String.valueOf(body.length())), "summary must include original length");
        }

        @Test
        void flagsErrorResults() {
            String body = "[tool_result: talos.edit_file]\n[error] File not found\n[/tool_result]";
            String summary = ToolCallLoop.summarizeToolResult(body);
            assertTrue(summary.contains("error"), "error results must be flagged");
            assertTrue(summary.contains("talos.edit_file"));
        }

        @Test
        void handlesMalformedHeaderGracefully() {
            String summary = ToolCallLoop.summarizeToolResult("just some text with no header");
            assertTrue(summary.contains("[compacted:"));
            assertTrue(summary.contains("unknown"));
        }
    }

    @Nested
    class CompactOlderToolResultsInPlace {

        @Test
        void leavesFewMessagesUntouched() {
            var messages = new ArrayList<ChatMessage>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user("hi"),
                    ChatMessage.assistant("hello")
            ));
            var before = new ArrayList<>(messages);
            ToolCallLoop.compactOlderToolResultsInPlace(messages);
            assertEquals(before, messages, "no tool_result messages → no change");
        }

        @Test
        void keepsLastTwoToolResultsVerbatim() {
            String fullBody = "[tool_result: talos.read_file]\n" + "x".repeat(5000) + "\n[/tool_result]";
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("read stuff"));
            // 4 tool results; oldest 2 must be compacted, newest 2 preserved
            messages.add(ChatMessage.toolResult("c1", fullBody));
            messages.add(ChatMessage.toolResult("c2", fullBody));
            messages.add(ChatMessage.toolResult("c3", fullBody));
            messages.add(ChatMessage.toolResult("c4", fullBody));

            ToolCallLoop.compactOlderToolResultsInPlace(messages);

            // Find tool_result messages in order
            List<ChatMessage> toolMsgs = new ArrayList<>();
            for (ChatMessage m : messages) if ("tool".equals(m.role())) toolMsgs.add(m);

            assertEquals(4, toolMsgs.size(), "count of tool_result messages must be preserved");
            assertTrue(toolMsgs.get(0).content().startsWith("[compacted:"),
                    "oldest tool_result must be compacted");
            assertTrue(toolMsgs.get(1).content().startsWith("[compacted:"),
                    "2nd-oldest tool_result must be compacted");
            assertEquals(fullBody, toolMsgs.get(2).content(),
                    "2nd-newest tool_result must be verbatim");
            assertEquals(fullBody, toolMsgs.get(3).content(),
                    "newest tool_result must be verbatim");
        }

        @Test
        void preservesToolCallIdsOnCompaction() {
            String body = "[tool_result: talos.list_dir]\n" + "y".repeat(500) + "\n[/tool_result]";
            var messages = new ArrayList<ChatMessage>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user("do stuff"),
                    ChatMessage.toolResult("call-A", body),
                    ChatMessage.toolResult("call-B", body),
                    ChatMessage.toolResult("call-C", body)
            ));
            ToolCallLoop.compactOlderToolResultsInPlace(messages);
            ChatMessage oldest = messages.get(2);
            assertEquals("tool", oldest.role());
            assertEquals("call-A", oldest.toolCallId(), "toolCallId must be preserved on compaction");
            assertTrue(oldest.content().startsWith("[compacted:"));
        }

        @Test
        void isIdempotent() {
            String body = "[tool_result: talos.read_file]\n" + "z".repeat(500) + "\n[/tool_result]";
            var messages = new ArrayList<ChatMessage>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user("go"),
                    ChatMessage.toolResult("c1", body),
                    ChatMessage.toolResult("c2", body),
                    ChatMessage.toolResult("c3", body)
            ));
            ToolCallLoop.compactOlderToolResultsInPlace(messages);
            String afterFirst = messages.get(2).content();
            ToolCallLoop.compactOlderToolResultsInPlace(messages);
            String afterSecond = messages.get(2).content();
            assertEquals(afterFirst, afterSecond,
                    "running compaction twice must not re-compact already-compacted messages");
        }
    }

    @Nested
    class LatestUserRequestIn {

        @Test
        void skipsToolRoleMessagesOnNativePath() {
            var messages = new ArrayList<ChatMessage>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user("edit index.html"),
                    ChatMessage.assistant("reading…"),
                    ChatMessage.toolResult("c1", "<html>"),
                    ChatMessage.toolResult("c2", "index.html")
            ));
            String req = ToolCallLoop.latestUserRequestIn(messages);
            assertEquals("edit index.html", req);
        }

        @Test
        void returnsNullOnEmptyOrMissingUser() {
            assertNull(ToolCallLoop.latestUserRequestIn(null));
            assertNull(ToolCallLoop.latestUserRequestIn(List.of()));
            assertNull(ToolCallLoop.latestUserRequestIn(List.of(ChatMessage.system("only sys"))));
        }
    }
}

