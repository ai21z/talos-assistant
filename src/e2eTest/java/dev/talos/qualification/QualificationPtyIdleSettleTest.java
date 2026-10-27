package dev.talos.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QualificationPtyIdleSettleTest {
    @Test
    void waitsForIdleBeforeApplyingOneFixedSettlePause() throws Exception {
        List<String> events = new ArrayList<>();

        QualificationPtyIdleSettle.awaitAndPause(
                () -> events.add("idle"),
                duration -> events.add("pause:" + duration.toMillis()));

        assertEquals(List.of("idle", "pause:100"), events);
    }

    @Test
    void failedIdleWaitDoesNotPauseOrAdvance() {
        List<String> events = new ArrayList<>();

        assertThrows(IllegalStateException.class, () ->
                QualificationPtyIdleSettle.awaitAndPause(
                        () -> {
                            events.add("idle-failed");
                            throw new IllegalStateException("no idle prompt");
                        },
                        duration -> events.add("pause:" + duration.toMillis())));

        assertEquals(List.of("idle-failed"), events);
    }

    @Test
    void settleDurationIsBoundedAndExplicit() {
        assertEquals(Duration.ofMillis(100), QualificationPtyIdleSettle.SETTLE_DELAY);
    }
}
