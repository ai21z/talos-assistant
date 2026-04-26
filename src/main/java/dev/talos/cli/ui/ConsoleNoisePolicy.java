package dev.talos.cli.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Keeps third-party runtime diagnostics out of the normal conversation stream.
 *
 * <p>Talos' own SLF4J/logback output is handled by {@code logback.xml}. Some
 * dependencies, notably Lucene internals, still write through
 * {@link java.util.logging}. Route those diagnostics to a local file instead
 * of letting JUL's default console handler leak into user transcripts.
 */
public final class ConsoleNoisePolicy {
    private static final AtomicBoolean JUL_INSTALLED = new AtomicBoolean(false);

    private ConsoleNoisePolicy() {
    }

    public static void install() {
        installJavaUtilLogging(defaultJulLogPath());
    }

    static Path defaultJulLogPath() {
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".talos", "logs", "talos-jul.log");
    }

    static void installJavaUtilLogging(Path logPath) {
        if (!JUL_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        Logger root = LogManager.getLogManager().getLogger("");
        if (root == null) {
            return;
        }

        removeConsoleHandlers(root);
        root.setLevel(Level.WARNING);

        try {
            installFileHandler(root, logPath);
        } catch (IOException | RuntimeException ignored) {
            // Failing to create a diagnostic log must never reintroduce
            // dependency warnings into the normal terminal transcript.
        }
    }

    private static void removeConsoleHandlers(Logger root) {
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                root.removeHandler(handler);
            }
        }
    }

    private static void installFileHandler(Logger root, Path logPath) throws IOException {
        if (logPath == null) {
            return;
        }
        Path parent = logPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        FileHandler fileHandler = new FileHandler(logPath.toString(), true);
        fileHandler.setLevel(Level.WARNING);
        fileHandler.setFormatter(new SimpleFormatter());
        root.addHandler(fileHandler);
    }
}
