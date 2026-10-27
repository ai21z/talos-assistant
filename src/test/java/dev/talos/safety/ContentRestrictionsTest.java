package dev.talos.safety;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentRestrictionsTest {
    @Test void allIndependentDestinationIntersectionsAreMonotonic() {
        for (int left = 0; left < 16; left++) {
            for (int right = 0; right < 16; right++) {
                var a = policy(left);
                var b = policy(right);
                var joined = a.intersect(b);
                assertEquals(policy(left & right), joined);
                assertEquals(b.intersect(a), joined);
                assertEquals(joined, joined.intersect(a).intersect(b));
                assertEquals(a, a.intersect(ContentRestrictions.UNRESTRICTED));
                assertEquals(joined.historyAllowed() ? "ordinary prose" : ContentRestrictions.WITHHELD,
                        joined.forHistory("ordinary prose"));
            }
        }
        assertEquals("", ContentRestrictions.UNRESTRICTED.forHistory(null));
        assertThrows(NullPointerException.class, () -> ContentRestrictions.UNRESTRICTED.intersect(null));
    }

    private static ContentRestrictions policy(int bits) {
        return new ContentRestrictions((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0);
    }
}
