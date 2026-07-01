package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallLoopCompactionBehaviorCharacterizationTest {

    @Test
    void olderToolResultsCompactToShapeOnlyStubsWhileRecentResultsStayVerbatim() {
        String readBody = successBody("talos.read_file", "ALPHA line one\nALPHA line two");
        String writeBody = successBody("talos.write_file", "Wrote src/App.java");
        String editBody = errorBody("talos.edit_file", "old_string was not found");
        String recentListBody = successBody("talos.list_dir", "src\nREADME.md");
        String recentReadBody = successBody("talos.read_file", "OMEGA final context");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("system"),
                ChatMessage.user("inspect the workspace"),
                ChatMessage.toolResult("read-1", readBody),
                ChatMessage.toolResult("write-2", writeBody),
                ChatMessage.toolResult("edit-3", editBody),
                ChatMessage.toolResult("list-4", recentListBody),
                ChatMessage.toolResult("read-5", recentReadBody)
        ));

        ToolCallSupport.compactOlderToolResultsInPlace(messages);

        ChatMessage read = messages.get(2);
        ChatMessage write = messages.get(3);
        ChatMessage edit = messages.get(4);
        ChatMessage recentList = messages.get(5);
        ChatMessage recentRead = messages.get(6);

        assertAll(
                () -> assertEquals("read-1", read.toolCallId()),
                () -> assertTrue(read.content().startsWith(
                        "[compacted: talos.read_file result, " + readBody.length() + " chars")),
                () -> assertTrue(read.content().contains("full output elided")),
                () -> assertFalse(read.content().contains("ALPHA line one")),
                () -> assertEquals("write-2", write.toolCallId()),
                () -> assertTrue(write.content().startsWith(
                        "[compacted: talos.write_file result, " + writeBody.length() + " chars")),
                () -> assertEquals("edit-3", edit.toolCallId()),
                () -> assertTrue(edit.content().startsWith(
                        "[compacted: talos.edit_file error, " + editBody.length() + " chars")),
                () -> assertEquals(recentListBody, recentList.content()),
                () -> assertEquals("list-4", recentList.toolCallId()),
                () -> assertEquals(recentReadBody, recentRead.content()),
                () -> assertEquals("read-5", recentRead.toolCallId())
        );
    }

    @Test
    void alreadyCompactedMessagesAreSkippedWhileOlderUncompactedResultsStillCompact() {
        String preexistingStub = "[compacted: talos.read_file result, 900 chars - already compacted]";
        String olderBody = successBody("talos.read_file", "BETA detail should be elided");
        String recentOne = successBody("talos.list_dir", "src");
        String recentTwo = successBody("talos.read_file", "GAMMA recent result");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.user("continue"),
                ChatMessage.toolResult("old-stub", preexistingStub),
                ChatMessage.toolResult("older-read", olderBody),
                ChatMessage.toolResult("recent-list", recentOne),
                ChatMessage.toolResult("recent-read", recentTwo)
        ));

        ToolCallSupport.compactOlderToolResultsInPlace(messages);

        assertAll(
                () -> assertEquals(preexistingStub, messages.get(1).content()),
                () -> assertTrue(messages.get(2).content().startsWith(
                        "[compacted: talos.read_file result, " + olderBody.length() + " chars")),
                () -> assertFalse(messages.get(2).content().contains("BETA detail")),
                () -> assertEquals(recentOne, messages.get(3).content()),
                () -> assertEquals(recentTwo, messages.get(4).content())
        );
    }

    @Test
    void latestUserRequestSkipsSyntheticToolResultAndCompactedMessages() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("system"),
                ChatMessage.user("real request"),
                ChatMessage.assistant("I will inspect."),
                ChatMessage.user("[tool_result: talos.read_file]\nsynthetic\n[/tool_result]"),
                ChatMessage.user("[compacted: talos.read_file result, 2048 chars - elided]")
        );

        assertEquals("real request", ToolCallSupport.latestUserRequestIn(messages));
    }

    @Test
    void readFileBodiesRemainAvailableWhenPromptMessagesCompact() {
        String oldRead = successBody("talos.read_file", "PRIVATE_STATE=ALPHA\nline two");
        String middleRead = successBody("talos.read_file", "BETA retained in state");
        String recentOne = successBody("talos.list_dir", "src");
        String recentTwo = successBody("talos.read_file", "OMEGA recent");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.user("read several files"),
                ChatMessage.toolResult("read-old", oldRead),
                ChatMessage.toolResult("read-middle", middleRead),
                ChatMessage.toolResult("list-recent", recentOne),
                ChatMessage.toolResult("read-recent", recentTwo)
        ));
        LoopState state = new LoopState("", List.of(), messages, null, null, null, 8, 0);
        state.readFileBodiesThisTurn.put("src/Old.java", "PRIVATE_STATE=ALPHA\nline two");
        state.readFileBodiesThisTurn.put("src/Middle.java", "BETA retained in state");

        ToolCallSupport.compactOlderToolResultsInPlace(state.messages);

        assertAll(
                () -> assertEquals("PRIVATE_STATE=ALPHA\nline two",
                        state.readFileBodiesThisTurn.get("src/Old.java")),
                () -> assertEquals("BETA retained in state",
                        state.readFileBodiesThisTurn.get("src/Middle.java")),
                () -> assertTrue(state.messages.get(1).content().startsWith(
                        "[compacted: talos.read_file result, " + oldRead.length() + " chars")),
                () -> assertFalse(state.messages.get(1).content().contains("PRIVATE_STATE=ALPHA")),
                () -> assertEquals(recentOne, state.messages.get(3).content()),
                () -> assertEquals(recentTwo, state.messages.get(4).content())
        );
    }

    private static String successBody(String toolName, String output) {
        return "[tool_result: " + toolName + "]\n" + output + "\n[/tool_result]";
    }

    private static String errorBody(String toolName, String output) {
        return "[tool_result: " + toolName + "]\n[error] " + output + "\n[/tool_result]";
    }
}
