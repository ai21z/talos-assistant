package dev.talos.cli.repl;

import dev.talos.core.security.Redactor;
import dev.talos.cli.ui.AnswerPaneRenderer;
import dev.talos.runtime.Result;
import dev.talos.runtime.context.ContentDraftContinuation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ExactAnswerPresentationTest {
    private static String answer(String content) {
        return ContentDraftContinuation.render(content, "story.txt", "0123456789ab");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lina saw \u6d77\u8fb9.", "Lina saw \ud83c\udf0a."})
    void actualOutputEncodingMustPreserveAdmittedCharacters(String content) {
        var output = new ByteArrayOutputStream();
        var charset = java.nio.charset.Charset.forName("windows-1252");
        var renderer = ExactAnswerRenderFixture.create(output, true, false, 96, new Redactor(), charset);
        renderer.render(new Result.Ok(answer(content)));
        String lossy = output.toString(charset);
        assertFalse(lossy.contains(content), "The fixture must exercise actual output encoding loss");
        assertTrue(lossy.contains("Lina saw ?"), lossy);
        output.reset();
        assertFalse(renderer.exactAnswerPresentation().preserves().test(answer(content)));
        assertEquals(0, output.size());
    }

    @Test
    void alternateEncodingStillAdmitsRepresentableContent() {
        var charset = java.nio.charset.Charset.forName("windows-1252");
        var output = new ByteArrayOutputStream();
        var renderer = ExactAnswerRenderFixture.create(output, true, false, 96, new Redactor(), charset);
        String content = "Lina\u2019s caf\u00e9.";
        assertTrue(renderer.exactAnswerPresentation().preserves().test(answer(content)));
        renderer.render(new Result.Ok(answer(content)));
        assertTrue(output.toString(charset).contains(content));
    }

    @Test
    void encodableButNonRoundTripCharactersCannotBeSelected() {
        var charset = java.nio.charset.Charset.forName("windows-31j");
        String content = "Lina paid \u00a5.";
        assertTrue(charset.newEncoder().canEncode(content));
        assertNotEquals(content, new String(content.getBytes(charset), charset),
                "The fixture must exercise a representable but nonidentical mapping");
        var renderer = ExactAnswerRenderFixture.create(new ByteArrayOutputStream(), true, false, 96, new Redactor(), charset);
        assertFalse(renderer.exactAnswerPresentation().preserves().test(answer(content)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Curly \u201cquotes\u201d and apostrophe\u2019s", "An em\u2014dash", "caf\u00e9", "\u6d77\u8fb9", "\ud83c\udf0a"})
    void unicodeLossIsRejectedWithoutOutput(String content) {
        var output = new ByteArrayOutputStream();
        var ascii = ExactAnswerRenderFixture.create(output, false, false, 96, new Redactor());
        assertFalse(ascii.exactAnswerPresentation().preserves().test(answer(content)));
        assertEquals(0, output.size());
        var unicode = ExactAnswerRenderFixture.create(output, true, false, 96, new Redactor());
        assertTrue(unicode.exactAnswerPresentation().preserves().test(answer(content)));
        unicode.render(new Result.Ok(answer(content)));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains(content));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void discardedTransportAndCompleteRenderingPreserveAdmittedText(boolean styled) {
        String content = "Lina's clock stayed seven minutes slow.\nTwo  spaces.\nA tab\there.\n`literal` **markers**.";
        for (boolean stream : List.of(false, true)) {
            var output = new ByteArrayOutputStream();
            var renderer = ExactAnswerRenderFixture.create(output, true, styled, 96, new Redactor());
            assertTrue(renderer.exactAnswerPresentation().preserves().test(answer(content)));
            if (stream) {
                var sink = renderer.answerStreamSink(null);
                String source = answer(content);
                for (int index = 0; index < source.length(); index += 7) {
                    sink.accept(source.substring(index, Math.min(source.length(), index + 7)));
                    assertEquals(0, output.size());
                }
                renderer.render(new Result.StreamEnd());
                assertEquals(0, output.size());
            }
            renderer.render(new Result.Ok(answer(content)));
            String shown = output.toString(StandardCharsets.UTF_8).replaceAll("\\u001B\\[[;\\d]*m", "");
            for (String line : content.split("\n")) assertTrue(shown.contains(line), shown);
        }
    }

    @Test
    void ordinaryWrappingIsAllowedButLostWhitespaceAndHardCutsAreNot() {
        for (boolean styled : List.of(false, true)) {
            var output = new ByteArrayOutputStream();
            var renderer = ExactAnswerRenderFixture.create(output, true, styled, 48, new Redactor());
            String source = answer("Lina watched the sea. ".repeat(10).strip());
            assertTrue(renderer.exactAnswerPresentation().preserves().test(source));
            renderer.render(new Result.Ok(source));
            String expected = source.lines()
                    .flatMap(line -> AnswerPaneRenderer.wrapLine(line, dev.talos.cli.ui.TerminalWidths.MIN - 4).stream())
                    .collect(java.util.stream.Collectors.joining("\n"));
            String shown = output.toString(StandardCharsets.UTF_8).replaceAll("\\u001B\\[[;\\d]*m", "");
            String actual = shown.lines().filter(line -> line.startsWith("  │ "))
                    .map(line -> line.substring(4)).collect(java.util.stream.Collectors.joining("\n"));
            assertEquals(expected, actual, "The draft oracle must match the actual buffered-pane wrapping");
            for (String text : List.of("Lina  watched the sea. ".repeat(10),
                    "Lina\twatched the sea. ".repeat(10), "x".repeat(100), " padded text ".repeat(10))) {
                assertFalse(renderer.exactAnswerPresentation().preserves().test(answer(text)), text);
            }
        }
    }

    @Test
    void sourceProjectionSecretsProtocolAndBoundsRemainAuthoritative() {
        var redactor = new Redactor(Map.of("redact", Map.of("secrets", List.of("fixture-secret"))));
        var renderer = ExactAnswerRenderFixture.create(new ByteArrayOutputStream(), true, false, 96, redactor);
        for (String text : List.of("alpha\u2028Sources\u2028omega", "alpha\u2029omega",
                "fixture-secret", "<think>private reasoning</think>", "<script>outside()</script>",
                "<tool_call>hidden()</tool_call>", "x".repeat(65_537))) {
            assertFalse(renderer.exactAnswerPresentation().preserves().test(answer(text)), text.substring(0, Math.min(40, text.length())));
        }
    }

    @Test
    void admissionAndDraftBoundaryDoNotCommitDiscardedTransportText() {
        var output = new ByteArrayOutputStream();
        var renderer = ExactAnswerRenderFixture.create(output, true, true, 96, new Redactor());
        var sink = renderer.answerStreamSink(null);
        sink.accept("Prompt audit with an unfinished `prefix");
        String before = output.toString(StandardCharsets.UTF_8);
        assertEquals("", before);
        assertTrue(renderer.exactAnswerPresentation().preserves().test(answer("Lina kept the clock slow.")));
        assertEquals(before, output.toString(StandardCharsets.UTF_8));
        renderer.exactAnswerPresentation().begin().run();
        assertEquals(0, output.size());
        sink.accept(answer("Lina kept the clock slow."));
        renderer.render(new Result.StreamEnd());
        assertEquals(0, output.size());
        renderer.render(new Result.Ok(answer("Lina kept the clock slow.")));
        String shown = output.toString(StandardCharsets.UTF_8).replaceAll("\\u001B\\[[;\\d]*m", "");
        assertTrue(shown.contains("Lina kept the clock slow."));
        assertTrue(shown.contains("Save draft 0123456789ab"));
        assertFalse(shown.contains("display omitted"));
    }
}
