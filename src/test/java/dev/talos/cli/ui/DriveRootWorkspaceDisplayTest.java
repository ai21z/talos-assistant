package dev.talos.cli.ui;

import dev.talos.cli.CliUtil;
import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A drive or filesystem root is a valid workspace and is shown by its root text. */
class DriveRootWorkspaceDisplayTest {
    private static final Path ROOT = Path.of("").toAbsolutePath().getRoot();

    @Test
    void rootIsShownByItsRootText() {
        assertEquals(ROOT.toString(), CliUtil.shortenPath(ROOT));
    }

    @Test
    void dashboardSnapshotAcceptsARootWorkspace() {
        var snapshot = CliStatusDashboard.snapshot(ROOT, new Config(), "auto", "model", "off", "next");

        assertEquals(ROOT.toString(), snapshot.workspace());
    }

    @Test
    void compactBannerAcceptsARootWorkspace() {
        var bytes = new ByteArrayOutputStream();
        TalosBanner.printCompact(ROOT, new Config(), "auto", new PrintStream(bytes, true, StandardCharsets.UTF_8));

        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("TALOS"));
    }
}
