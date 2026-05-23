package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebPartialVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void ownsStyledPartialVerification() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><title>Neon Harbor</title></head>
                  <body><main><h1>Neon Harbor</h1></main></body>
                </html>
                """);

        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        StaticWebPartialVerifier.verifyStyledWebWorkspace(
                workspace,
                List.of("index.html"),
                facts,
                problems);

        assertTrue(problems.contains(
                "Styled web task is missing CSS styling: no stylesheet link, CSS file, or inline <style> was found."),
                problems::toString);
        assertEquals(List.of(), facts);

        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <title>Neon Harbor</title>
                    <style>body { color: #f8f8ff; }</style>
                  </head>
                  <body><main><h1>Neon Harbor</h1></main></body>
                </html>
                """);
        facts.clear();
        problems.clear();

        StaticWebPartialVerifier.verifyStyledWebWorkspace(
                workspace,
                List.of("index.html"),
                facts,
                problems);

        assertEquals(List.of(), problems);
        assertEquals(List.of("index.html: inline CSS styling is present."), facts);
    }

    @Test
    void ownsFunctionalPartialVerification() throws Exception {
        TaskContract contract = new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("index.html"),
                Set.of(),
                "Create a self-contained BMI calculator webpage in index.html with inline JavaScript.");
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <body>
                    <form id="bmi-form">
                      <input id="weight" type="number">
                      <input id="height" type="number">
                      <button type="submit">Calculate</button>
                      <output id="result"></output>
                    </form>
                  </body>
                </html>
                """);

        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        StaticWebPartialVerifier.verifyFunctionalWebWorkspace(
                workspace,
                contract,
                List.of("index.html"),
                facts,
                problems);

        assertTrue(problems.contains(
                "Functional web task is missing JavaScript behavior: no JavaScript file or inline script was found."),
                problems::toString);
        assertTrue(problems.contains("HTML does not link a JavaScript file for functional behavior."), problems::toString);
        assertEquals(List.of("Calculator/form static structure checks passed."), facts);

        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <body>
                    <form id="bmi-form">
                      <input id="weight" type="number">
                      <input id="height" type="number">
                      <button type="submit">Calculate</button>
                      <output id="result"></output>
                    </form>
                    <script>document.getElementById('bmi-form');</script>
                  </body>
                </html>
                """);
        facts.clear();
        problems.clear();

        StaticWebPartialVerifier.verifyFunctionalWebWorkspace(
                workspace,
                contract,
                List.of("index.html"),
                facts,
                problems);

        assertEquals(List.of(), problems);
        assertEquals(List.of("Calculator/form static structure checks passed."), facts);
    }
}
