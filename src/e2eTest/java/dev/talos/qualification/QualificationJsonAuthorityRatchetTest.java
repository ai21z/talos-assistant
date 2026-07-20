package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationJsonAuthorityRatchetTest {
    private static final Path SOURCE_ROOT =
            Path.of("src/e2eTest/java/dev/talos/qualification");
    private static final String TEST_FIXTURE_HELPER = "QualificationTestPacket.java";
    private static final Pattern COERCIVE_TREE_ACCESSOR = Pattern.compile(
            "(?:\\.\\s*as(?:Text|Int|Long|Boolean|Double)\\s*\\("
                    + "|::\\s*as(?:Text|Int|Long|Boolean|Double)\\b)");
    private static final Pattern MAPPER_CONSTRUCTOR = Pattern.compile(
            "new(?:[\\w$.]+\\.)?(?:ObjectMapper|JsonMapper)\\(");
    private static final Pattern MAPPER_BUILDER = Pattern.compile(
            "(?:[\\w$.]+\\.)?(?:ObjectMapper|JsonMapper)\\.builder\\w*\\(");

    @Test
    void evidenceAuthorityDoesNotUseCoerciveTreeScalarAccessors() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path path : authoritySources()) {
            String source = Files.readString(path);
            if (COERCIVE_TREE_ACCESSOR.matcher(source).find()) {
                violations.add(relative(path)
                        + " contains a coercive JsonNode scalar accessor");
            }
        }

        assertEquals(List.of(), violations,
                "qualification evidence authority must use exact typed readers");
    }

    @Test
    void qualificationJsonIsTheOnlyJsonMapperOwner() throws Exception {
        List<String> violations = new ArrayList<>();
        int sharedMapperOwners = 0;
        int yamlMapperOwners = 0;
        for (Path path : authoritySources()) {
            String source = Files.readString(path);
            String compact = source.replaceAll("\\s+", "");
            int sharedBuilders = occurrences(compact, "JsonMapper.builder(");
            if (path.getFileName().toString().equals("QualificationJson.java")) {
                sharedMapperOwners += sharedBuilders;
            }

            int yamlMappers = occurrences(
                    compact, "newObjectMapper(newYAMLFactory())");
            if (path.getFileName().toString().equals("QualificationPtyCaptureMain.java")) {
                yamlMapperOwners += yamlMappers;
            }

            int constructors = matches(MAPPER_CONSTRUCTOR, compact);
            int builders = matches(MAPPER_BUILDER, compact);
            int permittedConstructors =
                    path.getFileName().toString().equals(
                            "QualificationPtyCaptureMain.java") ? 1 : 0;
            int permittedBuilders =
                    path.getFileName().toString().equals(
                            "QualificationJson.java") ? 1 : 0;
            if (constructors != permittedConstructors
                    || builders != permittedBuilders) {
                violations.add(relative(path)
                        + " has unowned mapper construction: constructors="
                        + constructors + ", builders=" + builders);
            }
        }

        assertEquals(1, sharedMapperOwners,
                "QualificationJson must own exactly one shared JSON mapper");
        assertEquals(1, yamlMapperOwners,
                "the PTY entrypoint must retain exactly one explicit YAML-only mapper");
        assertEquals(List.of(), violations,
                "qualification protocol JSON must have one mapper policy");
    }

    private static List<Path> authoritySources() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT),
                "qualification source root is unavailable: " + SOURCE_ROOT);
        try (var paths = Files.walk(SOURCE_ROOT)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().endsWith("Test.java"))
                    .filter(path -> !path.getFileName().toString().equals(TEST_FIXTURE_HELPER))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static String relative(Path path) {
        return SOURCE_ROOT.relativize(path).toString().replace('\\', '/');
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static int matches(Pattern pattern, String source) {
        int count = 0;
        var matcher = pattern.matcher(source);
        while (matcher.find()) count++;
        return count;
    }
}
