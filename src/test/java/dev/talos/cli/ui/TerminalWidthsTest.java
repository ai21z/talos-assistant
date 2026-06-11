package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the T771 width-resolution rule: terminal → COLUMNS → default. */
class TerminalWidthsTest {

    @Test
    void liveTerminalWidthIsClampedToSixtyAndOneTwenty() {
        assertEquals(120, TerminalWidths.resolve(() -> 200, Map.of(), 96));
        assertEquals(60, TerminalWidths.resolve(() -> 30, Map.of(), 96));
        assertEquals(100, TerminalWidths.resolve(() -> 100, Map.of(), 96));
        assertEquals(60, TerminalWidths.resolve(() -> 60, Map.of(), 96));
        assertEquals(120, TerminalWidths.resolve(() -> 120, Map.of(), 96));
    }

    @Test
    void nonPositiveTerminalWidthFallsThroughToColumns() {
        assertEquals(110, TerminalWidths.resolve(() -> 0, Map.of("COLUMNS", "110"), 96));
        assertEquals(110, TerminalWidths.resolve(() -> -1, Map.of("COLUMNS", "110"), 96));
    }

    @Test
    void throwingTerminalWidthFallsThroughToColumns() {
        IntSupplierThatThrows broken = new IntSupplierThatThrows();
        assertEquals(110, TerminalWidths.resolve(broken, Map.of("COLUMNS", "110"), 96));
    }

    @Test
    void columnsIsClampedLikeALiveWidth() {
        // Deliberate behavior change from the pre-T771 banner rule, which
        // accepted any COLUMNS >= 40 unclamped (roadmap clamp rule 60-120).
        assertEquals(120, TerminalWidths.resolve(null, Map.of("COLUMNS", "200"), 96));
        assertEquals(60, TerminalWidths.resolve(null, Map.of("COLUMNS", "45"), 96));
    }

    @Test
    void noTerminalAndNoColumnsReturnsSurfaceDefaultUnclamped() {
        // Redirected/scripted byte-identity: surface defaults pass through
        // even when outside the clamp range (answer pane default is 96,
        // but e.g. 40 must also survive untouched).
        assertEquals(96, TerminalWidths.resolve(null, Map.of(), 96));
        assertEquals(80, TerminalWidths.resolve(null, Map.of(), 80));
        assertEquals(40, TerminalWidths.resolve(null, Map.of(), 40));
        assertEquals(130, TerminalWidths.resolve(null, Map.of(), 130));
    }

    @Test
    void malformedColumnsFallsBackToSurfaceDefault() {
        assertEquals(96, TerminalWidths.resolve(null, Map.of("COLUMNS", "abc"), 96));
        assertEquals(96, TerminalWidths.resolve(null, Map.of("COLUMNS", "  "), 96));
        assertEquals(96, TerminalWidths.resolve(null, null, 96));
    }

    private static final class IntSupplierThatThrows implements java.util.function.IntSupplier {
        @Override public int getAsInt() {
            throw new IllegalStateException("terminal gone");
        }
    }
}
