package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StartupBannerRendererTest {
    private static final TerminalCapabilities UNICODE_NO_COLOR =
            new TerminalCapabilities(ColorPolicy.NEVER, true, false, true, false);
    private static final TerminalCapabilities UNICODE_COLOR =
            new TerminalCapabilities(ColorPolicy.ALWAYS, true, true, true, false);
    private static final TerminalCapabilities ASCII_NO_COLOR =
            new TerminalCapabilities(ColorPolicy.NEVER, false, false, false, true);

    @Test
    void startupWithIcon_matchesCandidateBGoldenAt80Columns() throws Exception {
        assertEquals(
                golden("startup-80-unicode.txt"),
                StartupBannerRenderer.render(sample(), UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON));
    }

    @Test
    void unicodeSafeDefaultUsesSafeIconWithoutExtendedGlyphs() {
        String rendered = StartupBannerRenderer.render(
                sample(),
                UNICODE_NO_COLOR,
                80,
                StartupBannerRenderer.Variant.STARTUP_WITH_ICON,
                Map.of());

        assertTrue(rendered.contains("│ ████████    TALOS"));
        assertFalse(rendered.matches("(?s).*[▟▙◞◄◅▶◀].*"), rendered);
    }

    @Test
    void unicodeSafeExtendedOverrideUsesExtendedIcon() {
        String rendered = StartupBannerRenderer.render(
                sample(),
                UNICODE_NO_COLOR,
                80,
                StartupBannerRenderer.Variant.STARTUP_WITH_ICON,
                Map.of("TALOS_GLYPHS", "extended"));

        assertTrue(rendered.contains("▟███▀▀███▙"));
        assertTrue(rendered.contains("  ▶    ◀  "));
    }

    @Test
    void unicodeSafeAsciiOverrideUsesAsciiRenderer() {
        String rendered = StartupBannerRenderer.render(
                sample(),
                UNICODE_NO_COLOR,
                80,
                StartupBannerRenderer.Variant.STARTUP_WITH_ICON,
                Map.of("TALOS_GLYPHS", "ascii"));

        assertTrue(rendered.startsWith("+------------------------------------------------------------------------------+\n"));
        assertTrue(rendered.contains("| TALOS  v0.9.9-beta"));
        assertFalse(rendered.contains("┌"));
        assertFalse(rendered.contains("████████"));
    }

    @Test
    void nonUnicodeSafeUsesAsciiRendererEvenWhenExtendedRequested() {
        String rendered = StartupBannerRenderer.render(
                sample(),
                ASCII_NO_COLOR,
                80,
                StartupBannerRenderer.Variant.STARTUP_WITH_ICON,
                Map.of("TALOS_GLYPHS", "extended"));

        assertTrue(rendered.startsWith("+------------------------------------------------------------------------------+\n"));
        assertFalse(rendered.contains("▟███▀▀███▙"));
        assertFalse(rendered.contains("████████"));
    }

    @Test
    void winkAnimationIsDisabledUnlessExtendedGlyphModeIsExplicit() {
        assertFalse(StartupBannerRenderer.shouldAnimate(UNICODE_COLOR, Map.of(), true));
        assertFalse(StartupBannerRenderer.shouldAnimate(UNICODE_COLOR, Map.of("TALOS_GLYPHS", "safe"), true));
        assertTrue(StartupBannerRenderer.shouldAnimate(UNICODE_COLOR, Map.of("TALOS_GLYPHS", "extended"), true));
        assertFalse(StartupBannerRenderer.shouldAnimate(UNICODE_COLOR, Map.of("TALOS_GLYPHS", "extended"), false));
    }

    @Test
    void wouldRenderIconTracksGlyphModeAndWidth() {
        assertTrue(StartupBannerRenderer.wouldRenderIcon(
                UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON, Map.of()));
        assertTrue(StartupBannerRenderer.wouldRenderIcon(
                UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON, Map.of("TALOS_GLYPHS", "extended")));
        assertFalse(StartupBannerRenderer.wouldRenderIcon(
                UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON, Map.of("TALOS_GLYPHS", "ascii")));
        assertFalse(StartupBannerRenderer.wouldRenderIcon(
                UNICODE_NO_COLOR, 69, StartupBannerRenderer.Variant.STARTUP_WITH_ICON, Map.of()));
    }

    @Test
    void startupWithBuildingIndex_matchesGoldenAt80Columns() throws Exception {
        assertEquals(
                golden("startup-80-building.txt"),
                StartupBannerRenderer.render(sample("auto", "building · 4,210/12,418", "ask before mutation", "brief"),
                        UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON));
    }

    @Test
    void startupWithWarningAndDebugTrace_matchesGoldenAt80Columns() throws Exception {
        CliStatusDashboard.Snapshot snapshot = new CliStatusDashboard.Snapshot(
                "0.9.9-beta",
                "C:\\...\\Projects\\LOQ\\loqj-cli",
                "dev",
                "qwen2.5-coder:14b-instruct-q...",
                "llama.cpp (managed)",
                "stale · rebuild advised",
                "writes require approval",
                "trace",
                "governed edits · writes require approval");

        assertEquals(
                golden("startup-80-warning-debug.txt"),
                StartupBannerRenderer.render(snapshot, UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON));
    }

    @Test
    void statusNoIcon_matchesGoldenAt80Columns() throws Exception {
        assertEquals(
                golden("status-80-no-icon.txt"),
                StartupBannerRenderer.render(sample(), UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STATUS_NO_ICON));
    }

    @Test
    void compactNoIcon_matchesGoldenAt60Columns() throws Exception {
        assertEquals(
                golden("compact-60-no-icon.txt"),
                StartupBannerRenderer.render(sample(), UNICODE_NO_COLOR, 60, StartupBannerRenderer.Variant.COMPACT_NO_ICON));
    }

    @Test
    void asciiFallback_matchesGoldenAt80ColumnsAndDropsIcon() throws Exception {
        assertEquals(
                golden("ascii-80-fallback.txt"),
                StartupBannerRenderer.render(sample(), ASCII_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON));
    }

    @Test
    void startupWithIcon_usesWindowsSafeSingleWeightUnicodeFrameWhenUnicodeSafe() {
        String rendered = StartupBannerRenderer.render(sample(), UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertTrue(rendered.contains("┌─────────────────────────┬"));
        assertTrue(rendered.contains("├─────────────────────────┴"));
        assertFalse(rendered.contains("+"));
    }

    @Test
    void asciiFallback_dropsInnerSplitBecausePlusJunctionsAreAmbiguous() {
        String rendered = StartupBannerRenderer.render(sample(), ASCII_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertFalse(rendered.contains("| TALOS         | Workspace"));
        assertFalse(rendered.contains("+-------------------------+"));
        assertTrue(rendered.contains("| TALOS  v0.9.9-beta"));
    }

    @Test
    void noColorCapabilitiesEmitNoAnsiSequences() {
        String rendered = StartupBannerRenderer.render(sample(), UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertFalse(rendered.contains("\033["));
    }

    @Test
    void colorCapabilitiesUseLockedBronzeAndFrameGrey() {
        String rendered = StartupBannerRenderer.render(sample(), UNICODE_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertTrue(rendered.contains("\033[38;2;167;123;58m████████  \033[0m"));
        assertTrue(rendered.contains("\033[38;2;167;123;58mTALOS\033[0m"));
        assertTrue(rendered.contains("\033[38;2;90;90;90m┌"));
    }

    @Test
    void colorCapabilitiesReserveCyanForBuildingIndexOnlyInsideBanner() {
        String rendered = StartupBannerRenderer.render(
                sample("auto", "building · 4,210/12,418", "ask before mutation", "brief"),
                UNICODE_COLOR,
                80,
                StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertTrue(rendered.contains("\033[38;2;95;175;215mbuilding · 4,210/12,418"));
        assertFalse(rendered.contains("\033[38;2;95;175;215mTALOS"));
    }

    @Test
    void widthBelow70FallsBackToCompactNoSplit() {
        String rendered = StartupBannerRenderer.render(sample(), UNICODE_NO_COLOR, 69, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertFalse(rendered.contains("┬"));
        assertFalse(rendered.contains("████████"));
        assertTrue(rendered.contains("TALOS v0.9.9-beta"));
    }

    @Test
    void widthBelow50FallsBackToPlainHeader() {
        String rendered = StartupBannerRenderer.render(sample(), UNICODE_NO_COLOR, 49, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertFalse(rendered.contains("┌"));
        assertTrue(rendered.startsWith("TALOS v0.9.9-beta\n"));
        assertTrue(rendered.contains("workspace  ~/projects/talos-cli\n"));
    }

    @Test
    void width100KeepsLeftPanelFixedAndWidensRuntimePanel() {
        String rendered = StartupBannerRenderer.render(sample(), UNICODE_NO_COLOR, 100, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);
        String firstLine = rendered.lines().findFirst().orElseThrow();

        assertEquals(100, firstLine.length());
        assertTrue(firstLine.startsWith("┌─────────────────────────┬"));
    }

    @Test
    void longWorkspaceMiddleTruncatesBeforeBreakingFrame() {
        CliStatusDashboard.Snapshot snapshot = new CliStatusDashboard.Snapshot(
                "0.9.9-beta",
                "C:\\Users\\arisz\\Projects\\LOQ\\loqj-cli\\src\\main\\java\\dev\\talos\\cli",
                "auto",
                "qwen2.5-coder:14b",
                "llama.cpp (managed)",
                "ready · 12,418 chunks",
                "ask before mutation",
                "off",
                "ready · type /help, /status, /tools · or ask a question");

        String rendered = StartupBannerRenderer.render(snapshot, UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertTrue(rendered.contains("C:\\...\\dev\\talos\\cli"));
        assertTrue(rendered.lines().filter(line -> !line.isEmpty()).allMatch(line -> line.length() == 80));
    }

    @Test
    void longIndexDropsChunkCountBeforeFrameOverflow() {
        String rendered = StartupBannerRenderer.render(
                sample("auto", "ready · 12,418 chunks with extra metadata", "ask before mutation", "off"),
                UNICODE_NO_COLOR,
                80,
                StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertTrue(rendered.contains("Index       ready"));
        assertFalse(rendered.contains("extra metadata"));
    }

    @Test
    void readModeUsesReadOnlyHint() {
        String rendered = StartupBannerRenderer.render(sample("read", "ready · 12,418 chunks", "ask before mutation", "off"),
                UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertTrue(rendered.contains("read-only · ask about files or use /help"));
    }

    @Test
    void devModeUsesGovernedEditsHint() {
        String rendered = StartupBannerRenderer.render(sample("dev", "ready · 12,418 chunks", "writes require approval", "off"),
                UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertTrue(rendered.contains("governed edits · writes require approval"));
    }

    @Test
    void debugModeUsesTraceHint() {
        String rendered = StartupBannerRenderer.render(sample("debug", "ready · 12,418 chunks", "ask before mutation", "trace"),
                UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertTrue(rendered.contains("debug on · use /last trace or /prompt-debug last"));
    }

    @Test
    void rendererSanitizesControlCharactersAndAnsiFromRuntimeValues() {
        CliStatusDashboard.Snapshot snapshot = new CliStatusDashboard.Snapshot(
                "0.9.9-beta\u001B[31m",
                "~/projects/\u001B[31msecret\u0007",
                "auto",
                "model",
                "engine",
                "ready",
                "ask before mutation",
                "off",
                "ready · type /help");

        String rendered = StartupBannerRenderer.render(snapshot, UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STATUS_NO_ICON);

        assertFalse(rendered.contains("\u001B"));
        assertFalse(rendered.contains("\u0007"));
        assertTrue(rendered.contains("~/projects/secret"));
    }

    @Test
    void cliStatusDashboardRenderCanUseStatusNoIconRenderer() {
        String rendered = CliStatusDashboard.render(sample(), UNICODE_NO_COLOR, 80);

        assertFalse(rendered.contains("████████"));
        assertTrue(rendered.contains("TALOS"));
        assertTrue(rendered.contains("Policy  ask before mutation"));
    }

    @Test
    void unicodeRendererAvoidsGlyphsThatWindowsConsoleOftenReplacesWithQuestionMarks() {
        CliStatusDashboard.Snapshot snapshot = new CliStatusDashboard.Snapshot(
                "0.9.9-beta",
                "C:\\Users\\arisz\\Projects\\LOQ\\loqj-cli\\src\\main\\java\\dev\\talos\\cli",
                "auto",
                "llama_cpp/gpt-oss-20b-with-extra-runtime-suffix",
                "llama.cpp (managed)",
                "building · 4,210/12,418",
                "ask before mutation",
                "off",
                "ready · type /help, /status, /tools · or ask a question");

        String rendered = StartupBannerRenderer.render(snapshot, UNICODE_NO_COLOR, 80, StartupBannerRenderer.Variant.STARTUP_WITH_ICON);

        assertFalse(rendered.matches("(?s).*[╭╮╰╯▛▜—…▟▙◞◄◅].*"), rendered);
    }

    private static CliStatusDashboard.Snapshot sample() {
        return sample("auto", "ready · 12,418 chunks", "ask before mutation", "off");
    }

    private static CliStatusDashboard.Snapshot sample(String mode, String index, String policy, String debug) {
        return new CliStatusDashboard.Snapshot(
                "0.9.9-beta",
                "~/projects/talos-cli",
                mode,
                "qwen2.5-coder:14b",
                "llama.cpp (managed)",
                index,
                policy,
                debug,
                "ready · type /help, /status, /tools · or ask a question");
    }

    private static String golden(String name) throws IOException {
        try (var in = StartupBannerRendererTest.class.getResourceAsStream("/dev/talos/cli/banner/" + name)) {
            assertNotNull(in, "missing golden resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
