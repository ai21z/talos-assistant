package dev.loqj.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link SourceIdentity}. */
class SourceIdentityTest {

    @Test
    void fullConstructor_allFieldsPreserved() {
        var id = new SourceIdentity("Foo.java", SourceType.CODE_FILE, SourceFormat.JAVA, MediaType.TEXTUAL);
        assertEquals("Foo.java", id.path());
        assertEquals(SourceType.CODE_FILE, id.type());
        assertEquals(SourceFormat.JAVA, id.format());
        assertEquals(MediaType.TEXTUAL, id.mediaType());
    }

    @Test
    void nullType_defaultsToUnknown() {
        var id = new SourceIdentity("x.dat", null, null, null);
        assertEquals(SourceType.UNKNOWN, id.type());
        assertEquals(SourceFormat.UNKNOWN, id.format());
        assertEquals(MediaType.UNKNOWN, id.mediaType());
    }

    @Test
    void nullPath_throws() {
        assertThrows(NullPointerException.class, () ->
                new SourceIdentity(null, SourceType.CODE_FILE, SourceFormat.JAVA, MediaType.TEXTUAL));
    }

    @Test
    void unclassified_allUnknown() {
        var id = SourceIdentity.unclassified("mystery.xyz");
        assertEquals("mystery.xyz", id.path());
        assertEquals(SourceType.UNKNOWN, id.type());
        assertEquals(SourceFormat.UNKNOWN, id.format());
        assertEquals(MediaType.UNKNOWN, id.mediaType());
    }

    @Test
    void isClassified_trueWhenAnyAxisKnown() {
        var id = new SourceIdentity("x", SourceType.CODE_FILE, SourceFormat.UNKNOWN, MediaType.UNKNOWN);
        assertTrue(id.isClassified());
    }

    @Test
    void isClassified_falseWhenAllUnknown() {
        var id = SourceIdentity.unclassified("x");
        assertFalse(id.isClassified());
    }

    @Test
    void recordEquality() {
        var a = new SourceIdentity("Foo.java", SourceType.CODE_FILE, SourceFormat.JAVA, MediaType.TEXTUAL);
        var b = new SourceIdentity("Foo.java", SourceType.CODE_FILE, SourceFormat.JAVA, MediaType.TEXTUAL);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void recordInequality() {
        var a = new SourceIdentity("Foo.java", SourceType.CODE_FILE, SourceFormat.JAVA, MediaType.TEXTUAL);
        var b = new SourceIdentity("Bar.py", SourceType.CODE_FILE, SourceFormat.PYTHON, MediaType.TEXTUAL);
        assertNotEquals(a, b);
    }
}

