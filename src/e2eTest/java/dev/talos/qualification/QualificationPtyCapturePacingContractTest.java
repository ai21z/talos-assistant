package dev.talos.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QualificationPtyCapturePacingContractTest {
    private static final Path SOURCE = Path.of(
            "src/e2eTest/java/dev/talos/qualification/QualificationPtyCaptureMain.java");

    @Test
    void startupIdleIsSettledBeforeScenarioInput() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String runMethod = between(source, "private void run()", "private String awaitApprovalOrTerminal");

        assertTrue(runMethod.contains("waitForSettledIdlePrompt(0, STARTUP_WAIT);"), runMethod);
        assertFalse(runMethod.contains("waitForIdlePrompt(0, STARTUP_WAIT);"), runMethod);
    }

    @Test
    void rawIdleWaitIsCentralizedBehindSettledBridge() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertEquals(2, occurrences(source, "waitForIdlePrompt("), source);
        assertTrue(source.contains("private void waitForSettledIdlePrompt("), source);
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex, source);
        return source.substring(startIndex, endIndex);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }
}
