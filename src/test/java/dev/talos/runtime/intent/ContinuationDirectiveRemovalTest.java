package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Removal clauses follow the same act rules as directives, and are never continuation directives (P3). */
class ContinuationDirectiveRemovalTest {

    @Test
    void removalRequestsYieldTheirRemovalClause() {
        assertEquals(List.of("remove the page"), ContinuationDirective.removalClauses("please remove the page"));
        assertEquals(List.of("delete this page now please"), ContinuationDirective.removalClauses(
                "ok can you delete this page now please? I want this workspace clean"));
        assertEquals(List.of("delete all the files in this workspace"), ContinuationDirective.removalClauses(
                "Can you completely DELETE all the files in this workspace?"));
        assertEquals(List.of("get rid of index.html"), ContinuationDirective.removalClauses("get rid of index.html"));
        assertEquals(List.of("erase index.html"), ContinuationDirective.removalClauses("Erase index.html."));
        assertEquals(List.of("rm notes.txt"), ContinuationDirective.removalClauses("rm notes.txt"));
        assertEquals(List.of("delete it"), ContinuationDirective.removalClauses("delete it?"));
    }

    @Test
    void questionsNegationsAndMentionsAreNotRemovals() {
        for (String message : List.of(
                "why did you delete it?",
                "did you delete it?",
                "how do I delete a file?",
                "don't delete it",
                "do not remove the footer",
                "the remove button is broken",
                "I deleted index.html myself",
                "I want you to delete the docx file and make the same thing as a pdf")) {
            assertTrue(ContinuationDirective.removalClauses(message).isEmpty(), message);
        }
    }

    @Test
    void aRemovalIsNeverAContinuationDirective() {
        assertFalse(ContinuationDirective.present("delete it"));
        assertFalse(ContinuationDirective.present("please remove the page"));
        assertTrue(ContinuationDirective.present("fix the header and delete it"));
    }
}
