package dev.talos.cli.setup;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LlamaCppEngineManifestTest {

    @Test
    void selectsPinnedUbuntuWslX64CpuArtifact() {
        SetupWizardSnapshot snapshot = snapshot("Linux", "amd64", true, "Ubuntu 26.04 LTS");

        var entry = LlamaCppEngineManifest.select(snapshot).orElseThrow();

        assertEquals("ubuntu-x64-cpu", entry.variant());
        assertEquals("b9860", entry.upstreamTag());
        assertEquals("llama-b9860-bin-ubuntu-x64.tar.gz", entry.assetName());
        assertEquals("https://github.com/ggml-org/llama.cpp/releases/download/b9860/llama-b9860-bin-ubuntu-x64.tar.gz",
                entry.url().toString());
        assertEquals("b68e8072eb88d1cc8b8e9d6ea8237aae87b34c6d8bbffda958c870e4dc949714", entry.sha256());
        assertEquals(15_851_454L, entry.sizeBytes());
        assertEquals("llama-server", entry.executableName());
        assertEquals(Path.of(".talos", "engines", "llama.cpp", "b9860", "ubuntu-x64-cpu"),
                entry.installSubdir());
    }

    @Test
    void rejectsNonUbuntuLinuxForFirstMilestone() {
        assertFalse(LlamaCppEngineManifest.select(snapshot("Linux", "amd64", false, "Fedora Linux 42")).isPresent());
    }

    @Test
    void rejectsWindowsForUnixEngineManifest() {
        assertFalse(LlamaCppEngineManifest.select(snapshot("Windows 11", "amd64", false, "")).isPresent());
    }

    @Test
    void rejectsArmForFirstMilestone() {
        assertFalse(LlamaCppEngineManifest.select(snapshot("Linux", "aarch64", true, "Ubuntu 26.04 LTS")).isPresent());
    }

    private static SetupWizardSnapshot snapshot(String os, String arch, boolean wsl, String distro) {
        return new SetupWizardSnapshot(
                os,
                arch,
                wsl,
                distro,
                21,
                Path.of("/home/user/.talos/config.yaml"),
                false,
                null,
                false,
                512_000,
                16_384,
                32_768);
    }
}
