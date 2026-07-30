package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactTargetSetTest {

    @Test
    void preservesNormalizedPathRoleSourceSpanTextConfidenceAndDerivation() {
        IntentDerivation derivation = new IntentDerivation(
                TargetSource.USER_REQUEST,
                "explicit mutation target",
                13,
                30,
                "styles\\main.css",
                0.91);
        ArtifactTargetSet targets = ArtifactTargetSet.of(
                new TargetRef(" styles\\main.css ", TargetRole.MUST_MUTATE, derivation));

        TargetRef stored = targets.find("styles/main.css").orElseThrow();

        assertEquals("styles/main.css", stored.path());
        assertEquals(TargetRole.MUST_MUTATE, stored.role());
        assertEquals(TargetSource.USER_REQUEST, stored.derivation().source());
        assertEquals("explicit mutation target", stored.derivation().reason());
        assertEquals(13, stored.derivation().startOffset());
        assertEquals(30, stored.derivation().endOffset());
        assertEquals("styles\\main.css", stored.derivation().sourceText());
        assertEquals(0.91, stored.derivation().confidence());
    }

    @Test
    void duplicateTargetsKeepStrongestRoleAndItsDerivation() {
        IntentDerivation mentioned = new IntentDerivation(
                TargetSource.USER_REQUEST, "mentioned", 0, 10, "scripts.js", 0.40);
        IntentDerivation verifier = new IntentDerivation(
                TargetSource.VERIFIER_RESULT, "verify only", 12, 22, "scripts.js", 0.80);
        IntentDerivation forbidden = new IntentDerivation(
                TargetSource.USER_REQUEST, "forbidden", 24, 34, "scripts.js", 0.95);

        ArtifactTargetSet targets = ArtifactTargetSet.of(
                new TargetRef("scripts.js", TargetRole.MENTIONED_ONLY, mentioned),
                new TargetRef("scripts.js", TargetRole.VERIFY_ONLY, verifier),
                new TargetRef("scripts.js", TargetRole.FORBIDDEN, forbidden),
                new TargetRef("scripts.js", TargetRole.MUST_MUTATE, mentioned));

        assertEquals(1, targets.targets().size());
        TargetRef stored = targets.find("scripts.js").orElseThrow();
        assertEquals(TargetRole.FORBIDDEN, stored.role());
        assertEquals(forbidden, stored.derivation());
    }

    @Test
    void explicitRequiredPostReadMutationRoleDominatesEachConstituentInEitherOrder() {
        IntentDerivation mutation = new IntentDerivation(
                TargetSource.USER_REQUEST,
                "explicit-mutation-target",
                20,
                36,
                "update styles.css",
                1.0);
        IntentDerivation source = new IntentDerivation(
                TargetSource.USER_REQUEST,
                "source-evidence-target",
                0,
                15,
                "read styles.css",
                1.0);
        TargetRole compositeRole =
                TargetRole.valueOf("MUST_MUTATE_AFTER_SOURCE_EVIDENCE");
        TargetRef composite = new TargetRef("styles.css", compositeRole, mutation);

        for (TargetRole constituent : List.of(
                TargetRole.MUST_MUTATE,
                TargetRole.OUTPUT_DESTINATION,
                TargetRole.MUST_READ,
                TargetRole.SOURCE_EVIDENCE,
                TargetRole.VERIFY_ONLY,
                TargetRole.MAY_MUTATE,
                TargetRole.MENTIONED_ONLY)) {
            TargetRef constituentRef = new TargetRef("styles.css", constituent, source);
            for (ArtifactTargetSet targets : List.of(
                    ArtifactTargetSet.of(composite, constituentRef),
                    ArtifactTargetSet.of(constituentRef, composite),
                    ArtifactTargetSet.of(composite).with(constituentRef),
                    ArtifactTargetSet.of(constituentRef).with(composite))) {
                TargetRef stored = targets.find("styles.css").orElseThrow();
                assertEquals(compositeRole, stored.role());
                assertEquals(mutation, stored.derivation());
            }
        }

        TargetRef forbidden = new TargetRef(
                "styles.css",
                TargetRole.FORBIDDEN,
                new IntentDerivation(
                        TargetSource.USER_REQUEST,
                        "forbidden-target",
                        40,
                        50,
                        "keep styles.css",
                        1.0));
        for (ArtifactTargetSet targets : List.of(
                ArtifactTargetSet.of(composite, forbidden),
                ArtifactTargetSet.of(forbidden, composite))) {
            assertEquals(forbidden, targets.find("styles.css").orElseThrow());
        }

        for (ArtifactTargetSet noHiddenJoin : List.of(
                ArtifactTargetSet.of(
                        new TargetRef("styles.css", TargetRole.SOURCE_EVIDENCE, source),
                        new TargetRef("styles.css", TargetRole.MUST_MUTATE, mutation)),
                ArtifactTargetSet.of(
                        new TargetRef("styles.css", TargetRole.MUST_MUTATE, mutation),
                        new TargetRef("styles.css", TargetRole.SOURCE_EVIDENCE, source)))) {
            assertEquals(
                    TargetRole.MUST_MUTATE,
                    noHiddenJoin.find("styles.css").orElseThrow().role());
        }
    }

    @Test
    void equalRoleAndConfidenceKeepTheFirstDerivationByDesign() {
        IntentDerivation first = new IntentDerivation(
                TargetSource.USER_REQUEST, "first", 0, 10, "styles.css", 0.8);
        IntentDerivation second = new IntentDerivation(
                TargetSource.MESSAGE_HISTORY, "second", 11, 21, "styles.css", 0.8);

        TargetRef stored = ArtifactTargetSet.of(
                        new TargetRef("styles.css", TargetRole.MUST_MUTATE, first),
                        new TargetRef("styles.css", TargetRole.MUST_MUTATE, second))
                .find("styles.css")
                .orElseThrow();

        assertEquals(first, stored.derivation());
    }

    @Test
    void filtersPathsByRole() {
        ArtifactTargetSet targets = ArtifactTargetSet.of(
                TargetRef.of("styles.css", TargetRole.MUST_MUTATE),
                TargetRef.of("index.html", TargetRole.VERIFY_ONLY),
                TargetRef.of("scripts.js", TargetRole.FORBIDDEN));

        assertEquals(Set.of("styles.css"), targets.pathsByRole(TargetRole.MUST_MUTATE));
        assertEquals(List.of(TargetRef.of("index.html", TargetRole.VERIFY_ONLY)),
                targets.targetsByRole(TargetRole.VERIFY_ONLY));
        assertEquals(Optional.empty(), targets.find("missing.js"));
    }

    @Test
    void rejectsBlankTargetsAndInvalidConfidence() {
        assertThrows(IllegalArgumentException.class,
                () -> TargetRef.of("   ", TargetRole.MENTIONED_ONLY));
        assertThrows(IllegalArgumentException.class,
                () -> new IntentDerivation(TargetSource.USER_REQUEST, "bad", 0, 3, "bad", 1.2));
    }

    @Test
    void targetListIsImmutable() {
        ArtifactTargetSet targets = ArtifactTargetSet.of(TargetRef.of("styles.css", TargetRole.MUST_MUTATE));

        assertThrows(UnsupportedOperationException.class,
                () -> targets.targets().add(TargetRef.of("late.js", TargetRole.MAY_MUTATE)));
        assertTrue(targets.find("styles.css").isPresent());
    }
}
