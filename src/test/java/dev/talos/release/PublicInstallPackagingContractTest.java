package dev.talos.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Public Windows install packaging contract")
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
    @DisplayName("signed bootstrap is checksum-based and does not execute downloaded code")
    void bootstrapIsChecksumBasedAndNonBlind() throws Exception {
        String script = read("tools/install-talos.ps1");

        assertTrue(script.contains("ai21z/talos-cli"),
                "bootstrap must download from the canonical GitHub Releases repository");
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
                    "public beta install support must be Windows x64 only");
            assertTrue(text.contains("bundled Java runtime"),
                    "public users must not be told to install Java manually");
            assertTrue(text.contains("llama.cpp server or model weights"),
                    "installer must not claim to bundle llama.cpp or model weights");
            assertTrue(text.contains("talos setup models"),
                    "model setup must remain a post-install Talos command");
        }

        assertTrue(readme.contains("tools/install-unix.sh is source/developer-only"),
                "Unix script must not be positioned as a public beta installer");
        assertTrue(doc.contains("GitHub Release is the canonical artifact host"),
                "public installation doc must name the release artifact host");
        assertTrue(doc.contains("WiX"),
                "public installation doc must record the Windows MSI builder prerequisite");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }
}
