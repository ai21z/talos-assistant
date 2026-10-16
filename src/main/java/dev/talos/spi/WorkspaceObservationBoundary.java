package dev.talos.spi;

import java.io.IOException;
import java.nio.file.Path;

/** Request-owned observation access is supplied explicitly to a tool. */
public interface WorkspaceObservationBoundary {
    enum Action { METADATA, ENUMERATE, CONTENT }
    enum Publication { ALL_NAMES, VISIBLE_NAMES, NO_NAMES }
    record Fields(boolean countPublic, boolean existencePublic, boolean typesPublic, boolean hiddenFactsPublic) {
        public static Fields all() { return new Fields(true, true, true, true); }
    }
    @FunctionalInterface interface IoSupplier<T> { T get() throws IOException; }

    boolean controlled();
    Publication publication();
    default Fields fields() { return Fields.all(); }
    <T> T observe(Action action, Path target, IoSupplier<T> operation) throws IOException;

    static WorkspaceObservationBoundary unrestricted() { return Unrestricted.INSTANCE; }

    enum Unrestricted implements WorkspaceObservationBoundary {
        INSTANCE;
        @Override public boolean controlled() { return false; }
        @Override public Publication publication() { return Publication.ALL_NAMES; }
        @Override public <T> T observe(Action action, Path target, IoSupplier<T> operation) throws IOException {
            return operation.get();
        }
    }
}
