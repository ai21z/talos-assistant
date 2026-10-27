package dev.talos.qualification;

/** Distinguishes an idle-prompt deadline from process exit and other PTY failures. */
final class QualificationPtyIdlePromptTimeoutException extends IllegalStateException {
    QualificationPtyIdlePromptTimeoutException() {
        super("timed out waiting for idle prompt");
    }
}
