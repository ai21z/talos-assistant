package dev.talos.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiWorkflowContractTest {

    @Test
    @DisplayName("CI checkout fetches full history for wiki commit liveness")
    void ciCheckoutFetchesFullHistoryForWikiCommitLiveness() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "beta-dev-ci.yml"));
        long checkoutUses = Pattern.compile("uses:\\s*actions/checkout@").matcher(workflow).results().count();
        long fullHistoryCheckouts = Pattern.compile(
                "uses:\\s*actions/checkout@[^\\r\\n]+\\R\\s+with:\\R\\s+fetch-depth:\\s*0")
                .matcher(workflow)
                .results()
                .count();

        assertTrue(checkoutUses > 0, "workflow must use actions/checkout");
        assertEquals(checkoutUses, fullHistoryCheckouts,
                "every checkout must fetch full history so last_verified_commit git cat-file checks work in CI");
    }

    @Test
    @DisplayName("release staging workflow cannot create public release assets")
    void releaseStagingWorkflowUsesOnlyQaStagingArtifactsBeforePublication() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "release-staging.yml"));

        assertTrue(workflow.contains("workflow_dispatch:"),
                "release staging must be manually dispatched against an explicit candidate");
        assertTrue(workflow.contains("target_sha:"),
                "workflow must require an exact candidate commit SHA");
        assertTrue(workflow.contains("version:"),
                "workflow must require the expected Talos version");
        assertTrue(workflow.contains("ref: ${{ inputs.target_sha }}"),
                "checkout must use the requested candidate SHA, not the branch head");
        assertTrue(workflow.contains("git rev-parse HEAD"),
                "workflow must verify the checked-out SHA");
        assertTrue(workflow.contains("talosVersion=${{ inputs.version }}"),
                "workflow must verify gradle.properties matches the requested version");
        assertTrue(workflow.contains("Working tree is dirty before release staging"),
                "workflow must fail if candidate checkout becomes dirty before staging artifacts");
        assertTrue(workflow.contains("./gradlew.bat clean check --no-daemon"),
                "T929 automated gate must run before staging artifacts");
        assertTrue(workflow.contains("./gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon"),
                "T929 wiki evidence gate must run before staging artifacts");
        assertTrue(workflow.contains("./gradlew.bat talosQualitySummaries --no-daemon"),
                "T929 quality summaries must run before staging artifacts");
        assertTrue(workflow.contains("./gradlew.bat windowsReleaseArtifacts --no-daemon"),
                "workflow must build the Windows release artifact set");
        assertTrue(workflow.contains("actions/upload-artifact@"),
                "workflow should publish only workflow-run staging artifacts");
        assertTrue(workflow.contains("qa-staging-talos-${{ inputs.version }}-windows-x64"),
                "uploaded artifact must be explicitly named as QA staging");
        assertTrue(workflow.contains("build/release/windows/"),
                "uploaded staging artifact must come from the Windows release output folder");
        assertTrue(workflow.contains("linux-qa-staging"),
                "T931 must add a Linux QA staging job");
        assertTrue(workflow.contains("needs: windows-qa-staging"),
                "Linux staging must wait for the Windows T929 automated gate/staging job");
        assertTrue(workflow.contains("./gradlew linuxReleaseArtifacts --no-daemon"),
                "workflow must build the Linux release artifact set");
        assertTrue(workflow.contains("qa-staging-talos-${{ inputs.version }}-linux-x64"),
                "Linux uploaded artifact must be explicitly named as QA staging");
        assertTrue(workflow.contains("build/release/linux/"),
                "Linux uploaded staging artifact must come from the Linux release output folder");

        assertNoReleasePublication(workflow, "release staging workflow");
    }

    @Test
    @DisplayName("release workflow files are not silently ignored")
    void releaseWorkflowFilesAreNotSilentlyIgnored() throws IOException {
        String gitignore = Files.readString(Path.of(".gitignore"));

        assertTrue(gitignore.contains("/.github/*"),
                ".gitignore should ignore local-only GitHub metadata by default");
        assertTrue(gitignore.contains("!/.github/workflows/"),
                ".gitignore must unignore GitHub workflow directories");
        assertTrue(gitignore.contains("!/.github/workflows/*.yml"),
                ".gitignore must allow versioned workflow YAML files");
    }

    @Test
    @DisplayName("public installation docs describe release staging as non-public")
    void publicInstallDocsDescribeQaStagingWorkflowBoundary() throws IOException {
        String docs = Files.readString(Path.of("docs", "public-installation.md"));

        assertTrue(docs.contains(".github/workflows/release-staging.yml"),
                "public install docs must name the release staging workflow");
        assertTrue(docs.contains("qa-staging-talos-<version>-windows-x64"),
                "docs must show the QA staging artifact name");
        assertTrue(docs.contains("not a GitHub Release asset"),
                "docs must state staging artifacts are not GitHub Release assets");
        assertTrue(docs.contains("No draft GitHub Release asset"),
                "docs must block draft release assets before T929");
        assertTrue(docs.contains("T929"),
                "docs must tie publication to the T929 QA gate");
    }

    private static void assertNoReleasePublication(String workflow, String label) {
        String normalized = workflow.toLowerCase();
        for (String forbidden : new String[] {
                "gh release",
                "softprops/action-gh-release",
                "actions/create-release",
                "ncipollo/release-action",
                "draft: true",
                "make_latest"
        }) {
            assertTrue(!normalized.contains(forbidden),
                    label + " must not create GitHub Release or draft release assets before T929");
        }
        assertTrue(!workflow.contains("contents: write"),
                label + " must not request contents: write while it only stages artifacts");
    }
}
