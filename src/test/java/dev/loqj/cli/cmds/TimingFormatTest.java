package dev.loqj.cli.cmds;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for elapsed time formatting in RagAskCmd.
 */
public class TimingFormatTest {

    @Test
    public void testMillisecondsFormat() {
        // < 1 second → XYZms
        assertEquals("500ms", formatTime(500_000_000L));
        assertEquals("123ms", formatTime(123_456_789L));
        assertEquals("999ms", formatTime(999_000_000L));
    }

    @Test
    public void testSecondsFormat() {
        // 1-59s → X.Ys
        assertEquals("1.0s", formatTime(1_000_000_000L));
        assertEquals("5.5s", formatTime(5_500_000_000L));
        assertEquals("30.2s", formatTime(30_234_567_890L));
        assertEquals("59.9s", formatTime(59_900_000_000L));
    }

    @Test
    public void testMinutesFormat() {
        // >= 60s → M:SS
        assertEquals("1:00", formatTime(60_000_000_000L));
        assertEquals("1:30", formatTime(90_000_000_000L));
        assertEquals("2:45", formatTime(165_000_000_000L));
        assertEquals("10:05", formatTime(605_000_000_000L));
    }

    @Test
    public void testBoundaryConditions() {
        // Just under 1 second
        assertEquals("999ms", formatTime(999_999_999L));

        // Exactly 1 second
        assertEquals("1.0s", formatTime(1_000_000_000L));

        // Just under 60 seconds (but rounds to 59.9s)
        String result = formatTime(59_999_999_999L);
        assertTrue(result.equals("59.9s") || result.equals("60.0s"),
            "Expected 59.9s or 60.0s due to rounding, got: " + result);

        // Exactly 60 seconds
        assertEquals("1:00", formatTime(60_000_000_000L));
    }

    @Test
    public void testZeroAndVerySmall() {
        assertEquals("0ms", formatTime(0L));
        assertEquals("0ms", formatTime(500_000L)); // 0.5ms rounds to 0
    }

    // Helper to invoke private formatElapsedTime method via reflection
    private String formatTime(long nanos) {
        try {
            Class<?> ragAskCmdClass = Class.forName("dev.loqj.cli.cmds.RagAskCmd");
            Method method = ragAskCmdClass.getDeclaredMethod("formatElapsedTime", long.class);
            method.setAccessible(true);
            return (String) method.invoke(null, nanos);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke formatElapsedTime", e);
        }
    }
}
