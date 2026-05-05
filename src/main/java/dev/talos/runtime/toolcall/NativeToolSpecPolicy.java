package dev.talos.runtime.toolcall;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.spi.types.ToolSpec;
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
        return ToolSurfacePlanner.plan(contract, phase, registry).nativeToolSpecs();
    }

    public static List<String> names(List<ToolSpec> specs) {
        return ToolSurfacePlanner.names(specs);
    }
}
