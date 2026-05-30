package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetRoleTest {

    @Test
    void exposesInitialRolesInDeterministicPrecedenceOrder() {
        assertEquals(List.of(
                TargetRole.FORBIDDEN,
                TargetRole.MUST_MUTATE,
                TargetRole.OUTPUT_DESTINATION,
                TargetRole.MUST_READ,
                TargetRole.SOURCE_EVIDENCE,
                TargetRole.VERIFY_ONLY,
                TargetRole.MAY_MUTATE,
                TargetRole.MENTIONED_ONLY
        ), TargetRole.byPrecedence());
    }

    @Test
    void strongestSelectsHigherPrecedenceRole() {
        assertEquals(TargetRole.FORBIDDEN,
                TargetRole.strongest(TargetRole.MUST_MUTATE, TargetRole.FORBIDDEN));
        assertEquals(TargetRole.OUTPUT_DESTINATION,
                TargetRole.strongest(TargetRole.VERIFY_ONLY, TargetRole.OUTPUT_DESTINATION));
        assertEquals(TargetRole.MUST_READ,
                TargetRole.strongest(TargetRole.SOURCE_EVIDENCE, TargetRole.MUST_READ));
        assertEquals(TargetRole.MAY_MUTATE,
                TargetRole.strongest(TargetRole.MENTIONED_ONLY, TargetRole.MAY_MUTATE));
    }
}
