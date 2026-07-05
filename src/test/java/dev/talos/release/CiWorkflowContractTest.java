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
    @DisplayName("CI checkout fetches full history for version and provenance checks")
    void ciCheckoutFetchesFullHistoryForVersionAndProvenanceChecks() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "beta-dev-ci.yml"));
        long checkoutUses = Pattern.compile("uses:\\s*actions/checkout@").matcher(workflow).results().count();
        long fullHistoryCheckouts = Pattern.compile(
                "uses:\\s*actions/checkout@[^\\r\\n]+\\R\\s+with:\\R\\s+fetch-depth:\\s*0")
                .matcher(workflow)
                .results()
                .count();

        assertTrue(checkoutUses > 0, "workflow must use actions/checkout");
        assertEquals(checkoutUses, fullHistoryCheckouts,
                "every checkout must fetch full history for version/provenance checks");
    }

    @Test
    @DisplayName("CI triggers match the public main and beta-dev branch model")
    void ciTriggersMatchPublicMainAndBetaDevBranchModel() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "beta-dev-ci.yml"));

        assertTrue(workflow.contains("branches: [main, v0.9.0-beta-dev]"),
                "pull_request CI must target only public main and the beta-dev integration branch");
        assertTrue(workflow.contains("- main"),
                "push CI must run on public main");
        assertTrue(workflow.contains("- v0.9.0-beta-dev"),
                "push CI must run on the beta-dev integration branch");
        assertTrue(!workflow.contains("codex/**"),
                "CI push triggers must not retain dead codex branch patterns");
        assertTrue(!workflow.contains("feature/**"),
                "CI push triggers must not retain dead feature branch patterns");
        assertTrue(!workflow.contains("improvement/**"),
                "CI push triggers must not add old release-branch patterns as product policy");
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
        assertTrue(workflow.contains("${{ github.sha }}") && workflow.contains("attestationSourceRepositoryDigest"),
                "workflow must record the GitHub attestation source digest explicitly");
        assertTrue(workflow.contains("does not match GitHub attestation SourceRepositoryDigest"),
                "workflow must fail when target_sha differs from the workflow ref SHA GitHub binds into attestations");
        assertTrue(workflow.contains("artifactBuildCheckoutSha"),
                "staging manifests must distinguish the checked-out build SHA from attestation source identity");
        assertTrue(workflow.contains("attestationDigestPolicy"),
                "staging manifests must name the selected attestation digest policy");
        assertTrue(workflow.contains("talosVersion=${{ inputs.version }}"),
                "workflow must verify gradle.properties matches the requested version");
        assertTrue(workflow.contains("Working tree is dirty before release staging"),
                "workflow must fail if candidate checkout becomes dirty before staging artifacts");
        assertTrue(workflow.contains("./gradlew.bat clean check --no-daemon"),
                "automated candidate gate must run before staging artifacts");
        assertTrue(workflow.contains("./gradlew.bat talosQualitySummaries --no-daemon"),
                "quality summaries must run before staging artifacts");
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
                "Linux staging must wait for the Windows automated gate/staging job");
        assertTrue(workflow.contains("./gradlew linuxReleaseArtifacts --no-daemon"),
                "workflow must build the Linux release artifact set");
        assertTrue(workflow.contains("qa-staging-talos-${{ inputs.version }}-linux-x64"),
                "Linux uploaded artifact must be explicitly named as QA staging");
        assertTrue(workflow.contains("build/release/linux/"),
                "Linux uploaded staging artifact must come from the Linux release output folder");
        assertTrue(workflow.contains("id-token: write"),
                "release staging must request OIDC only for GitHub artifact attestations");
        assertTrue(workflow.contains("attestations: write"),
                "release staging must request explicit attestation write permission");
        assertTrue(workflow.contains("uses: actions/attest@v4"),
                "release staging must use GitHub's current attestation action");
        assertTrue(workflow.contains("subject-path: |"),
                "release staging must attest the staged release files by path");
        assertTrue(workflow.contains("build/release/windows/*"),
                "Windows staged release files must be attested");
        assertTrue(workflow.contains("build/release/linux/*"),
                "Linux staged release files must be attested");
        assertTrue(workflow.contains("sbom-path: build/release/windows/talos-${{ inputs.version }}-sbom.cdx.json"),
                "Windows staged release artifacts must carry an SBOM attestation");
        assertTrue(workflow.contains("sbom-path: build/release/linux/talos-${{ inputs.version }}-sbom.cdx.json"),
                "Linux staged release artifacts must carry an SBOM attestation");

        assertNoReleasePublication(workflow, "release staging workflow");
        assertTrue(!workflow.contains("push:"),
                "release staging must not run from branch or tag pushes");
        assertTrue(!workflow.contains("release:"),
                "release staging must not run from GitHub Release events");
        assertTrue(!workflow.contains("pull_request:"),
                "release staging must not run from pull request events");
    }

    @Test
    @DisplayName("site staging deploy is manual, preview-only, and explicitly named")
    void siteStagingWorkflowDeploysOnlyNamedCloudflarePreview() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "site-staging.yml"));

        assertTrue(workflow.contains("workflow_dispatch:"),
                "site staging must be manually dispatched against an explicit candidate");
        assertTrue(workflow.contains("target_sha:"),
                "site staging must require an exact candidate commit SHA");
        assertTrue(workflow.contains("version:"),
                "site staging must require the expected Talos version");
        assertTrue(workflow.contains("ref: ${{ inputs.target_sha }}"),
                "checkout must use the requested candidate SHA, not the branch head");
        assertTrue(workflow.contains("git rev-parse HEAD"),
                "workflow must verify the checked-out SHA");
        assertTrue(workflow.contains("talosVersion=${{ inputs.version }}"),
                "workflow must verify gradle.properties matches the requested version");
        assertTrue(workflow.contains("CLOUDFLARE_API_TOKEN"),
                "workflow must use an explicit Cloudflare API token secret");
        assertTrue(workflow.contains("CLOUDFLARE_ACCOUNT_ID"),
                "workflow must use an explicit Cloudflare account id variable or secret");
        assertTrue(workflow.contains("npm ci --prefix site"),
                "workflow must install site dependencies from the lockfile");
        assertTrue(workflow.contains("npm test --prefix site"),
                "workflow must run site honesty/static tests before building");
        assertTrue(workflow.contains("npm run build --prefix site"),
                "workflow must build the Vite site before deployment");
        assertTrue(workflow.contains("npm run test:deploy-surface --prefix site"),
                "workflow must scan built site output before deployment");
        assertTrue(workflow.contains("npx --yes wrangler@"),
                "workflow must deploy through an explicit Wrangler invocation");
        assertTrue(workflow.contains("pages deploy site/dist"),
                "workflow must deploy only the built site/dist directory");
        assertTrue(workflow.contains("--project-name=taloslocal"),
                "Cloudflare Pages project name must be explicit and product-owned");
        assertTrue(workflow.contains("--branch=site-staging"),
                "workflow must deploy to the site-staging preview branch, not production");
        assertTrue(workflow.contains("site-staging.taloslocal.pages.dev"),
                "workflow output must name the expected preview alias");

        assertNoReleasePublication(workflow, "site staging workflow");
        assertTrue(!workflow.contains("push:"),
                "site staging must not run from branch or tag pushes");
        assertTrue(!workflow.contains("release:"),
                "site staging must not run from GitHub Release events");
        assertTrue(!workflow.contains("pull_request:"),
                "site staging must not run from pull request events");
        assertTrue(!workflow.contains("taloslocal.com"),
                "site staging must not bind or claim the production domain");
        assertTrue(!workflow.contains("codex"),
                "site staging workflow must not leak assistant/tool resource names");
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
        String docs = Files.readString(Path.of("docs", "development", "release-process.md"));

        assertTrue(docs.contains(".github/workflows/release-staging.yml"),
                "public install docs must name the release staging workflow");
        assertTrue(docs.contains("qa-staging-talos-<version>-windows-x64"),
                "docs must show the QA staging artifact name");
        assertTrue(docs.contains("not a GitHub Release asset"),
                "docs must state staging artifacts are not GitHub Release assets");
        assertTrue(docs.contains("No draft GitHub Release asset"),
                "docs must block draft release assets before QA");
        assertTrue(docs.contains("Release QA Gate"),
                "docs must tie publication to the release QA gate");
    }

    @Test
    @DisplayName("public installation docs describe CI protection and release trigger policy")
    void publicInstallDocsDescribeCiProtectionAndReleaseTriggerPolicy() throws IOException {
        String docs = Files.readString(Path.of("docs", "development", "release-process.md"));

        assertTrue(docs.contains("gh api repos/ai21z/talos-assistant/branches/main/protection"),
                "docs must capture the branch-protection verification command");
        assertTrue(docs.contains("Gradle check (Java 21)"),
                "docs must name the required Windows CI check");
        assertTrue(docs.contains("Linux command portability smoke (Java 21)"),
                "docs must name the required Linux portability check");
        assertTrue(docs.contains("main is protected"),
                "docs must state the public main protection policy");
        assertTrue(docs.contains("v0.9.0-beta-dev"),
                "docs must state the beta-dev integration branch policy");
        assertTrue(docs.contains("workflow_dispatch"),
                "docs must state release staging is manually dispatched");
        assertTrue(docs.contains("No tag, push, pull-request, or release event may publish public artifacts"),
                "docs must block accidental publication triggers");
    }

    @Test
    @DisplayName("public installation docs describe checksum, SBOM, and attestation boundaries")
    void publicInstallDocsDescribeProvenanceBoundaries() throws IOException {
        String docs = Files.readString(Path.of("docs", "development", "release-process.md"));

        assertTrue(docs.contains("Checksums prove that downloaded bytes match the published checksum manifest"),
                "docs must explain the checksum boundary");
        assertTrue(docs.contains("The SBOM is a CycloneDX dependency inventory"),
                "docs must explain the SBOM boundary");
        assertTrue(docs.contains("Artifact attestations bind staged files to a GitHub Actions workflow run"),
                "docs must explain the attestation boundary");
        assertTrue(docs.contains("None of these metadata artifacts prove Talos behavior is correct"),
                "docs must prevent supply-chain metadata from replacing QA");
        assertTrue(docs.contains("gh attestation verify"),
                "docs must include the attestation verification command");
        assertTrue(docs.contains("--predicate-type https://cyclonedx.org/bom"),
                "docs must include the SBOM attestation verification command");
        assertTrue(docs.contains("attestationSourceRepositoryDigest"),
                "docs must name the manifest field reviewers should use for --source-digest");
        assertTrue(docs.contains("target_sha must match the workflow ref SHA"),
                "docs must explain that release staging rejects dual-SHA provenance");
        assertTrue(docs.contains("Release QA Gate"),
                "docs must keep behavioral release readiness owned by the QA gate");
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
                    label + " must not create GitHub Release or draft release assets before release QA approval");
        }
        assertTrue(!workflow.contains("contents: write"),
                label + " must not request contents: write while it only stages artifacts");
    }
}
