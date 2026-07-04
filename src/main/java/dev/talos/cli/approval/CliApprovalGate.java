package dev.talos.cli.approval;

import dev.talos.cli.ui.ApprovalPromptRenderer;
import dev.talos.cli.ui.ApprovalPromptText;
import dev.talos.cli.ui.CliTheme;
import dev.talos.cli.ui.TerminalWidths;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Scanner;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * CLI-based approval gate that prompts the user for confirmation
 * before executing sensitive (WRITE/DESTRUCTIVE) tool operations.
 *
 * <p>Two input strategies:
 * <ol>
 *   <li><strong>JLine / REPL-integrated</strong> (preferred): supply a
 *       {@code Function<String, String>} that maps a prompt string to
 *       the user's response line.  This is typically backed by
 *       {@code lineReader.readLine(prompt)} so that the same terminal
 *       input system is used for normal REPL prompts and approval prompts.
 *   </li>
 *   <li><strong>Scanner / InputStream</strong> (legacy, tests): reads from
 *       a raw {@code InputStream} via {@link Scanner}. Still useful for
 *       unit tests and non-interactive pipelines.
 *   </li>
 * </ol>
 *
 * <p>An optional {@code Runnable prePromptHook} is invoked <em>before</em>
 * the approval prompt is printed. The primary use is stopping the spinner
 * so the user sees a clean approval line instead of a "still thinking"
 * animation.
 *
 * <p>Accepts "y", "yes" (case-insensitive) as approval. Everything else is denial.
 * EOF / null on input is treated as denial.
 */
public final class CliApprovalGate implements ApprovalGate {

    /** Fixed approval-window width for paths without a live terminal (pre-T773 value). */
    private static final int APPROVAL_WINDOW_DEFAULT_WIDTH = 80;

    private final Function<String, String> lineReader;
    private final PrintStream out;
    private final Runnable prePromptHook;
    private final IntSupplier terminalWidth;

    /**
     * Primary constructor: JLine / REPL-integrated.
     *
     * @param lineReader   reads one line of user input for a given prompt string;
     *                     must return {@code null} on EOF
     * @param out          output stream for the approval banner (description + detail);
     *                     the prompt suffix itself (e.g. "Allow? [y/N] ") is passed to
     *                     {@code lineReader} so the terminal can render it atomically
     * @param prePromptHook optional callback invoked before the prompt is shown
     *                      (e.g. stop spinner); may be {@code null}
     */
    public CliApprovalGate(Function<String, String> lineReader, PrintStream out, Runnable prePromptHook) {
        this(lineReader, out, prePromptHook, null);
    }

    /**
     * JLine / REPL-integrated with a live terminal width source (T773).
     * The bordered approval window follows the terminal width (clamped
     * 60-120); the prompt strings themselves are width-independent and
     * stay byte-frozen via {@link dev.talos.cli.ui.ApprovalPromptText}.
     */
    public CliApprovalGate(Function<String, String> lineReader, PrintStream out, Runnable prePromptHook,
                           IntSupplier terminalWidth) {
        this.lineReader = (lineReader != null) ? lineReader : prompt -> null;
        this.out = (out != null) ? out : System.out;
        this.prePromptHook = prePromptHook;
        this.terminalWidth = terminalWidth;
    }

    /**
     * Legacy constructor: Scanner-based (for tests and non-interactive use).
     *
     * @param in  input stream (typically a {@code ByteArrayInputStream} in tests)
     * @param out output stream
     */
    public CliApprovalGate(InputStream in, PrintStream out) {
        final PrintStream effectiveOut = (out != null) ? out : System.out;
        Scanner scanner = new Scanner(in != null ? in : System.in);
        this.lineReader = prompt -> {
            effectiveOut.print(prompt);
            effectiveOut.flush();
            if (!scanner.hasNextLine()) return null;
            return scanner.nextLine();
        };
        this.out = effectiveOut;
        this.prePromptHook = null;
        this.terminalWidth = null;
    }

    /** Default constructor using Scanner on System.in / System.out. */
    public CliApprovalGate() {
        this(System.in, System.out);
    }

