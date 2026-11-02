package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runtime capability notes come from the tool name policy and the offered surface (MICA invariant I4). */
class CapabilityStatementNoteTest {
    private static final List<String> READ_ONLY = List.of("talos.read_file", "talos.list_dir", "talos.grep");
    private static final List<String> WITH_DELETE = List.of("talos.read_file", "talos.delete_path");

    @Test
    void anAliasOfAToolThatWasNotOfferedIsStatedAsNotAvailable() {
        assertEquals(List.of("[Runtime note: talos.delete_file is an alias of talos.delete_path, which is not"
                        + " available in this turn.]"),
                CapabilityStatementNote.notes("I cannot delete. Use talos.delete_file commands.", READ_ONLY, List.of()));
    }

    @Test
    void anOfferedMutatingToolIsStatedAsAvailableUnderTheApprovalPolicy() {
        assertEquals(List.of("[Runtime note: talos.delete_path is available in this turn and changes files only"
                        + " under the approval policy.]"),
                CapabilityStatementNote.notes("I cannot run talos.delete_path here.", WITH_DELETE, List.of()));
    }

    @Test
    void anUnknownTalosToolIdentifierIsStatedAsNotATalosTool() {
        assertEquals(List.of("[Runtime note: talos.purge_workspace is not a Talos tool.]"),
                CapabilityStatementNote.notes("Try talos.purge_workspace instead.", READ_ONLY, List.of()));
    }

    @Test
    void readOnlyToolsToolsThatRanFileNamesAndOtherNamespacesGainNoNote() {
        assertEquals(List.of(), CapabilityStatementNote.notes("I used talos.read_file on README.md.", READ_ONLY, List.of()));
        assertEquals(List.of(), CapabilityStatementNote.notes("talos.delete_path removed old.txt.",
                WITH_DELETE, List.of("talos.delete_path")));
        assertEquals(List.of(), CapabilityStatementNote.notes("Settings live in talos.json and talos.yaml.",
                READ_ONLY, List.of()));
        assertEquals(List.of(), CapabilityStatementNote.notes("The functions.delete_file call is not mine.",
                READ_ONLY, List.of()));
        // A registered backend alias in another namespace is not a statement about Talos tools.
        assertEquals(List.of(), CapabilityStatementNote.notes("Call tool_use.delete_file or tool_use:write_file.",
                READ_ONLY, List.of()));
    }

    @Test
    void eachNameIsNotedOnceAndTheAnswerTextIsKept() {
        String answer = "Use talos.delete_file or talos:delete_file.  ";
        String note = "[Runtime note: talos.delete_file is an alias of talos.delete_path, which is not available in"
                + " this turn.]";
        assertEquals(answer + "\n\n" + note, CapabilityStatementNote.annotate(answer, READ_ONLY, List.of()));
        assertEquals("Use talos.delete_file.\n\n" + note,
                CapabilityStatementNote.annotate("Use talos.delete_file.\n\n", READ_ONLY, List.of()));
        assertEquals("No tools named.", CapabilityStatementNote.annotate("No tools named.", READ_ONLY, List.of()));
    }

    @Test
    void onlyTheModelStatementIsScannedAndTheRuntimeTextAroundItIsKept() {
        String runtime = "Turn 5 ran talos.write_file on story.txt.\n\n";
        String model = "I cannot run talos.delete_file here.";
        assertEquals(runtime + model + "\n\n[Runtime note: talos.delete_file is an alias of talos.delete_path, which"
                        + " is not available in this turn.]",
                CapabilityStatementNote.annotate(runtime + model, model, READ_ONLY, List.of()));
        assertEquals(runtime, CapabilityStatementNote.annotate(runtime, "", READ_ONLY, List.of()));
    }
}
