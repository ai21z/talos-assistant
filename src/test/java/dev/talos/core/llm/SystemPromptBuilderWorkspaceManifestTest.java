package dev.talos.core.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7 — Verifies that a workspace manifest is already injected into the
 * system prompt by {@link SystemPromptBuilder#withWorkspace(Path)} via
 * {@link dev.talos.core.util.WorkspaceManifest}.
 *
 * <p>The manifest was already implemented prior to this pass. These tests
 * exist so the wiring is guarded against regression and so the project has
 * explicit, seam-correct proof that:
 *
 * <ul>
 *   <li>file paths (not contents) appear in the built prompt,</li>
 *   <li>the output is bounded (manifest has internal caps), and</li>
 *   <li>no manifest is injected when no workspace is supplied
 *       (safe default, no silent surprise).</li>
 * </ul>
 *
 * <p>This is the correct seam: {@code SystemPromptBuilder} is where every
 * mode (ASK / RAG / UNIFIED) composes its system prompt. The test asserts
 * on the final composed string, not on internal helpers.
 */
@DisplayName("R7 — SystemPromptBuilder workspace manifest wiring")
class SystemPromptBuilderWorkspaceManifestTest {

    @Test
    @DisplayName("prompt contains 'Workspace:' header and relative file paths when withWorkspace() is used")
    void workspaceManifestIsInjected(@TempDir Path workspace) throws IOException {
        // Populate a tiny tree — relative paths only, no noise directories.
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Main.java"), "class Main {}");
        Files.writeString(workspace.resolve("README.md"),
                "# Demo Project\nThis is a small demo used by the manifest test.");

        String prompt = SystemPromptBuilder.forUnified()
                .withWorkspace(workspace)
                .build();

        // Header
        assertTrue(prompt.contains("Workspace:"),
                "Prompt must include a 'Workspace:' header. Prompt was:\n" + prompt);
        // File structure section
        assertTrue(prompt.contains("File structure:"),
                "Prompt must include a 'File structure:' section. Prompt was:\n" + prompt);
        // Relative paths present (forward-slash normalized by WorkspaceManifest)
        assertTrue(prompt.contains("src/Main.java"),
                "Prompt must list the relative path src/Main.java. Prompt was:\n" + prompt);
        assertTrue(prompt.contains("README.md"),
                "Prompt must list README.md. Prompt was:\n" + prompt);

        // README excerpt is included — but this is a *grounding aid*, not a
        // substitute for reading files. The excerpt header is required; the
        // contents are allowed but bounded elsewhere.
        assertTrue(prompt.contains("README (excerpt):"),
                "Prompt must include README excerpt section header.");
    }

    @Test
    @DisplayName("prompt does NOT contain file contents from non-README files under 'File structure:'")
    void manifestListsPathsNotFileContents(@TempDir Path workspace) throws IOException {
        String secret = "THIS_STRING_IS_FILE_BODY_CONTENT_NOT_A_PATH";
        Files.writeString(workspace.resolve("a.txt"), secret);

        String prompt = SystemPromptBuilder.forUnified()
                .withWorkspace(workspace)
                .build();

        assertTrue(prompt.contains("a.txt"),
                "Path must be listed. Prompt was:\n" + prompt);
        assertFalse(prompt.contains(secret),
                "Manifest is a grounding aid — it must NOT leak file contents. "
                + "Prompt was:\n" + prompt);
    }

    @Test
    @DisplayName("manifest is bounded — MANIFEST_MAX_CHARS (2000) cap is honored even for busy workspaces")
    void manifestIsBounded(@TempDir Path workspace) throws IOException {
        // Create enough files to blow past the 80-entry tree cap and the 2000-char total cap.
        for (int i = 0; i < 200; i++) {
            Files.writeString(workspace.resolve("file_%03d.txt".formatted(i)), "x");
        }

        String prompt = SystemPromptBuilder.forUnified()
                .withWorkspace(workspace)
                .build();

        // Extract the manifest region (from "Workspace:" up to the next blank-line
        // section boundary introduced by SystemPromptBuilder). A loose upper
        // bound is sufficient here: the manifest's own internal cap is 2000,
        // so in practice the contribution can't exceed that plus a trailing
        // "\n...". We assert a generous ceiling — 2500 chars — to guard the
        // intent (bounded) without becoming brittle to formatting changes.
        int workspaceIdx = prompt.indexOf("Workspace:");
        assertTrue(workspaceIdx >= 0, "manifest must appear in prompt");

        // Find the next double-newline after the manifest — that's where
        // SystemPromptBuilder splices the next section.
        int end = prompt.indexOf("\n\n", workspaceIdx + 1);
        if (end < 0) end = prompt.length();
        int manifestLen = end - workspaceIdx;

        assertTrue(manifestLen <= 2500,
                "Manifest region must be bounded; was " + manifestLen + " chars. "
                + "This guards WorkspaceManifest.MANIFEST_MAX_CHARS (2000) + small formatting.");
        // And it must have been truncated, given 200 files.
        assertTrue(prompt.contains("(truncated)") || prompt.contains("..."),
                "With 200 files the manifest must show a truncation marker. Prompt region:\n"
                + prompt.substring(workspaceIdx, end));
    }

    @Test
    @DisplayName("no workspace supplied → no 'Workspace:' / 'File structure:' leakage into prompt")
    void noWorkspaceNoManifest() {
        String prompt = SystemPromptBuilder.forUnified().build();

        assertFalse(prompt.contains("Workspace:"),
                "Without withWorkspace(), no 'Workspace:' header must appear. Prompt:\n" + prompt);
        assertFalse(prompt.contains("File structure:"),
                "Without withWorkspace(), no 'File structure:' section must appear. Prompt:\n" + prompt);
    }
}

