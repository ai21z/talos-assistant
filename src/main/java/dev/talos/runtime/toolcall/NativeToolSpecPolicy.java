package dev.talos.runtime.toolcall;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;

import java.util.List;

/** Selects the native tool surface advertised to the model for one turn. */
public final class NativeToolSpecPolicy {

    private NativeToolSpecPolicy() {}

    public static List<ToolSpec> select(
            TaskContract contract,
            ExecutionPhase phase,
            ToolRegistry registry
    ) {
        if (registry == null || registry.isEmpty()) return List.of();

        boolean mutationAllowed = contract != null
                && contract.mutationAllowed()
                && phase == ExecutionPhase.APPLY;

        return registry.descriptors().stream()
                .filter(descriptor -> mutationAllowed || isReadOnly(descriptor))
                .map(NativeToolSpecPolicy::toSpec)
                .toList();
    }

    public static List<String> names(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();
        return specs.stream()
                .map(ToolSpec::name)
                .sorted()
                .toList();
    }

    private static boolean isReadOnly(ToolDescriptor descriptor) {
        return descriptor != null
                && descriptor.riskLevel() != null
                && !descriptor.riskLevel().requiresApproval();
    }

    private static ToolSpec toSpec(ToolDescriptor descriptor) {
        return new ToolSpec(
                descriptor.name(),
                descriptor.description(),
                descriptor.parametersSchema());
    }
}
