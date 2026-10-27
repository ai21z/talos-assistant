package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jline.utils.AttributedString;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandTextLayoutTest {
    @ParameterizedTest
    @ValueSource(ints = {20, 40, 60, 80, 120})
    void entriesRetainContentAndBoundDisplayCells(int width) {
        String alias = "model-" + "a".repeat(48);
        String text = CommandTextLayout.entry(alias, "Readiness is not established. " + "界e\u0301".repeat(45), width);
        text.lines().forEach(line -> assertTrue(new AttributedString(line).columnLength() <= width, line));
        assertTrue(text.replaceAll("\\s", "").contains(alias));
        assertEquals(45, text.codePoints().filter(cp -> cp == '界').count());
        assertFalse(text.contains("..."));
    }

    @Test void widthsUseLiveNarrowTerminalButRemainDeterministicWithoutIt() {
        assertEquals(40, CommandTextLayout.width(() -> 40));
        assertEquals(120, CommandTextLayout.width(() -> 200));
        assertEquals(96, CommandTextLayout.width(() -> 0));
        assertEquals(96, CommandTextLayout.width(null));
    }

    @Test void wideEntriesHaveHangingDescriptions() {
        String entry = CommandTextLayout.entry("/models", "description ".repeat(15), 80);
        assertTrue(entry.startsWith("  /models"));
        entry.lines().skip(1).forEach(line -> assertTrue(line.startsWith(" ".repeat(28)), entry));
    }

    @Test void narrowEntriesStackWithoutTruncation() {
        String entry = CommandTextLayout.entry("/models --verbose", "Details of all local entries.", 40);
        assertEquals("  /models --verbose\n    Details of all local entries.", entry);
    }

    @Test void multilineValuesRemainIndentedAndLargeTokensAreNotCopiedRepeatedly() {
        assertEquals("  Name\n    first\n    second", CommandTextLayout.entry("Name", "first\nsecond", 40));
        assertEquals("  Name                      first\n                            second",
                CommandTextLayout.entry("Name", "first\nsecond", 80));
        String token = "a".repeat(100_000);
        String wrapped = CommandTextLayout.wrap(token, 40);
        assertEquals(token, wrapped.replace("\n", ""));
        wrapped.lines().forEach(line -> assertTrue(line.length() <= 40));
    }

    @Test void longWordsSurrogatesAndIndentedLinesRemainIntact() {
        String text = "    " + "\uD83D\uDE00".repeat(30);
        String result = CommandTextLayout.wrap(text, 20);
        assertEquals(text.replace(" ", ""), result.replaceAll("\\s", ""));
        result.lines().forEach(line -> assertTrue(line.startsWith("    ")));
        assertEquals("one\n\n  two\n", CommandTextLayout.wrap("one\r\n\r\n  two\r\n", 20));
    }

    @Test void tablesUseColumnsWhenTheyFitAndFieldsWhenTheyDoNot() {
        assertEquals("Name  State\none   idle\ntwo   ready", CommandTextLayout.table(
                List.of("Name", "State"), List.of(List.of("one", "idle"), List.of("two", "ready")), 80));
        String fallback = CommandTextLayout.table(List.of("Name", "State"),
                List.of(List.of("long-".repeat(20), "idle", "extra"), List.of("short")), 40);
        assertTrue(fallback.contains("Name:"));
        assertTrue(fallback.contains("Value 3:"));
        assertTrue(fallback.contains("extra"));
        fallback.lines().forEach(line -> assertTrue(line.length() <= 40, line));
        assertEquals("No rows.", CommandTextLayout.table(List.of("Name"), List.of(), 40));
    }
}
