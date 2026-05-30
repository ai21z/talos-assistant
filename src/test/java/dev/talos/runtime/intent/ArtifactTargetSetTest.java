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