    @Override
    public boolean approve(String description, String detail) {
        return approveFull(description, detail).isApproved();
    }

    /**
     * Tri-state approval prompt.
     *
     * <p>Accepts "y" / "yes" for one-time approval, "a" / "all" / "always"
     * for approval with a "remember for this session" flag, and anything
     * else (including EOF) as denial.
     */
    @Override
    public ApprovalResponse approveFull(String description, String detail) {
        // Stop spinner / prepare terminal before showing approval UI
        if (prePromptHook != null) {
            try { prePromptHook.run(); } catch (Exception ignored) { }
        }

        String risk = inferRisk(description, detail);
        out.println();
        out.print(new ApprovalPromptRenderer(CliTheme.current(), windowWidth())
                .render(description, detail, risk));
        out.flush();

        String response;
        try {
            response = lineReader.apply(ApprovalPromptText.SESSION_PROMPT_LINE);
        } catch (Exception e) {
            // JLine EndOfFileException, IOError, etc. → deny
            return ApprovalResponse.DENIED;
        }

        if (response == null) {
            return ApprovalResponse.DENIED; // EOF = deny
        }

        response = response.trim().toLowerCase();
        if ("a".equals(response) || "all".equals(response) || "always".equals(response)) {
            return ApprovalResponse.APPROVED_REMEMBER;
        }
        if ("y".equals(response) || "yes".equals(response)) {
            return ApprovalResponse.APPROVED;
        }
        return ApprovalResponse.DENIED;
    }

    /**
     * One-turn-only approval prompt. Unlike {@link #approveFull(String, String)},
     * this deliberately does not offer or accept a session-remember response.
     */
    @Override
    public ApprovalResponse approveOnce(String description, String detail) {
        if (prePromptHook != null) {
            try { prePromptHook.run(); } catch (Exception ignored) { }
        }

        String risk = inferRisk(description, detail);
        out.println();
        out.print(new ApprovalPromptRenderer(CliTheme.current(), windowWidth())
                .renderOnce(description, detail, risk));
        out.flush();

        String response;
        try {
            response = lineReader.apply(ApprovalPromptText.ONCE_PROMPT_LINE);
        } catch (Exception e) {
            return ApprovalResponse.DENIED;
        }

        if (response == null) {
            return ApprovalResponse.DENIED;
        }

        response = response.trim().toLowerCase();
        if ("y".equals(response) || "yes".equals(response)) {
            return ApprovalResponse.APPROVED;
        }
        return ApprovalResponse.DENIED;
    }

    /**
     * COLUMNS is consulted only when a live terminal exists but cannot report
     * its width - the approval window never read COLUMNS before T773, so
     * redirected/scripted output must not start depending on it.
     */
    private int windowWidth() {
        return TerminalWidths.resolve(
                terminalWidth,
                terminalWidth != null ? System.getenv() : java.util.Map.of(),
                APPROVAL_WINDOW_DEFAULT_WIDTH);
    }

    private static String inferRisk(String description, String detail) {
        String text = ((description == null ? "" : description) + "\n" + riskScanScope(detail))
                .toLowerCase(java.util.Locale.ROOT);
        if (text.contains("protected read")
                || text.contains("sensitive read")
                || text.contains("reading protected path")
                || text.contains("private document model handoff")
                || text.contains("sending extracted document text to model context")) {
            return "sensitive read";
        }
        if (text.contains("delete") || text.contains("destructive") || text.contains("remove")) {
            return "destructive";
        }
        if (text.contains("write") || text.contains("edit") || text.contains("modify") || text.contains("target:")) {
            return "write";
        }
        return "sensitive";
    }

    /**
     * Risk inference must not scan the unified-diff block (T756): diff body
     * lines quote arbitrary file content, and words like "remove" or
     * "delete" inside the user's own code would flip the label to
     * "destructive". The block is the final detail section, opened by the
     * "diff (+" marker line that TurnProcessor appends.
     */
    private static String riskScanScope(String detail) {
        if (detail == null) return "";
        int diffStart = detail.indexOf("\n    diff (+");
        return diffStart >= 0 ? detail.substring(0, diffStart) : detail;
    }
}
