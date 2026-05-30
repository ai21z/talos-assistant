package dev.talos.runtime.intent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record ArtifactTargetSet(List<TargetRef> targets) {
    public ArtifactTargetSet {
        targets = mergeStrongest(targets);
    }

    public static ArtifactTargetSet empty() {
        return new ArtifactTargetSet(List.of());
    }

    public static ArtifactTargetSet of(TargetRef... refs) {
        return new ArtifactTargetSet(refs == null ? List.of() : Arrays.asList(refs));
    }

    public ArtifactTargetSet with(TargetRef ref) {
        if (ref == null) return this;
        List<TargetRef> combined = new ArrayList<>(targets);
        combined.add(ref);
        return new ArtifactTargetSet(combined);
    }

    public Optional<TargetRef> find(String path) {
        String normalized;
        try {
            normalized = TargetRef.normalizePath(path);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
        return targets.stream()
                .filter(target -> target.path().equals(normalized))
                .findFirst();
    }

    public List<TargetRef> targetsByRole(TargetRole role) {
        if (role == null) return List.of();
        return targets.stream()
                .filter(target -> target.role() == role)
                .toList();
    }

    public Set<String> pathsByRole(TargetRole role) {
        if (role == null) return Set.of();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (TargetRef target : targets) {
            if (target.role() == role) {
                paths.add(target.path());
            }
        }
        return Collections.unmodifiableSet(paths);
    }

    private static List<TargetRef> mergeStrongest(List<TargetRef> refs) {
        if (refs == null || refs.isEmpty()) return List.of();
        Map<String, TargetRef> byPath = new LinkedHashMap<>();
        for (TargetRef ref : refs) {
            if (ref == null) continue;
            TargetRef existing = byPath.get(ref.path());
            if (existing == null || shouldReplace(existing, ref)) {
                byPath.put(ref.path(), ref);
            }
        }
        return List.copyOf(byPath.values());
    }

    private static boolean shouldReplace(TargetRef existing, TargetRef candidate) {
        if (candidate.role().strongerThan(existing.role())) return true;
        if (existing.role().strongerThan(candidate.role())) return false;
        return candidate.derivation().confidence() > existing.derivation().confidence();
    }
}
