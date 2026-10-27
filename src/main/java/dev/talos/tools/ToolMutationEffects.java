package dev.talos.tools;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.UnaryOperator;

/** Observed side effects, independent of whether the requested operation succeeded.
 * Applied does not mean verified; uncertain paths must not be described as unchanged.
 * An empty value is the compatibility default, not a general proof of no mutation.
 */
public record ToolMutationEffects(List<String> appliedPaths, List<String> uncertainPaths) {
    public ToolMutationEffects {
        appliedPaths = normalized(appliedPaths);
        uncertainPaths = normalized(uncertainPaths);
    }

    public static ToolMutationEffects none() { return new ToolMutationEffects(List.of(), List.of()); }
    public static ToolMutationEffects applied(String path) {
        return new ToolMutationEffects(List.of(path), List.of());
    }
    public static ToolMutationEffects uncertain(String path) {
        return new ToolMutationEffects(List.of(), List.of(path));
    }
    public boolean hasEffects() { return !appliedPaths.isEmpty() || !uncertainPaths.isEmpty(); }
    public List<String> affectedPaths() {
        LinkedHashSet<String> paths = new LinkedHashSet<>(appliedPaths);
        paths.addAll(uncertainPaths);
        return List.copyOf(paths);
    }
    public ToolMutationEffects plus(ToolMutationEffects other) {
        if (other == null || !other.hasEffects()) return this;
        java.util.ArrayList<String> applied = new java.util.ArrayList<>(appliedPaths);
        applied.addAll(other.appliedPaths);
        java.util.ArrayList<String> uncertain = new java.util.ArrayList<>(uncertainPaths);
        uncertain.addAll(other.uncertainPaths);
        return new ToolMutationEffects(applied, uncertain);
    }
    public ToolMutationEffects mapPaths(UnaryOperator<String> mapper) {
        return new ToolMutationEffects(appliedPaths.stream().map(mapper).toList(),
                uncertainPaths.stream().map(mapper).toList());
    }
    private static List<String> normalized(List<String> paths) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (paths != null) for (String path : paths) {
            if (path == null || path.isBlank()) continue;
            String value = path.replace('\\', '/');
            while (value.startsWith("./")) value = value.substring(2);
            if (!value.isBlank()) out.add(value);
        }
        return List.copyOf(out);
    }
}
