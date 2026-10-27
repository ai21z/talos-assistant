package dev.talos.cli.repl;

import dev.talos.cli.ui.*;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;

/** Real rendering is exercised with explicit terminal capabilities. */
public final class ExactAnswerRenderFixture {
    private ExactAnswerRenderFixture() { }
    public static RenderEngine create(ByteArrayOutputStream output, boolean unicode, boolean styled,
                                      int width, Redactor redactor) {
        return create(output, unicode, styled, width, redactor, StandardCharsets.UTF_8);
    }

    public static RenderEngine create(ByteArrayOutputStream output, boolean unicode, boolean styled,
                                      int width, Redactor redactor, Charset charset) {
        var capabilities = new TerminalCapabilities(styled ? ColorPolicy.ALWAYS : ColorPolicy.NEVER,
                styled, styled, unicode, false);
        return new RenderEngine(new Config(null), redactor,
                new PrintStream(output, true, charset), styled,
                CliTheme.forCapabilities(capabilities), () -> width);
    }
}
