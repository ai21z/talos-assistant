package dev.talos.cli.ui;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * T779 lifecycle contracts. The capable-terminal rendering path needs a real
 * scroll-region terminal and is owner-verified in the manual PTY cycle; these
 * tests pin the fallback decision and lifecycle idempotence.
 */
class StatusRowPresenterTest {

    private static Terminal dumbTerminal(ByteArrayOutputStream sink) throws Exception {
        return TerminalBuilder.builder()
                .system(false)
                .dumb(true)
                .encoding(StandardCharsets.UTF_8)
                .streams(new ByteArrayInputStream(new byte[0]), sink)
                .build();
    }

    @Test
    void nullTerminalIsNeverSupported() {
        assertFalse(StatusRowPresenter.supports(null));
    }

    @Test
    void dumbTerminalIsNotSupportedAndStartIsANoOp() throws Exception {
        var sink = new ByteArrayOutputStream();
        try (Terminal terminal = dumbTerminal(sink)) {
            var presenter = new StatusRowPresenter(terminal, CliTheme.current());
            assertFalse(presenter.supported(),
                    "dumb terminals lack scroll regions; the legacy spinner must be used");

            presenter.start("Answering…");
            presenter.stop();
            terminal.flush();

            assertEquals("", sink.toString(StandardCharsets.UTF_8),
                    "an unsupported presenter must write nothing");
        }
    }

    @Test
    void rowCarriesRouteModelAndTurnContext() throws Exception {
        try (Terminal terminal = dumbTerminal(new ByteArrayOutputStream())) {
            var presenter = new StatusRowPresenter(terminal, CliTheme.current());
            presenter.route("unified");
            presenter.context(() -> "qwen2.5-coder:14b", () -> 3);

            String row = presenter.buildRow().toString(); // AttributedString#toString is the plain text

            org.junit.jupiter.api.Assertions.assertTrue(row.contains("route unified"), row);
            org.junit.jupiter.api.Assertions.assertTrue(row.contains("qwen2.5-coder:14b"), row);
            org.junit.jupiter.api.Assertions.assertTrue(row.contains("turn 3"), row);
        }
    }

    @Test
    void rowTruncatesToTheTerminalWidth() throws Exception {
        try (Terminal terminal = dumbTerminal(new ByteArrayOutputStream())) {
            var presenter = new StatusRowPresenter(terminal, CliTheme.current());
            presenter.route("x".repeat(300));
            presenter.context(() -> "y".repeat(100), () -> 12);

            int columns = presenter.buildRow().columnLength();

            org.junit.jupiter.api.Assertions.assertTrue(columns < Math.max(80, terminal.getWidth()),
                    "row must truncate below the terminal width, was " + columns);
        }
    }

    @Test
    void brokenContextSuppliersDegradeSilently() throws Exception {
        try (Terminal terminal = dumbTerminal(new ByteArrayOutputStream())) {
            var presenter = new StatusRowPresenter(terminal, CliTheme.current());
            presenter.context(
                    () -> { throw new IllegalStateException("model gone"); },
                    () -> { throw new IllegalStateException("turn gone"); });

            String row = presenter.buildRow().toString();

            org.junit.jupiter.api.Assertions.assertFalse(row.contains("turn"), row);
        }
    }

    @Test
    void lifecycleIsIdempotent() throws Exception {
        var sink = new ByteArrayOutputStream();
        try (Terminal terminal = dumbTerminal(sink)) {
            var presenter = new StatusRowPresenter(terminal, CliTheme.current());
            presenter.stop();           // stop before start
            presenter.start("a");       // no-op (unsupported)
            presenter.start("b");       // double start
            presenter.stop();
            presenter.stop();           // double stop
            presenter.close();
            presenter.close();          // double close
        }
    }
}
