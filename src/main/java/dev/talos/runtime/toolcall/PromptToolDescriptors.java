package dev.talos.runtime.toolcall;

import dev.talos.core.llm.PromptToolDescriptor;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;

import java.util.List;

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

    private static PromptToolDescriptor fromDescriptor(ToolDescriptor descriptor) {
        return new PromptToolDescriptor(
                descriptor.name(),
                descriptor.description(),
                descriptor.parametersSchema(),
                descriptor.riskLevel() != null && descriptor.riskLevel().requiresApproval());
    }
}
