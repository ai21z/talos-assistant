package dev.talos.harness;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynchronizedCliProcessDriverTest {

    @Test
    void sends_each_line_only_after_expected_prompt_appears() throws Exception {
        PipedInputStream stdout = new PipedInputStream();
        PipedOutputStream fakeProcessOut = new PipedOutputStream(stdout);
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        SynchronizedCliProcessDriver driver = SynchronizedCliProcessDriver.start(stdout, stdin);

        Thread writer = new Thread(() -> {
            try {
                fakeProcessOut.write("talos [auto] > ".getBytes(StandardCharsets.UTF_8));
                fakeProcessOut.flush();
                Thread.sleep(50);
                fakeProcessOut.write("Allow? [y=yes, a=yes for session, N=no]".getBytes(StandardCharsets.UTF_8));
                fakeProcessOut.flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();

        driver.runSteps(List.of(
                new SynchronizedCliProcessDriver.Step("talos [auto] > ", "Read .env"),
                new SynchronizedCliProcessDriver.Step("Allow? [y=yes", "n")
        ), Duration.ofSeconds(2));

        assertEquals("Read .env" + System.lineSeparator() + "n" + System.lineSeparator(),
                stdin.toString(StandardCharsets.UTF_8));
        assertTrue(driver.transcript().contains("Allow?"), driver.transcript());
        fakeProcessOut.close();
        writer.join();
        driver.close();
    }

    @Test
    void timeout_fails_with_transcript_context_when_prompt_is_missing() throws Exception {
        PipedInputStream stdout = new PipedInputStream();
        PipedOutputStream fakeProcessOut = new PipedOutputStream(stdout);
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        SynchronizedCliProcessDriver driver = SynchronizedCliProcessDriver.start(stdout, stdin);
        fakeProcessOut.write("talos [auto] > ".getBytes(StandardCharsets.UTF_8));
        fakeProcessOut.flush();

        IOException error = assertThrows(IOException.class, () ->
                driver.runSteps(List.of(
                        new SynchronizedCliProcessDriver.Step("missing approval prompt", "n")
                ), Duration.ofMillis(150)));

        assertTrue(error.getMessage().contains("missing approval prompt"), error.getMessage());
        assertTrue(error.getMessage().contains("talos [auto]"), error.getMessage());
        fakeProcessOut.close();
        driver.close();
    }

    @Test
    void stopped_process_fails_before_sending_late_input() throws Exception {
        PipedInputStream stdout = new PipedInputStream();
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        AtomicBoolean processAlive = new AtomicBoolean(false);
        SynchronizedCliProcessDriver driver = SynchronizedCliProcessDriver.start(stdout, stdin, processAlive::get);

        IOException error = assertThrows(IOException.class, () ->
                driver.runSteps(List.of(
                        new SynchronizedCliProcessDriver.Step("Allow?", "n")
                ), Duration.ofSeconds(1)));

        assertTrue(error.getMessage().contains("process exited"), error.getMessage());
        assertEquals("", stdin.toString(StandardCharsets.UTF_8));
        driver.close();
    }
}
