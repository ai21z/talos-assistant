package dev.talos.spi.types;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record BackendSpec(
        String id,
        Path workDir,
        String executable,
        List<String> args,
        Map<String,String> env
) {}
