package dev.talos.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolMutationEffectsTest {
    @Test
    void observedPathsPreserveMeaningfulWhitespaceAndRemainDistinct() {
        ToolMutationEffects effects = ToolMutationEffects.applied(" result.txt")
                .plus(ToolMutationEffects.uncertain("result.txt"));
        assertEquals(List.of(" result.txt"), effects.appliedPaths());
        assertEquals(List.of("result.txt"), effects.uncertainPaths());
        assertEquals(List.of(" result.txt", "result.txt"), effects.affectedPaths());
    }
}
