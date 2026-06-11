package dev.talos.cli.ui;

/**
 * Incremental transformer between sanitized streamed answer text and the
 * answer-pane stream (T776/T777). Implementations receive chunks at
 * arbitrary boundaries and return pane-ready text (possibly empty while
 * buffering); {@link #flush()} drains any remainder at stream close
 * without inventing a trailing newline.
 */
public interface StreamShaper {

    String accept(String chunk);

    String flush();
}
