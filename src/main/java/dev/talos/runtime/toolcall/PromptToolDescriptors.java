package dev.talos.runtime.toolcall;

import dev.talos.core.llm.PromptToolDescriptor;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapts executable tool registry metadata into prompt-facing descriptors. */
public final class PromptToolDescriptors {
    private PromptToolDescriptors() {
    }

    public static List<PromptToolDescriptor> fromRegistry(ToolRegistry registry) {
        if (registry == null || registry.isEmpty()) {
            return List.of();
        }
        return registry.descriptors().stream()
                .map(PromptToolDescriptors::fromDescriptor)
                .toList();
    }

    public static List<PromptToolDescriptor> fromRegistry(ToolRegistry registry, List<ToolSpec> visibleSpecs) {
        if (visibleSpecs == null || visibleSpecs.isEmpty()) {
            return List.of();
        }
        Map<String, ToolDescriptor> descriptorsByName = new LinkedHashMap<>();
        if (registry != null && !registry.isEmpty()) {
            for (ToolDescriptor descriptor : registry.descriptors()) {
                descriptorsByName.put(descriptor.name(), descriptor);
            }
        }
        return visibleSpecs.stream()
                .map(spec -> fromSpec(spec, descriptorsByName.get(spec.name())))
                .toList();
    }

    private static PromptToolDescriptor fromDescriptor(ToolDescriptor descriptor) {
        return new PromptToolDescriptor(
                descriptor.name(),
                descriptor.description(),
                descriptor.parametersSchema(),
                descriptor.riskLevel() != null && descriptor.riskLevel().requiresApproval());
    }

    private static PromptToolDescriptor fromSpec(ToolSpec spec, ToolDescriptor descriptor) {
        if (descriptor != null) {
            return fromDescriptor(descriptor);
        }
        return new PromptToolDescriptor(
                spec.name(),
                spec.description(),
                spec.parametersSchemaJson(),
                false);
    }
}
