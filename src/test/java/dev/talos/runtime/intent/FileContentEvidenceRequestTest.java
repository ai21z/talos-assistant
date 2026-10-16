package dev.talos.runtime.intent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class FileContentEvidenceRequestTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Read files.", "Read the main HTML, CSS, and JS files and explain three improvements.",
            "Please inspect the relevant project files.", "Could you please review the current configuration files?",
            "Open these source files.", "Examine our Java and Python files.",
            "Use evidence from the actual files, not generic website advice.",
            "Use content from my local files.", "Use information from the current files. Do not edit anything.",
            "Explain the difference. Read the main files."
    })
    void directSourceContentRequestsRequireCurrentEvidence(String request) {
        assertTrue(FileContentEvidenceRequest.requiresCurrentFiles(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Explain evidence policies. No project inspection is needed.",
            "No files are required.", "Do not read files.", "Read the files if needed.",
            "Read the files only if asked.", "Read the files. Actually do not inspect anything.",
            "Read the files. No file access is required.", "Optionally read the files.",
            "Read the explanation of how to organize files.", "Review advice about files.",
            "Read the following description of files.", "Read the attached files.",
            "Read the files pasted below.", "Read the files from this prompt.",
            "Read the files attached to this message.",
            "Examine the files. Actually do not examine anything.",
            "Use evidence from the files. Do not use evidence from files.",
            "Read the files provided with this example.", "Read the files shown in my message.",
            "Read the files uploaded to this message.",
            "Open the files. Never open anything.", "Review the files. Stop reviewing files.",
            "Examine the files. Avoid examining files.",
            "Use facts from the files. Stop using facts from files.",
            "The tutorial says:\nRead the files.", "Explain this sentence, read the files.",
            "Explain \"Read the files.\"", "`Read the files.`", "(Read the files.)", "[Read the files.",
            "Read the\u0007 files.", "Please tell me how to read files.", "What was read from the files?",
            "Suppose a model says this. Read the files.", "Read a story summarizing files.",
            "Read the unusual material about files.", "Use evidence policies from the actual files as a quoted example."
    })
    void quotedConditionalWithdrawnAndUnresolvedObjectsDoNotGrantCurrentFileIntent(String request) {
        assertFalse(FileContentEvidenceRequest.requiresCurrentFiles(request));
    }
}
