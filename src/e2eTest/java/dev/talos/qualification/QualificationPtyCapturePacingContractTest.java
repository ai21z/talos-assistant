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

        assertTrue(runMethod.contains("waitForStartupIdlePrompt();"), runMethod);
        assertFalse(runMethod.contains("waitForIdlePrompt(0, STARTUP_WAIT);"), runMethod);
    }

    @Test
    void startupWakeupIsRecordedAndBlank() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String startupMethod = between(
                source, "private void waitForStartupIdlePrompt()", "private int waitForIdlePrompt");

        assertTrue(startupMethod.contains("addInput(\"startup-wakeup\");"), startupMethod);
        assertTrue(startupMethod.contains("writeLine(\"\");"), startupMethod);
        assertFalse(startupMethod.contains("writeLine(prompt)"), startupMethod);
        assertFalse(startupMethod.contains("addInput(\"scenario-prompt\")"), startupMethod);
    }

    @Test
    void rawIdleWaitIsCentralizedBehindSettledBridge() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertEquals(2, occurrences(source, "waitForIdlePrompt("), source);
        assertTrue(source.contains("private void waitForSettledIdlePrompt("), source);
    }

    @Test
    void installedProcessReceivesFrozenQualificationDeclarationIdentity() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String startMethod = between(source, "private void startTalos()", "private void interruptDuringGeneration");

        assertTrue(startMethod.contains("talos.qualification.root"), startMethod);
        assertTrue(startMethod.contains("talos.qualification.calibration"), startMethod);
        assertTrue(startMethod.contains("talos.qualification.calibrationSha256"), startMethod);
        assertTrue(startMethod.contains("campaign.calibrationRulesSha256()"), startMethod);
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
