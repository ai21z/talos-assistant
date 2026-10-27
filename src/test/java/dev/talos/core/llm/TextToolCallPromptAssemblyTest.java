package dev.talos.core.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TextToolCallPromptAssemblyTest {
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";
    private static final List<PromptToolDescriptor> TOOLS = List.of(
            tool("talos.list_dir", false), tool("talos.read_file", false),
            tool("talos.retrieve", false), tool("talos.write_file", true),
            tool("talos.run_command", true));

    static Stream<Arguments> assemblies() {
        return Stream.of("listing", "verification", "read-only", "unified-inspection", "general", "resource")
                .flatMap(branch -> Stream.of(false, true).flatMap(nativeTools ->
                        Stream.of("available", "format-missing", "preamble-missing", "all-missing")
                                .map(resources -> Arguments.of(branch, nativeTools, resources))));
    }

    @ParameterizedTest(name = "{0}, native={1}, resources={2}")
    @MethodSource("assemblies")
    void actualAssemblyKeepsFormatAndVisibleSurfaceAligned(String branch, boolean nativeTools, String resources)
            throws Exception {
        Class<?> type = builderClass(resources);
        Object builder = type.getMethod(branch.equals("unified-inspection") ? "forUnified" : "forAsk").invoke(null);
        List<PromptToolDescriptor> tools = branch.equals("general")
                ? TOOLS.stream().filter(tool -> !tool.name().equals("talos.retrieve")).toList() : TOOLS;
        type.getMethod("withPromptTools", List.class).invoke(builder, tools);
        type.getMethod("withNativeTools", boolean.class).invoke(builder, nativeTools);
        if (branch.equals("listing")) {
            type.getMethod("withDirectoryListingToolMode", boolean.class).invoke(builder, true);
        } else if (branch.equals("read-only") || branch.equals("verification")) {
            type.getMethod("withReadOnlyToolMode", boolean.class).invoke(builder, true);
            type.getMethod("withCommandToolMode", boolean.class).invoke(builder, branch.equals("verification"));
        } else if (branch.equals("unified-inspection")) {
            type.getMethod("withVisibleToolNames", List.class).invoke(builder, List.of("talos.read_file"));
        }
        String prompt = ((String) type.getMethod("build").invoke(builder)).replace("\r\n", "\n");
        Set<String> expected = switch (branch) {
            case "listing" -> Set.of("talos.list_dir");
            case "verification" -> Set.of("talos.list_dir", "talos.read_file", "talos.retrieve", "talos.run_command");
            case "read-only" -> Set.of("talos.list_dir", "talos.read_file", "talos.retrieve");
            case "unified-inspection" -> Set.of("talos.read_file");
            default -> tools.stream().map(PromptToolDescriptor::name).collect(java.util.stream.Collectors.toSet());
        };
        Set<String> visible = Pattern.compile("(?m)^- \\*\\*(talos\\.[a-z_]+)\\*\\*")
                .matcher(prompt).results().map(match -> match.group(1)).collect(java.util.stream.Collectors.toSet());
        assertEquals(expected, visible);
        assertEquals(expected.size(), prompt.split(Pattern.quote("Parameters: `" + SCHEMA + "`"), -1).length - 1);
        if (nativeTools) {
            assertFalse(prompt.contains("Textual tool-call format:"));
            assertFalse(prompt.contains("```json"));
        } else {
            assertEquals(1, prompt.split("Textual tool-call format:", -1).length - 1);
            assertTrue(prompt.contains("exactly one complete JSON object"));
            assertTrue(prompt.contains("separate closed JSON code blocks, one per call"));
            assertTrue(prompt.contains("Do not put multiple top-level JSON objects or an array of calls in one block"));
            assertTrue(prompt.contains("```json\n{\"name\": \"tool_name\", \"parameters\": {\"key\": \"value\"}}\n```"));
            if (branch.equals("resource")) {
                assertTrue(prompt.contains("{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"src/Main.java\"}}"));
                assertTrue(prompt.contains("{\"name\": \"talos.write_file\", \"parameters\": {\"path\": \"output/summary.txt\""));
            }
        }
    }

    @Test
    void missingFormatResourceUsesIdenticalInlineContract() throws Exception {
        Class<?> fallback = new ResourceHidingLoader("format-missing").loadClass(TextToolCallInstructions.class.getName());
        assertEquals(TextToolCallInstructions.formatContract(), fallback.getMethod("formatContract").invoke(null));
    }

    @Test
    void absentEmptyAndFullyFilteredToolsDoNotAddTextualInstructions() throws Exception {
        for (String resources : List.of("available", "all-missing")) {
            Class<?> type = builderClass(resources);
            for (int mode = 0; mode < 3; mode++) {
                Object builder = type.getMethod("forUnified").invoke(null);
                if (mode > 0) type.getMethod("withPromptTools", List.class).invoke(builder, mode == 1 ? List.of() : TOOLS);
                if (mode == 2) type.getMethod("withVisibleToolNames", List.class).invoke(builder, List.of());
                String prompt = (String) type.getMethod("build").invoke(builder);
                assertFalse(prompt.contains("Textual tool-call format:"));
                assertFalse(prompt.contains("Available Tools"));
            }
        }
    }

    private static PromptToolDescriptor tool(String name, boolean approval) {
        return new PromptToolDescriptor(name, "Fixture " + name, SCHEMA, approval);
    }

    private static Class<?> builderClass(String resources) throws ClassNotFoundException {
        return resources.equals("available") ? SystemPromptBuilder.class
                : new ResourceHidingLoader(resources).loadClass(SystemPromptBuilder.class.getName());
    }

    /** Loads the real assembler and instruction owner, hiding only selected classpath resources. */
    private static final class ResourceHidingLoader extends ClassLoader {
        private final String resources;

        ResourceHidingLoader(String resources) {
            super(SystemPromptBuilder.class.getClassLoader());
            this.resources = resources;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(SystemPromptBuilder.class.getName())
                    && !name.startsWith(SystemPromptBuilder.class.getName() + "$")
                    && !name.equals(TextToolCallInstructions.class.getName())) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (InputStream stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (stream == null) throw new ClassNotFoundException(name);
                        byte[] bytes = stream.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException error) {
                        throw new ClassNotFoundException(name, error);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (resources.equals("all-missing") && name.startsWith("prompts/")) return null;
            if (resources.equals("format-missing") && name.endsWith("tools-text-format.txt")) return null;
            if (resources.equals("preamble-missing") && name.endsWith("tools-preamble.txt")) return null;
            return super.getResourceAsStream(name);
        }
    }
}
