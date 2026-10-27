package dev.talos.cli.ui;

import dev.talos.spi.types.ModelIdentitySnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared presentation rules for the bounded runtime model identity decision. */
public final class ModelIdentityPresentation {
    private ModelIdentityPresentation() {}

    public record Row(String label, String value) {}

    public static List<Row> statusRows(ModelIdentitySnapshot identity) {
        if (identity == null || !identity.available()) return List.of();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Configured", value(identity.configuredModel(), "not configured")));
        if (!identity.configuredModelFile().isBlank()) {
            rows.add(new Row("Config file", identity.configuredModelFile()));
        }
        if (!identity.declaredProfile().isBlank()) {
            rows.add(new Row("Declared", identity.declaredProfile()));
        }
        rows.add(new Row("Resolved", value(identity.resolvedProfile(), "not qualified")));
        rows.add(new Row("Artifact", evidence(
                lower(identity.artifactState()),
                "verification",
                identity.artifactVerificationIdentity())));
        String serving = value(identity.observedModelFile(), "not observed")
                + ", " + value(lower(identity.servedIdentityState()), "unverified");
        rows.add(new Row("Serving", serving));
        rows.add(new Row("Tool mode", value(lower(identity.effectiveToolMode()), "chat-only")));
        if (!identity.effectiveRequestLayout().isBlank()) {
            rows.add(new Row("Request layout", lower(identity.effectiveRequestLayout())));
        }
        if (identity.effectiveContextTokens() > 0) {
            rows.add(new Row("Resolved ctx", Integer.toString(identity.effectiveContextTokens())));
        }
        rows.add(new Row("Engine lane", evidence(
                value(identity.engineLane(), "unverified"),
                "executable",
                identity.engineExecutableIdentity())));
        if (!identity.disposition().isBlank()) {
            rows.add(new Row("Disposition", identity.disposition()));
        }
        rows.add(new Row("Resolution", value(lower(identity.resolution()), "unverified")));
        rows.add(new Row("Props", evidence(
                value(lower(identity.propsState()), "unavailable"),
                "response",
                identity.propsResponseSha256())));
        if (!identity.reasonCodes().isEmpty()) {
            rows.add(new Row("Downgrade", identity.reasonCodes().stream()
                    .map(ModelIdentityPresentation::reason)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("")));
        }
        return List.copyOf(rows);
    }

    public static List<String> activeModelNotes(
            ModelIdentitySnapshot identity,
            String backend,
            String model) {
        if (identity == null || !identity.available()) return List.of();
        if (!identity.backend().equalsIgnoreCase(value(backend, ""))) return List.of();
        if (!identity.configuredModel().equalsIgnoreCase(value(model, ""))) return List.of();
        List<String> notes = new ArrayList<>();
        notes.add("active");
        notes.add("resolution " + value(lower(identity.resolution()), "unverified"));
        notes.add("tools " + value(lower(identity.effectiveToolMode()), "chat-only"));
        if (!identity.observedModelFile().isBlank()) {
            notes.add("serving " + identity.observedModelFile());
        }
        return List.copyOf(notes);
    }

    public static String bannerModelLabel(ModelIdentitySnapshot identity, String fallback) {
        if (identity == null || !identity.available()) return value(fallback, "unknown");
        if (identity.configuredModel().isBlank()) return value(fallback, "unknown");
        String model = identity.configuredModel();
        if (identity.qualified()) return model;
        return model + " (" + value(lower(identity.resolution()), "unverified") + ")";
    }

    private static String evidence(String state, String label, String hash) {
        String value = value(state, "unverified");
        return hash == null || hash.isBlank()
                ? value
                : value + ", " + label + " " + hash.substring(0, Math.min(12, hash.length()));
    }

    private static String reason(String raw) {
        return value(raw, "").toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String lower(String raw) {
        return value(raw, "").toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String value(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw;
    }
}
