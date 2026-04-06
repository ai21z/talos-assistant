package dev.talos.runtime;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Scanner;

/**
 * CLI-based approval gate that prompts the user for confirmation
 * before executing sensitive (WRITE/DESTRUCTIVE) tool operations.
 *
 * <p>Reads from the provided input stream (typically {@code System.in})
 * and writes the prompt to the provided output stream (typically {@code System.out}).
 *
 * <p>Accepts "y", "yes" (case-insensitive) as approval. Everything else is denial.
 * EOF on input is treated as denial.
 */
public final class CliApprovalGate implements ApprovalGate {

    private final Scanner scanner;
    private final PrintStream out;

    public CliApprovalGate(InputStream in, PrintStream out) {
        this.scanner = new Scanner(in != null ? in : System.in);
        this.out = (out != null) ? out : System.out;
    }

    /** Default constructor using System.in / System.out. */
    public CliApprovalGate() {
        this(System.in, System.out);
    }

    @Override
    public boolean approve(String description, String detail) {
        out.println();
        out.println("  ⚠ Approval required: " + (description != null ? description : "unknown operation"));
        if (detail != null && !detail.isBlank()) {
            out.println("    " + detail);
        }
        out.print("  Allow? [y/N] ");
        out.flush();

        if (!scanner.hasNextLine()) {
            return false; // EOF = deny
        }

        String response = scanner.nextLine().trim().toLowerCase();
        return "y".equals(response) || "yes".equals(response);
    }
}

