package dev.loqj.cli.commands;

import java.util.List;

public record CommandSpec(
        String name,
        List<String> aliases,
        String usage,
        String summary
) { }
