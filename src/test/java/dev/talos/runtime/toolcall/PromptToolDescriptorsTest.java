package dev.talos.runtime.toolcall;

import dev.talos.core.llm.PromptToolDescriptor;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.impl.RetrieveTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptToolDescriptorsTest {

    @Test
    void adaptsRegistryDescriptorsIntoPromptDescriptors() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(
                "talos.read_file",
                "Read a file",
                "{\"type\":\"object\"}",
                ToolRiskLevel.READ_ONLY));
        registry.register(tool(
                "talos.write_file",
                "Write a file",
                "{\"type\":\"object\",\"required\":[\"path\"]}",
                ToolRiskLevel.WRITE));

        List<PromptToolDescriptor> descriptors = PromptToolDescriptors.fromRegistry(registry);

        PromptToolDescriptor read = descriptors.stream()
                .filter(descriptor -> "talos.read_file".equals(descriptor.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("Read a file", read.description());
        assertEquals("{\"type\":\"object\"}", read.parametersSchema());
        assertFalse(read.requiresApproval());

        PromptToolDescriptor write = descriptors.stream()
                .filter(descriptor -> "talos.write_file".equals(descriptor.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("Write a file", write.description());
        assertEquals("{\"type\":\"object\",\"required\":[\"path\"]}", write.parametersSchema());
        assertTrue(write.requiresApproval());
    }

    @Test
    void adaptsOnlyThePlannedVisibleSurfaceWhenSpecsAreProvided() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(
                "talos.read_file",
                "Read a file",
                "{\"type\":\"object\"}",
                ToolRiskLevel.READ_ONLY));
        registry.register(new RetrieveTool(null));

        List<PromptToolDescriptor> descriptors = PromptToolDescriptors.fromRegistry(
                registry,
                List.of(new ToolSpec("talos.read_file", "Read a file", "{\"type\":\"object\"}")));

        assertEquals(List.of("talos.read_file"),
                descriptors.stream().map(PromptToolDescriptor::name).toList());
    }

    private static TalosTool tool(
            String name,
            String description,
            String parametersSchema,
            ToolRiskLevel riskLevel) {
        return new TalosTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, description, parametersSchema, riskLevel);
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.ok("stub");
            }
        };
    }
}
