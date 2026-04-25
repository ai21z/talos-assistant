package dev.talos.cli.prompt;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class LastPromptCapture {
    private static final AtomicReference<PromptRender> LAST = new AtomicReference<>();

    private LastPromptCapture() {}

    public static void record(PromptRender render) {
        if (render != null) LAST.set(render);
    }

    public static Optional<PromptRender> latest() {
        return Optional.ofNullable(LAST.get());
    }

    public static void clear() {
        LAST.set(null);
    }
}
