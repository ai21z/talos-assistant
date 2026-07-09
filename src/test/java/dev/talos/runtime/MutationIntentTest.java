package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationIntentTest {

    private static final String RETROCATS_AUDIT_PROMPT =
            "Create a complete modern dark synthwave static website for a band called Retrocats. "
                    + "Use exactly index.html, style.css, and script.js as the local files. "
                    + "Use Tailwind correctly only through the official browser CDN or through generated CSS. "
                    + "Do not create a local tailwind.min.css file, no broken tailwind.min.css, "
                    + "no placeholder Tailwind file, and no unprocessed @tailwind directives. "
                    + "The site must preserve these required visible facts: Retrocats, Costanza, Merri, "
                    + "formed in 2024, analog synth sounds, electric guitars, 80s rock and metal blended "
                    + "with synthwave, Cassette Love, Nine-zero vhs, Future tense, Past Perfect Vibes, "
                    + "Dust to Dust, Gold for the old, Life span, Rome 15 July 2026, Barcelona 18 July 2026, "
                    + "Berlin 22 July 2026. Make it visually strong: dark base, pink/orange synthwave "
                    + "accents, band hero, albums, top songs, concerts, and a small interactive JavaScript enhancement.";

    private static final String T61_B_RETRY_PROMPT =
            "This is a retry after the denied attempt. Edit README.md now using talos.write_file. "
                    + "The complete file must contain exactly two lines: first line T61-B exact README; "
                    + "second line Line two; no other characters.";

    private static final String SCN13_FIX_PROMPT =
            "There is a bug in calc.py: multiply returns the wrong result. "
                    + "Fix multiply so it returns the product of a and b. "
                    + "Change only what is necessary.";

    @Test
    void overwriteRewriteReplaceAndNaturalCreationPhrasingAreExplicitMutationIntent() {
        for (String input : java.util.List.of(
                "Overwrite index.html with a corrected complete version.",
                "Overwrite these three files to make a working BMI calculator: index.html, styles.css, scripts.js.",
                "Replace index.html with a corrected complete version.",
                "Rewrite scripts.js so the button works.",
                "Can you make me a simple BMI calculator webpage here?",
                "I am not technical, I just want a page I can open and use. Can you make it?",
                "I want a modern synthwave band web page with dark colors, pink and orange accents, "
                        + "album sections, top songs, and upcoming concerts. Can you create that web page?",
                "Can you fix the files in this folder for me?",
                "Great! now can you create that site?",
                "Move public.txt to archive/public.txt.",
                "Copy docs/plan.md to docs/archive/plan.md.",
                "Rename old.txt to new.txt.",
                "Mkdir docs/reports.",
                "make me a folder called ideas",
                "make a folder called docs",
                "create a directory named reports")) {
            assertTrue(MutationIntent.looksExplicitMutationRequest(input), input);
        }
    }

    @Test
    void repairIsExplicitMutationIntent() {
        assertTrue(MutationIntent.looksExplicitMutationRequest("Repair this website."));
        assertTrue(MutationIntent.looksExplicitMutationRequest("Can you repair index.html?"));
        assertTrue(MutationIntent.looksExplicitMutationRequest("Please repair the broken app."));
    }

    @Test
    void preambleBeforeExplicitFileEditIsMutationIntent() {
        assertTrue(MutationIntent.looksExplicitMutationRequest(T61_B_RETRY_PROMPT));
        assertTrue(MutationIntent.classificationReason(T61_B_RETRY_PROMPT)
                .contains("explicit-mutation-verb-with-file-target"));
    }

    @Test
    void fixProblemInNamedFileIsExplicitMutationIntent() {
        for (String input : java.util.List.of(
                "Fix the bug in calc.py.",
                "Fix a bug in src/App.java.",
                "Please fix the failing test in FooTest.java.")) {
            assertTrue(MutationIntent.looksExplicitMutationRequest(input), input);
            assertEquals("explicit-fix-problem-in-file-target",
                    MutationIntent.classificationReason(input), input);
        }
    }

    @Test
    void fileScopedDefectThenImperativeFixIsExplicitMutationIntent() {
        assertTrue(MutationIntent.looksExplicitMutationRequest(SCN13_FIX_PROMPT));
        assertEquals("explicit-file-scoped-defect-fix-request",
                MutationIntent.classificationReason(SCN13_FIX_PROMPT));
    }

    @Test
    void advisoryFixProblemInNamedFileStaysReadOnly() {
        for (String input : java.util.List.of(
                "How would you fix the bug in calc.py?",
                "How would you fix multiply in calc.py?",
                "Can you explain how to fix the bug in calc.py?",
                "There is a bug in calc.py. Explain how to fix multiply.",
                "There is a bug in calc.py. How would you fix it?",
                "Should I fix the bug in calc.py?",
                "There is a bug in calc.py. Should I fix multiply?",
                "There is a bug in calc.py, but do not change files.",
                "There is a bug in calc.py. Don't fix it yet, just tell me what is wrong.",
                "There is a bug in calc.py. Do not fix it yet, just tell me what is wrong.",
                "There is a bug in calc.py. Dont fix it yet, just tell me what is wrong.",
                "There is a bug in calc.py. Should I fix it?",
                "There is a bug in calc.py. Can I fix it?",
                "There is a bug in calc.py. May I fix it?",
                "There is a bug in calc.py. Would we fix it?",
                "There is a bug in calc.py. Could we fix it?")) {
            assertFalse(MutationIntent.looksExplicitMutationRequest(input), input);
        }
    }

    @Test
    void assistantDirectedFixItRequestsStayMutationIntent() {
        for (String input : java.util.List.of(
                "There is a bug in calc.py. Can you fix it?",
                "There is a bug in calc.py. Would you fix it?",
                "There is a bug in calc.py. Could you fix it?",
                "Fix it.")) {
            assertTrue(MutationIntent.looksExplicitMutationRequest(input), input);
        }
    }

    @Test
    void retryStatusReviewAndAdvisoryEditPromptsStayReadOnly() {
        for (String input : java.util.List.of(
                "Review README.md",
                "What happened after the denied attempt?",
                "Should I edit README.md?",
                "Can you explain how to edit README.md?",
                "Show me how to update README.md.")) {
            assertFalse(MutationIntent.looksExplicitMutationRequest(input), input);
        }
    }

    @Test
    void advisoryRepairQuestionStaysReadOnly() {
        assertFalse(MutationIntent.looksExplicitMutationRequest("What repair would you make?"));
        assertFalse(MutationIntent.looksExplicitMutationRequest("Can you explain the repair?"));
    }

    @Test
    void capabilityOnlyCreationQuestionsStayReadOnly() {
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "I want to make 2 web pages. Can you help me with that? Is this in your skills?"));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Can you create websites, or is that outside your skills?"));
    }

    @Test
    void priorChangeStatusQuestionsAreNotMutationIntent() {
        assertFalse(MutationIntent.looksExplicitMutationRequest("did you make the changes?"));
        assertFalse(MutationIntent.looksExplicitMutationRequest("did you update the files?"));
        assertFalse(MutationIntent.looksExplicitMutationRequest("what did you change?"));
        assertFalse(MutationIntent.looksExplicitMutationRequest("why did nothing change?"));
    }

    @Test
    void readOnlyNegationStillWinsForRepair() {
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Repair this file but do not change anything."));
    }

    @Test
    void turkishDefaultLocaleDoesNotBreakAsciiMutationIntent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertTrue(MutationIntent.looksExplicitMutationRequest("FIX index.html."),
                    MutationIntent.classificationReason("FIX index.html."));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void namedFileScopedNegationDoesNotCancelMutationIntent() {
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Fix only styles.css. Do not change index.html or scripts.js."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Edit only index.html; don't touch styles.css."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Summarize long-notes.txt into ideas/summary.md. keep it tight. don't touch private files."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Summarize long-notes.txt into ideas/summary.md. do not touch protected files."));
    }

    @Test
    void scopedDesignConstraintDoesNotCancelMutationIntent() {
        for (String input : java.util.List.of(
                "Restyle the page but do not change the color scheme.",
                "Make the hero bigger, do not touch the footer.")) {
            assertTrue(MutationIntent.looksExplicitMutationRequest(input),
                    MutationIntent.classificationReason(input));
            assertFalse(MutationIntent.classificationReason(input)
                    .contains("global-read-only-negation"), input);
        }
    }

    @Test
    void scopedTailwindArtifactNegationDoesNotCancelExplicitStaticWebCreation() {
        assertTrue(MutationIntent.looksExplicitMutationRequest(RETROCATS_AUDIT_PROMPT));
        assertFalse(MutationIntent.classificationReason(RETROCATS_AUDIT_PROMPT)
                .contains("global-read-only-negation"));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Create the website. Do not create a local tailwind.min.css file."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Create the website. Do not use local tailwind.min.css."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Create the website with no broken tailwind.min.css and no placeholder Tailwind file."));
    }

    @Test
    void globalCreateNegationsStillCancelMutationIntent() {
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Do not create files. Just explain the website structure."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Do not create anything. Describe what you would make."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Do not edit anything. Review the current site."));
    }

    @Test
    void readThenCreateFromItSeparatesSourceAndOutputTargets() {
        MutationIntent.SourceToTargetArtifact artifact = MutationIntent.sourceToTargetArtifact(
                "read long-notes.txt and create ideas/summary.md from it; do not read .env.")
                .orElseThrow();

        assertEquals(Set.of("long-notes.txt"), artifact.sourceTargets());
        assertEquals(Set.of("ideas/summary.md"), artifact.outputTargets());
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "read long-notes.txt and create ideas/summary.md from it; do not read .env."));
    }

    @Test
    void readThenCreateMultipleOutputsFromItSeparatesSourceAndOutputTargets() {
        MutationIntent.SourceToTargetArtifact artifact = MutationIntent.sourceToTargetArtifact(
                "read brief.txt and create index.html, styles.css, and scripts.js from it.")
                .orElseThrow();

        assertEquals(Set.of("brief.txt"), artifact.sourceTargets());
        assertEquals(Set.of("index.html", "styles.css", "scripts.js"), artifact.outputTargets());
    }

    @Test
    void globalReadOnlyNegationStillCancelsMutationIntent() {
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Do not change anything. Just inspect."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Summarize long-notes.txt into ideas/summary.md, but don't touch files."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Diagnose this, do not change files."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Show me how to make one, do not edit files."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "I am only chatting, please don't inspect my files. What can you do for me?"));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Can you explain how to build a BMI calculator?"));
    }

    @Test
    void formattingNegationDoesNotCancelExplicitMutationIntent() {
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Use talos.write_file to overwrite index.html. "
                        + "Set the content argument to the exact five letters AFTER. "
                        + "Do not use angle brackets. Do not use placeholders. "
                        + "The entire file should be AFTER."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Use write_file to overwrite index.html. Do not use placeholders."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Overwrite index.html. Do not use angle brackets."));

        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Do not edit files. Explain what you would change."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "I am only chatting, please don't inspect my files. What can you do for me?"));
    }

    @org.junit.jupiter.api.Test
    void explicitInlineOutputPhrasesClassifyAsNonMutatingChatCodegen() {
        String captured = "Write a Java method int[] twoSum(int[] nums, int target) that returns "
                + "the indices of two numbers that add up to target. Output only the code.";
        org.junit.jupiter.api.Assertions.assertEquals(
                "inline-output-request", MutationIntent.classificationReason(captured));
        org.junit.jupiter.api.Assertions.assertFalse(
                MutationIntent.looksExplicitMutationRequest(captured));

        for (String input : java.util.List.of(
                "Write a quicksort in Python. Just show me the code.",
                "Write a debounce helper in JavaScript, answer inline please.")) {
            org.junit.jupiter.api.Assertions.assertEquals(
                    "inline-output-request", MutationIntent.classificationReason(input), input);
        }
        // Overlaps the older read-only negation counter-signal; either
        // reason is fine as long as the outcome is non-mutating.
        org.junit.jupiter.api.Assertions.assertFalse(
                MutationIntent.looksExplicitMutationRequest(
                        "Write a regex for emails. Do not create any files."));
    }

    @org.junit.jupiter.api.Test
    void namedFileTargetsKeepMutationRoutingDespiteInlinePhrases() {
        String input = "Write twoSum to Solution.java. Output only the code.";
        org.junit.jupiter.api.Assertions.assertTrue(
                MutationIntent.looksExplicitMutationRequest(input),
                MutationIntent.classificationReason(input));
    }
}
