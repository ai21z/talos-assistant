package dev.talos.cli.ui;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp;
import org.jline.utils.Status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JLine Status bottom-row presenter (T779).
 *
 * <p>Replaces the {@code \r} spinner thread for capable terminals: the
 * status row lives in a JLine-managed scroll region below the output, so it
 * never interleaves raw carriage returns with answer streaming and never
 * desynchronizes JLine's cursor model (the T774 single-writer discipline —
 * all status drawing goes through {@link Status}, which writes through the
 * terminal itself).
 *
 * <p>{@code Status.supported} is a protected field with no accessor, so
 * {@link #supports(Terminal)} mirrors its capability requirements; on
 * unsupported terminals (dumb, legacy consoles without scroll regions) the
 * caller keeps the legacy {@code \r} spinner. Content is renderer-owned:
 * model text never reaches this row.
 */
public final class StatusRowPresenter implements AutoCloseable {

    private static final long FRAME_MILLIS = 120;
    private static final String[] FRAMES_UNICODE = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String[] FRAMES_ASCII = {"|", "/", "-", "\\"};

    private final Terminal terminal;
    private final CliTheme theme;
    private final String[] frames;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicInteger frame = new AtomicInteger();
    private final Object monitor = new Object();
    private Thread ticker;
    private volatile Instant startedAt;
    private volatile String label = "";

    public StatusRowPresenter(Terminal terminal, CliTheme theme) {
        this.terminal = terminal;
        this.theme = theme == null ? CliTheme.current() : theme;
        this.frames = this.theme.capabilities().unicodeSafe() ? FRAMES_UNICODE : FRAMES_ASCII;
    }

    /** Mirrors the capability set JLine's protected {@code Status.supported} requires. */
    public static boolean supports(Terminal terminal) {
        if (terminal == null) return false;
        try {
            return terminal.getStringCapability(InfoCmp.Capability.change_scroll_region) != null
                    && terminal.getStringCapability(InfoCmp.Capability.save_cursor) != null
                    && terminal.getStringCapability(InfoCmp.Capability.restore_cursor) != null
                    && terminal.getStringCapability(InfoCmp.Capability.cursor_address) != null;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public boolean supported() {
        return supports(terminal);
    }

    /** Starts the ticking row; no-op when unsupported or already running. */
    public void start(String statusLabel) {
        if (!supported()) return;
        if (!active.compareAndSet(false, true)) return;
        this.label = statusLabel == null ? "" : statusLabel;
        this.startedAt = Instant.now();
        ticker = new Thread(this::run, "talos-status-row");
        ticker.setDaemon(true);
        ticker.start();
    }

    /** Clears the row and stops the ticker; idempotent. */
    public void stop() {
        if (!active.compareAndSet(true, false)) return;
        synchronized (monitor) {
            monitor.notifyAll();
        }
        if (ticker != null) {
            try {
                ticker.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ticker = null;
        }
    }

    /** Stops and tears the JLine status region down (process shutdown). */
    @Override
    public void close() {
        stop();
        if (terminal == null) return;
        try {
            Status.getExistingStatus(terminal).ifPresent(Status::close);
        } catch (RuntimeException ignored) {
        }
    }

    private void run() {
        Status status = Status.getStatus(terminal);
        try {
            while (active.get()) {
                status.update(List.of(buildRow()));
                synchronized (monitor) {
                    try {
                        monitor.wait(FRAME_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            status.update(List.of());
            status.hide();
        }
    }

    private AttributedString buildRow() {
        String glyph = frames[frame.getAndIncrement() % frames.length];
        long secs = startedAt.until(Instant.now(), ChronoUnit.SECONDS);
        String elapsed = secs < 60
                ? secs + "s"
                : String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60);
        return AttributedString.fromAnsi(
                "  " + theme.active(glyph) + " " + theme.metadata(label) + "  " + theme.muted(elapsed));
    }
}
