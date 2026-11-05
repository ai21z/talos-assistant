package dev.talos.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.util.Hash;
import dev.talos.tools.impl.ExecutedEditDetail;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The typed producer preimage of P18 (D12 option B) and its carrier on ToolResult. */
class MutationPreimageTest {
    private static final String CANARY = "p18-canary-7f3a";
    private static final String TEXT = "[SERVER]\r\nport=8080\r\n# " + CANARY + "\r\n";
    private static final String SHA = Hash.sha256Hex(TEXT.getBytes(StandardCharsets.UTF_8));

    @Test
    void anExistingTargetKeepsItsTextAndANormalizedPath() {
        MutationPreimage preimage = MutationPreimage.existing(".\\conf\\config.ini", SHA.toUpperCase(), TEXT);

        assertTrue(preimage.available());
        assertTrue(preimage.existed());
        assertEquals("conf/config.ini", preimage.target());
        assertEquals(SHA, preimage.sha256());
        assertEquals(TEXT, preimage.text());
    }

    @Test
    void anAbsentTargetHasEmptyTextAndNoHash() {
        MutationPreimage preimage = MutationPreimage.absent("notes.md");

        assertTrue(preimage.available());
        assertFalse(preimage.existed());
        assertEquals("", preimage.text());
        assertEquals("", preimage.sha256());
    }

    @Test
    void invalidInputGivesNone() {
        assertSame(MutationPreimage.none(), MutationPreimage.existing("config.ini", "not-a-hash", TEXT));
        assertSame(MutationPreimage.none(), MutationPreimage.existing(" ", SHA, TEXT));
        assertSame(MutationPreimage.none(), MutationPreimage.existing(null, SHA, TEXT));
        assertSame(MutationPreimage.none(), MutationPreimage.existing("config.ini", SHA, null));
        assertSame(MutationPreimage.none(), MutationPreimage.existing(
                "config.ini", SHA, "x".repeat(MutationPreimage.MAX_BYTES + 1)));
        assertSame(MutationPreimage.none(), MutationPreimage.absent(""));
        assertFalse(MutationPreimage.none().available());
    }

    @Test
    void itBelongsOnlyToExactlyOneAppliedPathEqualToItsTarget() {
        MutationPreimage preimage = MutationPreimage.existing("config.ini", SHA, TEXT);

        assertTrue(preimage.associatedWith(ToolMutationEffects.applied("config.ini")));
        assertTrue(preimage.associatedWith(ToolMutationEffects.applied("./config.ini")));
        assertFalse(preimage.associatedWith(ToolMutationEffects.applied("other.ini")));
        assertFalse(preimage.associatedWith(ToolMutationEffects.uncertain("config.ini")));
        assertFalse(preimage.associatedWith(
                new ToolMutationEffects(List.of("config.ini", "conf"), List.of())));
        assertFalse(preimage.associatedWith(
                new ToolMutationEffects(List.of("config.ini"), List.of("config.ini.tmp"))));
        assertFalse(preimage.associatedWith(ToolMutationEffects.none()));
        assertFalse(MutationPreimage.none().associatedWith(ToolMutationEffects.applied("config.ini")));
    }

    @Test
    void equalityUsesTheTargetAndTheHashAndNeverPrintsContent() {
        MutationPreimage preimage = MutationPreimage.existing("config.ini", SHA, TEXT);

        assertEquals(preimage, MutationPreimage.existing("config.ini", SHA, TEXT));
        assertEquals(preimage.hashCode(), MutationPreimage.existing("config.ini", SHA, TEXT).hashCode());
        assertNotEquals(preimage, MutationPreimage.absent("config.ini"));
        assertEquals("MutationPreimage[target=config.ini, existed=true]", preimage.toString());
        assertFalse(preimage.toString().contains(SHA));
        assertEquals("MutationPreimage[none]", MutationPreimage.none().toString());
    }

    @Test
    void theCarrierKeepsAnAssociatedPreimageAndDropsAnyOther() {
        MutationPreimage preimage = MutationPreimage.existing("config.ini", SHA, TEXT);
        ToolResult applied = ToolResult.ok("Updated config.ini").withMutationEffects(ToolMutationEffects.applied("config.ini"));

        ToolResult kept = applied.withMutationPreimage(preimage);
        assertEquals(preimage, kept.mutationPreimage());
        assertEquals(preimage, kept.withExecutedEditDetail(ExecutedEditDetail.none()).mutationPreimage());
        assertEquals(preimage, kept.withMutationEffects(ToolMutationEffects.applied("config.ini")).mutationPreimage());
        assertSame(MutationPreimage.none(),
                kept.withMutationEffects(ToolMutationEffects.applied("other.ini")).mutationPreimage());
        assertSame(MutationPreimage.none(), ToolResult.ok("Updated").withMutationPreimage(preimage).mutationPreimage());
        assertSame(MutationPreimage.none(), new ToolResult(true, "out", null, null, ToolContentMetadata.normal(),
                ToolMutationEffects.applied("config.ini"), ExecutedEditDetail.none()).mutationPreimage());
    }

    @Test
    void noSerializationOrStringFormCarriesThePriorText() throws Exception {
        MutationPreimage preimage = MutationPreimage.existing("config.ini", SHA, TEXT);
        ToolResult result = ToolResult.ok("Updated config.ini")
                .withMutationEffects(ToolMutationEffects.applied("config.ini"))
                .withMutationPreimage(preimage);
        ObjectMapper mapper = new ObjectMapper();

        assertEquals("{\"available\":true}", mapper.writeValueAsString(preimage));
        String json = mapper.writeValueAsString(result);
        assertFalse(json.contains(CANARY), json);
        assertFalse(json.contains(SHA), json);
        assertFalse(result.toString().contains(CANARY), result.toString());
    }
}
