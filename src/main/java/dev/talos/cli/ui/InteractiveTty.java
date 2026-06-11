package dev.talos.cli.ui;

import org.jline.nativ.CLibrary;
import org.jline.nativ.Kernel32;
import org.jline.utils.OSUtils;

import java.io.Console;
import java.lang.reflect.Method;

/**
 * Interactive-terminal detection that survives JDK 22+ (T769).
 *
 * <p>Talos historically used {@code System.console() != null} as the
 * interactivity check. That breaks on JDK 22+: JLine's bundled console
 * provider and JDK 22's console changes make {@code System.console()}
 * return a non-null console even when stdout is piped or redirected, so a
 * console-null check silently reports "interactive" for redirected runs —
 * flooding piped output with spinner carriage returns and ANSI.
 *
 * <p>The authoritative check is the OS-level {@code isatty(fd)} probe via
 * JLine's bundled JNI natives (the same probe {@code RunCmd} already used
 * for terminal selection). The console-based check survives only as the
 * fallback for exotic launchers where the natives cannot load, hardened
 * with the JDK 22 {@code Console.isTerminal()} disambiguator (looked up
 * reflectively — this codebase compiles on JDK 21).
 */
public final class InteractiveTty {

    /** True when stdin (fd 0) is connected to a real terminal. */
    public static boolean stdinIsTty() {
        return fileDescriptorIsTerminal(0);
    }

    /** True when stdout (fd 1) is connected to a real terminal. */
    public static boolean stdoutIsTty() {
        return fileDescriptorIsTerminal(1);
    }

    /** OS-level isatty probe with console fallback when natives are unavailable. */
    public static boolean fileDescriptorIsTerminal(int fd) {
        try {
            if (OSUtils.IS_WINDOWS) {
                return Kernel32.isatty(fd) != 0;
            }
            return CLibrary.isatty(fd) != 0;
        } catch (Throwable nativesUnavailable) {
            Console console = System.console();
            return consoleFallbackDecision(console != null, consoleIsTerminal(console));
        }
    }

    /**
     * Pure fallback decision, separated for testability.
     *
     * @param consoleNonNull    whether {@code System.console()} returned a console
     * @param consoleIsTerminal {@code Console.isTerminal()} result, or {@code null}
     *                          when the method does not exist (JDK ≤ 21) or failed
     */
    static boolean consoleFallbackDecision(boolean consoleNonNull, Boolean consoleIsTerminal) {
        if (!consoleNonNull) {
            // JDK ≤ 21: a null console reliably means redirected/piped.
            return false;
        }
        // JDK 22+: a console exists even when redirected; isTerminal() is the
        // real signal. On JDK ≤ 21 (no such method) a non-null console still
        // implies a real terminal.
        return consoleIsTerminal == null || consoleIsTerminal;
    }

    /** Reflective {@code Console.isTerminal()} (JDK 22+); null when unavailable. */
    static Boolean consoleIsTerminal(Console console) {
        if (console == null) {
            return null;
        }
        try {
            Method isTerminal = Console.class.getMethod("isTerminal");
            Object result = isTerminal.invoke(console);
            return result instanceof Boolean b ? b : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private InteractiveTty() {
    }
}
