package dev.talos.cli.approval;

import dev.talos.cli.ui.ApprovalPromptRenderer;
import dev.talos.cli.ui.CliTheme;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Scanner;
import java.util.function.Function;

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

    private final Function<String, String> lineReader;
    private final PrintStream out;
    private final Runnable prePromptHook;

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
        this.lineReader = (lineReader != null) ? lineReader : prompt -> null;
        this.out = (out != null) ? out : System.out;
        this.prePromptHook = prePromptHook;
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
        out.print(new ApprovalPromptRenderer(CliTheme.current(), 80)
                .render(description, detail, risk));
        out.flush();

        String response;
        try {
            response = lineReader.apply("  Allow? [y=yes, a=yes for session, N=no] ");
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
        out.print(new ApprovalPromptRenderer(CliTheme.current(), 80)
                .renderOnce(description, detail, risk));
        out.flush();

        String response;
        try {
            response = lineReader.apply("  Allow? [y=yes, N=no] ");
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

    private static String inferRisk(String description, String detail) {
        String text = ((description == null ? "" : description) + "\n" + (detail == null ? "" : detail))
                .toLowerCase(java.util.Locale.ROOT);
        if (text.contains("protected read")
                || text.contains("sensitive read")
                || text.contains("reading protected path")) {
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
}
