package dev.talos.runtime.intent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cue is deliberately coarse. A false positive withholds detail, a false negative could disclose it. */
class OutputRestrictionCueTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Is this folder empty? Don't show its contents.",
            "Keep the file names private. Is this folder empty?",
            "Do not reveal the workspace path. Is this folder empty?",
            "Just tell me whether it is empty.",
            "Only say yes or no.",
            "Hide the names, is anything here?",
            "Is it empty? Never mention file names.",
            "Answer without listing names.",
            "Don't touch or list any files.",
            "Please don't edit anything, and don't show the names.",
            "Is it empty? Dont show the files.",
            // Counter-review bypasses: a mutation verb leading a clause must not exempt later disclosure verbs.
            "Never change, list or read files.",
            "Don't edit, show or name anything.",
            "Don't modify, list, or reveal any files.",
            "Don't edit anything and keep the files secret.",
            "Don't change anything and hide the contents.",
            "Do not change anything and omit the entries.",
            // Counter-review vocabulary gaps.
            "Keep the names to yourself.",
            "Leave out the names.",
            "Is it empty? Not the names though.",
            "I'd rather you didn't list them.",
            "Is it empty, yes or no, nothing more.",
            "Refrain from listing files."
    })
    void restrictionShapedWordingIsDetected(String request) {
        assertTrue(OutputRestrictionCue.present(request), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Hey, what can you see in this folder? Is it empty?",
            "Hey, what can you see in this folder? Please don't edit anything.",
            "Give me a short handoff. What is saved and verified? Don't change any files.",
            "Do not modify or delete anything. What is in this folder?",
            "Never write to disk. Where are we?",
            "Don't change any files, please. What is in this folder?",
            "is any file in this workspace?",
            ""
    })
    void ordinaryWordingAndMutationOnlyProhibitionsAreNotRestrictions(String request) {
        assertFalse(OutputRestrictionCue.present(request), request);
    }
}
