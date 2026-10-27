package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetRoleTest {

    @Test
    void exposesInitialRolesInDeterministicPrecedenceOrder() {
        assertEquals(List.of(
                "FORBIDDEN",
                "MUST_MUTATE_AFTER_SOURCE_EVIDENCE",
                "MUST_MUTATE",
                "OUTPUT_DESTINATION",
                "MUST_READ",
                "SOURCE_EVIDENCE",
                "VERIFY_ONLY",
                "MAY_MUTATE",
                "MENTIONED_ONLY"
        ), TargetRole.byPrecedence().stream().map(TargetRole::name).toList());
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

    @Test
    void requiredPostReadMutationRoleDominatesEachConstituentWithoutHiddenSynthesis() {
        TargetRole composite = TargetRole.valueOf("MUST_MUTATE_AFTER_SOURCE_EVIDENCE");

        assertEquals(composite, TargetRole.strongest(composite, TargetRole.MUST_MUTATE));
        assertEquals(composite, TargetRole.strongest(TargetRole.MUST_MUTATE, composite));
        assertEquals(composite, TargetRole.strongest(TargetRole.MUST_READ, composite));
        assertEquals(composite, TargetRole.strongest(composite, TargetRole.MUST_READ));
        assertEquals(composite, TargetRole.strongest(composite, TargetRole.SOURCE_EVIDENCE));
        assertEquals(composite, TargetRole.strongest(TargetRole.SOURCE_EVIDENCE, composite));
        assertEquals(TargetRole.FORBIDDEN, TargetRole.strongest(composite, TargetRole.FORBIDDEN));
        assertEquals(TargetRole.FORBIDDEN, TargetRole.strongest(TargetRole.FORBIDDEN, composite));
        assertEquals(
                TargetRole.MUST_MUTATE,
                TargetRole.strongest(TargetRole.MUST_MUTATE, TargetRole.SOURCE_EVIDENCE));
    }

    @Test
    void strongestIsAnIdempotentCommutativeAssociativeTotalOrder() {
        for (TargetRole first : TargetRole.values()) {
            assertEquals(first, TargetRole.strongest(first, first));
            for (TargetRole second : TargetRole.values()) {
                TargetRole strongest = TargetRole.strongest(first, second);
                assertEquals(strongest, TargetRole.strongest(second, first));
                assertTrue(
                        strongest == first || strongest == second,
                        () -> first + " and " + second + " must not synthesize another role");
                for (TargetRole third : TargetRole.values()) {
                    assertEquals(
                            TargetRole.strongest(
                                    first,
                                    TargetRole.strongest(second, third)),
                            TargetRole.strongest(
                                    TargetRole.strongest(first, second),
                                    third),
                            () -> "non-associative precedence for "
                                    + first + ", " + second + ", " + third);
                }
            }
        }
    }

    @Test
    void declaredPrecedenceListMatchesNumericPrecedenceExactly() {
        List<TargetRole> roles = TargetRole.byPrecedence();

        assertEquals(List.of(TargetRole.values()), roles);
        for (int i = 1; i < roles.size(); i++) {
            assertTrue(
                    roles.get(i - 1).precedence() > roles.get(i).precedence(),
                    "precedence list is not strictly descending at " + i);
        }
    }
}
