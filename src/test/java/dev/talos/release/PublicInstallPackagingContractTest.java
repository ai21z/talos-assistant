package dev.talos.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Public install packaging contract")
class PublicInstallPackagingContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();

    @Test
    @DisplayName("jpackage uses a Windows console, per-user install, and publisher identity")
    void jpackageUsesWindowsPublicBetaOptions() throws Exception {
        String build = read("build.gradle.kts");

        assertTrue(build.contains("tasks.register<Exec>(\"jpackageApp\")"),
                "build must keep a jpackageApp task for the Windows MSI");
        assertTrue(build.contains("\"--type\", \"msi\""),
                "jpackageApp must build the MSI package type");
        assertTrue(build.contains("\"--win-console\""),
                "Windows CLI package must create a console launcher");
        assertTrue(build.contains("\"--win-per-user-install\""),
                "public beta MSI must support non-admin per-user install");
        assertTrue(build.contains("\"--vendor\", \"Vissarion Zounarakis\""),
                "publisher/vendor identity must match the public winget publisher target");
    }

    @Test
    @DisplayName("release build publishes the expected Windows x64 artifacts")
    void releaseBuildPublishesWindowsArtifacts() throws Exception {
        String build = read("build.gradle.kts");

        for (String task : new String[] {
                "jpackageAppImage",
                "windowsReleaseMsi",
                "windowsReleaseAppZip",
                "copyWindowsReleaseBootstrap",
                "windowsReleaseChecksums",
                "windowsReleaseArtifacts"
        }) {
            assertTrue(build.contains("\"" + task + "\""), "missing release task: " + task);
        }

        assertTrue(build.contains("Talos-${version}-windows-x64.msi"),
                "release MSI must use the canonical artifact name");
        assertTrue(build.contains("talos-${version}-windows-x64-app.zip"),
                "release app-image ZIP must use the canonical artifact name");
        assertTrue(build.contains("checksums.txt"),
                "release artifacts must include checksum output");
    }

    @Test
    @DisplayName("release build publishes the expected Linux x64 runtime-bundled artifact")
    void releaseBuildPublishesLinuxRuntimeBundledArtifact() throws Exception {
        String build = read("build.gradle.kts");

        for (String task : new String[] {
                "jpackageLinuxAppImage",
                "linuxReleaseAppTar",
                "copyLinuxReleaseBootstrap",
                "linuxReleaseChecksums",
                "linuxReleaseArtifacts"
        }) {
            assertTrue(build.contains("\"" + task + "\""), "missing Linux release task: " + task);
        }

        assertTrue(build.contains("talos-${version}-linux-x64-app.tar.gz"),
                "Linux public beta artifact must use the canonical runtime-bundled tarball name");
        assertTrue(build.contains("\"--type\", \"app-image\""),
                "Linux public beta lane must use a runtime-bundled jpackage app image");
        assertTrue(build.contains("\"--name\", \"talos\""),
                "Linux app image must expose a lowercase talos launcher");
        assertTrue(build.contains("build/release/linux"),
                "Linux release artifacts must be staged separately from Windows artifacts");
        assertTrue(build.contains("install-talos.sh"),
                "Linux public release artifact set must include the Linux bootstrap script");
        assertFalse(build.contains("linuxReleaseDistTar"),
                "T931 must not fall back to a BYO-JDK distTar lane without recorded proof");
    }

    @Test
    @DisplayName("generated launchers include Java native-access allowance")
    void generatedLaunchersAllowNativeAccessForBundledTerminalAndIndexLibraries() throws Exception {
        String build = read("build.gradle.kts");

        assertTrue(build.contains("applicationDefaultJvmArgs = listOf("),
                "Gradle application plugin must own generated launcher JVM defaults");
        assertTrue(build.contains("\"--enable-native-access=ALL-UNNAMED\""),
                "launchers must suppress Java FFM native-access warnings from bundled JLine/Lucene");
    }

    @Test
    @DisplayName("signed bootstrap is checksum-based and does not execute downloaded code")
    void bootstrapIsChecksumBasedAndNonBlind() throws Exception {
        String script = read("tools/install-talos.ps1");

        assertTrue(script.contains("ai21z/talos-assistant"),
                "bootstrap must download from the canonical GitHub Releases repository");
        assertFalse(script.contains("ai21z/talos-cli"),
                "bootstrap must not point at the pre-rename GitHub repository");
        assertTrue(script.contains("checksums.txt"),
                "bootstrap must verify against the release checksum manifest");
        assertTrue(script.contains("Get-FileHash"),
                "bootstrap must verify downloaded artifact hashes");
        assertTrue(script.contains("Get-AuthenticodeSignature"),
                "bootstrap must enforce or explicitly acknowledge script signing");
        assertTrue(script.contains("$env:LOCALAPPDATA"),
                "bootstrap must install under the current Windows user profile");
        assertTrue(script.contains("SetEnvironmentVariable"),
                "bootstrap must update the user PATH without requiring admin rights");
        assertTrue(script.contains("talos.cmd"),
                "bootstrap must install a stable lowercase talos command shim");

        assertFalse(script.matches("(?is).*\\b(?:Invoke-Expression|iex)\\b.*"),
                "bootstrap must not execute downloaded script text");
        assertFalse(script.matches("(?is).*irm\\b.*\\|.*(?:iex|powershell).*"),
                "bootstrap must not use blind irm | iex install style");
        assertFalse(script.matches("(?is).*llama-server\\.exe.*download.*"),
                "bootstrap must not download llama.cpp server binaries");
        assertFalse(script.matches("(?is).*(?:qwen|gpt-oss|gguf).*download.*"),
                "bootstrap must not download model weights");
    }

    @Test
    @DisplayName("Windows public bootstrap prints current setup handoff and refreshes PATH")
    void windowsPublicBootstrapPrintsCurrentSetupHandoffAndRefreshesPath() throws Exception {
        String script = read("tools/install-talos.ps1");

        assertTrue(script.contains("SendMessageTimeout"),
                "public bootstrap must broadcast PATH changes like the developer installer");
        assertTrue(script.contains("WM_SETTINGCHANGE"),
                "public bootstrap must notify Windows that the user environment changed");
        assertTrue(script.contains("talos --version"),
                "public bootstrap must tell users how to verify the installed command");
        assertTrue(script.contains("talos setup models"),
                "Windows public setup handoff must stay on the Windows-supported model setup path");
        assertTrue(script.contains("talos status --verbose"),
                "public bootstrap must point users at verbose status diagnostics");
        assertFalse(script.contains("talos setup wizard"),
                "Windows public bootstrap must not advertise the Ubuntu/WSL-only setup wizard");
        assertFalse(script.contains("rag-index"),
                "public bootstrap must not advertise stale retrieval subcommands as first-run guidance");
        assertFalse(script.contains("rag-ask"),
                "public bootstrap must not advertise stale retrieval subcommands as first-run guidance");
    }

    @Test
    @DisplayName("Windows developer installer prints current setup handoff without stale RAG commands")
    void windowsDeveloperInstallerPrintsCurrentSetupHandoffWithoutStaleRagCommands() throws Exception {
        String script = read("tools/install-windows.ps1");

        assertTrue(script.contains("SendMessageTimeout"),
                "developer installer must keep broadcasting PATH changes");
        assertTrue(script.contains("WM_SETTINGCHANGE"),
                "developer installer must notify Windows that the user environment changed");
        assertTrue(script.contains("talos --version"),
                "developer installer must tell users how to verify the installed command");
        assertTrue(script.contains("talos setup models"),
                "developer installer must point Windows users at the supported model setup path");
        assertTrue(script.contains("talos status --verbose"),
                "developer installer must point users at verbose status diagnostics");
        assertFalse(script.contains("talos setup wizard"),
                "developer Windows installer must not advertise the Ubuntu/WSL-only setup wizard");
        assertFalse(script.contains("rag-index"),
                "developer installer must not advertise stale retrieval subcommands as first-run guidance");
        assertFalse(script.contains("rag-ask"),
                "developer installer must not advertise stale retrieval subcommands as first-run guidance");
    }

    @Test
    @DisplayName("Unix bootstrap verifies Java and the direct installed Talos binary")
    void unixBootstrapVerifiesJavaAndDirectInstalledBinary() throws Exception {
        String script = read("tools/install-unix.sh");

        assertTrue(script.contains("--dry-run"),
                "Unix installer must expose a dry-run/bootstrap preflight path for deterministic smoke tests");
        assertTrue(script.contains("detect_java_feature"),
                "Unix installer must detect Java before first Talos JVM invocation");
        assertTrue(script.contains("openjdk-21-jre-headless"),
                "Ubuntu/WSL source setup must render the exact Java 21 runtime package command");
        assertTrue(script.contains("Java 21+ was not detected"),
                "missing Java must produce a clear block before Talos is invoked");
        assertTrue(script.contains("\"$INSTALL_DIR/bin/talos\" --version"),
                "Unix installer must verify the direct Linux-installed binary, not inherited PATH talos");
        assertFalse(script.contains("run: talos --version"),
                "Unix installer verification copy must not rely on inherited PATH talos");
    }

    @Test
    @DisplayName("Unix bootstrap uses login-shell profile detection instead of interpreter variables")
    void unixBootstrapUsesLoginShellProfileDetection() throws Exception {
        String script = read("tools/install-unix.sh");

        assertTrue(script.contains("--profile-file"),
                "Unix installer must allow an explicit profile target for deterministic installs");
        assertTrue(script.contains("select_shell_profile"),
                "Unix installer must centralize shell-profile selection");
        assertTrue(script.contains("$SHELL"),
                "Unix installer must inspect the user's login shell");
        assertTrue(script.contains("getent passwd"),
                "Unix installer must fall back to passwd shell data when SHELL is unavailable");
        assertTrue(script.contains(".zshrc"),
                "zsh login-shell users must be routed to .zshrc");
        assertTrue(script.contains(".bashrc"),
                "bash login-shell users must be routed to .bashrc");
        assertFalse(script.contains("ZSH_VERSION"),
                "script interpreter variables must not decide the user's shell profile");
        assertFalse(script.contains("BASH_VERSION"),
                "running the installer under bash must not force .bashrc for zsh users");
    }

    @Test
    @DisplayName("Unix bootstrap keeps package-manager and model setup explicit")
    void unixBootstrapKeepsPackageManagerAndModelSetupExplicit() throws Exception {
        String script = read("tools/install-unix.sh");

        assertTrue(script.contains("--allow-package-install"),
                "package-manager execution must require an explicit opt-in flag");
        assertTrue(script.contains("ALLOW_PACKAGE_INSTALL=false"),
                "package-manager execution must be disabled by default");
        assertTrue(script.contains("talos setup wizard"),
                "successful bootstrap should hand off to the Talos-owned setup wizard");
        assertFalse(script.matches("(?is).*(?:curl|wget|gh release download|huggingface-cli|hf download).*llama[-.]cpp.*"),
                "Unix bootstrap must not download llama.cpp in this milestone");
        assertFalse(script.matches("(?is).*(?:curl|wget|huggingface-cli|hf download).*(?:qwen|gpt-oss|gguf).*"),
                "Unix bootstrap must not download model weights in this milestone");
    }

    @Test
    @DisplayName("setup wizard owns pinned engine and model manifests with explicit model download prompt")
    void setupWizardOwnsPinnedEngineAndExplicitModelDownloadManifest() throws Exception {
        String engineManifest = read("src/main/java/dev/talos/cli/setup/LlamaCppEngineManifest.java");
        String modelManifest = read("src/main/java/dev/talos/cli/setup/LlamaCppModelManifest.java");
        String installer = read("src/main/java/dev/talos/cli/setup/LlamaCppEngineInstaller.java");
        String runner = read("src/main/java/dev/talos/cli/setup/SetupWizardRunner.java");
        String unixBootstrap = read("tools/install-unix.sh");

        assertTrue(engineManifest.contains("llama-b9860-bin-ubuntu-x64.tar.gz"),
                "manifest must pin the Ubuntu x64 CPU llama.cpp artifact");
        assertTrue(engineManifest.contains("b68e8072eb88d1cc8b8e9d6ea8237aae87b34c6d8bbffda958c870e4dc949714"),
                "manifest must pin the artifact SHA-256");
        assertFalse(engineManifest.contains("releases/latest"),
                "manifest must not follow upstream latest");
        assertTrue(modelManifest.contains("qwen2.5-coder-14b-instruct-q4_k_m.gguf"),
                "model manifest must pin the accepted beta Qwen GGUF filename");
        assertTrue(modelManifest.contains("gpt-oss-20b-mxfp4.gguf"),
                "model manifest must pin the accepted beta GPT-OSS GGUF filename");
        assertTrue(modelManifest.contains("c1e659736d89ac1065fb495330fb824d94001974a4bfa78e7270e43476a8d940"),
                "model manifest must pin Qwen SHA-256");
        assertTrue(modelManifest.contains("be37a636aca0fc1aae0d32325f82f6b4d21495f06823b5fbc1898ae0303e9935"),
                "model manifest must pin GPT-OSS SHA-256");
        assertTrue(installer.contains("SHA-256 mismatch"),
                "installer must fail closed on checksum mismatch");
        assertTrue(runner.contains("Install this pinned llama.cpp engine now? [y/N]"),
                "wizard must require explicit engine-install confirmation");
        assertTrue(runner.contains("Download this model now? [y/N]"),
                "wizard must require explicit model-download confirmation");
        assertFalse(unixBootstrap.matches("(?is).*(?:curl|wget|huggingface-cli|hf download).*(?:qwen|gpt-oss|gguf).*"),
                "Unix bootstrap must not download model weights; the Talos wizard owns that prompted step");
    }

    @Test
    @DisplayName("Linux public bootstrap verifies release artifacts before user-local install")
    void linuxPublicBootstrapVerifiesReleaseArtifactsBeforeInstall() throws Exception {
        String script = read("tools/install-talos.sh");

        assertTrue(script.contains("ai21z/talos-assistant"),
                "Linux bootstrap must download from the canonical GitHub Releases repository");
        assertTrue(script.contains("talos-$release_version-linux-x64-app.tar.gz"),
                "Linux bootstrap must derive the canonical versioned tarball name");
        assertTrue(script.contains("checksums.txt"),
                "Linux bootstrap must download or accept a release checksum manifest");
        assertTrue(script.contains("sha256sum"),
                "Linux bootstrap must verify SHA-256 before extracting or installing");
        assertTrue(script.contains("assert_supported_linux_x64"),
                "Linux bootstrap must refuse unsupported OS/arch before downloading");
        assertTrue(script.contains("Unsupported OS/arch"),
                "unsupported platforms must fail with a clear message");
        assertTrue(script.contains("$HOME/.local/share/talos"),
                "Linux bootstrap must install under the current user's local data directory");
        assertTrue(script.contains("$HOME/.local/bin"),
                "Linux bootstrap must expose a user-local talos command shim");
        assertTrue(script.contains("talos setup wizard"),
                "successful install must hand off to the explicit Talos setup wizard");
        assertTrue(script.contains("--no-wizard"),
                "release QA must be able to smoke install without launching interactive model setup");
        assertTrue(script.contains("--artifact-file"),
                "release QA must support installing a locally staged artifact without a public release");
        assertTrue(script.contains("--checksums-file"),
                "release QA must verify locally staged artifacts with the same checksum path");

        assertFalse(script.matches("(?is).*\\b(?:apt|apt-get|dnf|yum|pacman|brew)\\b.*"),
                "Linux public bootstrap must not run package managers");
        assertFalse(script.matches("(?im)^.*(?:curl|wget)[^\\r\\n]*\\|[^\\r\\n]*(?:bash|sh).*$"),
                "Linux public bootstrap must not encourage blind curl/wget pipe-to-shell execution");
        assertFalse(script.matches("(?is).*(?:curl|wget|gh release download|huggingface-cli|hf download).*llama[-.]cpp.*"),
                "Linux public bootstrap must not download llama.cpp; the setup wizard owns prompted engine setup");
        assertFalse(script.matches("(?is).*(?:curl|wget|huggingface-cli|hf download).*(?:qwen|gpt-oss|gguf).*"),
                "Linux public bootstrap must not download model weights; the setup wizard owns prompted model setup");
    }

    @Test
    @DisplayName("public docs describe Linux tarball lane without package-manager overclaim")
    void publicDocsDescribeLinuxTarballLane() throws Exception {
        String doc = read("docs/public-installation.md");
        String readme = read("README.md");

        for (String text : new String[] { doc, readme }) {
            assertTrue(text.contains("talos-<version>-linux-x64-app.tar.gz"),
                    "public docs must name the canonical Linux tarball artifact");
            assertTrue(text.contains("install-talos.sh"),
                    "public docs must name the Linux public bootstrap script");
            assertTrue(text.contains("Ubuntu/WSL x64"),
                    "public docs must keep the first Linux lane narrow");
            assertTrue(text.contains("runtime-bundled"),
                    "public docs must state the Linux public artifact includes its runtime");
            assertTrue(text.contains("talos setup wizard"),
                    "public docs must hand off to the explicit setup wizard after Talos install");
            assertTrue(text.contains("no DEB/RPM/Homebrew/SDKMAN"),
                    "public docs must keep native package-manager formats out of the beta claim");
        }
    }

    @Test
    @DisplayName("docs and site describe the beta install support boundary truthfully")
    void docsAndSiteDescribeInstallBoundary() throws Exception {
        String readme = read("README.md");
        String doc = read("docs/public-installation.md");
        String site = read("site/index.html");

        for (String text : new String[] { readme, doc, site }) {
            assertTrue(text.contains("winget install --id TalosProject.TalosCLI -e"),
                    "public install target must name the exact winget command");
            assertTrue(text.contains("talos-cli"),
                    "public install copy must expose talos-cli as the searchable package name or moniker");
            assertTrue(text.contains("Vissarion Zounarakis"),
                    "public install copy must name the winget publisher");
            assertTrue(text.contains("Windows x64"),
                    "public beta install copy must keep the packaged Windows x64 lane explicit");
            assertTrue(text.contains("Linux source/developer"),
                    "public beta install copy must describe the Linux source/developer lane explicitly");
            assertTrue(text.contains("bundled Java runtime"),
                    "public users must not be told to install Java manually");
            assertTrue(text.contains("llama.cpp server or model weights"),
                    "installer must not claim to bundle llama.cpp or model weights");
            assertTrue(text.contains("talos setup models"),
                    "model setup must remain a post-install Talos command");
        }

        String normalizedReadme = readme.replaceAll("\\s+", " ");

        assertTrue(readme.contains("Linux source/developer beta path"),
                "README must name Linux as source/developer beta support, not package-manager support");
        assertTrue(normalizedReadme.contains("no DEB/RPM/Homebrew/SDKMAN package claim"),
                "README must not imply native Linux package-manager support");
        assertTrue(doc.contains("GitHub Release is the canonical artifact host"),
                "public installation doc must name the release artifact host");
        assertTrue(doc.contains("WiX"),
                "public installation doc must record the Windows MSI builder prerequisite");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }
}
