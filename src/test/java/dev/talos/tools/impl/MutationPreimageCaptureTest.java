package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.io.AtomicFileWriter;
import dev.talos.core.security.Sandbox;
import dev.talos.core.util.Hash;
import dev.talos.tools.MutationPreimage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The producer preimage captured by the real edit and write tools (P18, D12 option B). The written bytes and the tool
 * outcome are asserted beside it, because capture is optional evidence and never changes a mutation.
 */
class MutationPreimageCaptureTest {
    private static final String PRIOR = "[SERVER]\r\nport=8080\r\n[CLIENT]\r\nport=8080\r\n";

    @TempDir Path workspace;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config());
    }

    @Test
    void theEditToolKeepsThePriorBoundToTheReplacedBytes() throws Exception {
        Files.writeString(workspace.resolve("config.ini"), PRIOR, StandardCharsets.UTF_8);

        ToolResult result = new FileEditTool().execute(new ToolCall("talos.edit_file", Map.of(
                "path", "config.ini", "old_string", "[SERVER]\r\nport=8080", "new_string", "[SERVER]\r\nport=9000")), ctx);

        assertTrue(result.success(), result.errorMessage());
        MutationPreimage preimage = result.mutationPreimage();
        assertTrue(preimage.available(), preimage.toString());
        assertTrue(preimage.existed());
        assertEquals("config.ini", preimage.target());
        assertEquals(PRIOR, preimage.text());
        assertEquals(Hash.sha256Hex(PRIOR.getBytes(StandardCharsets.UTF_8)), preimage.sha256());
        assertTrue(preimage.associatedWith(result.mutationEffects()));
        assertEquals("[SERVER]\r\nport=9000\r\n[CLIENT]\r\nport=8080\r\n",
                Files.readString(workspace.resolve("config.ini")));
    }

    @Test
    void theWriteToolKeepsThePriorOfAnExistingTarget() throws Exception {
        Files.writeString(workspace.resolve("config.ini"), PRIOR, StandardCharsets.UTF_8);

        ToolResult result = new FileWriteTool().execute(new ToolCall("talos.write_file", Map.of(
                "path", "config.ini", "content", "[SERVER]\nport=9000\n")), ctx);

        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.output().startsWith("Updated config.ini ("), result.output());
        MutationPreimage preimage = result.mutationPreimage();
        assertTrue(preimage.existed(), preimage.toString());
        assertEquals(PRIOR, preimage.text());
        assertEquals(Hash.sha256Hex(PRIOR.getBytes(StandardCharsets.UTF_8)), preimage.sha256());
        assertEquals("[SERVER]\nport=9000\n", Files.readString(workspace.resolve("config.ini")));
    }

    @Test
    void aNewTargetHasAnAbsentPreimage() throws Exception {
        ToolResult result = new FileWriteTool().execute(new ToolCall("talos.write_file", Map.of(
                "path", "notes.md", "content", "hello\n")), ctx);

        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.mutationPreimage().available());
        assertFalse(result.mutationPreimage().existed());
        assertEquals("", result.mutationPreimage().text());
    }

    @Test
    void aWriteThatAlsoCreatesItsDirectoryCarriesNoPreimage() throws Exception {
        ToolResult result = new FileWriteTool().execute(new ToolCall("talos.write_file", Map.of(
                "path", "docs/notes.md", "content", "hello\n")), ctx);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(2, result.mutationEffects().appliedPaths().size(), result.mutationEffects().toString());
        assertSame(MutationPreimage.none(), result.mutationPreimage());
        assertEquals("hello\n", Files.readString(workspace.resolve("docs/notes.md")));
    }

    @Test
    void aPriorOverTheBoundCarriesNoPreimageAndTheWriteIsUnchanged() throws Exception {
        byte[] large = new byte[MutationPreimage.MAX_BYTES + 1];
        Arrays.fill(large, (byte) 'a');
        Files.write(workspace.resolve("big.txt"), large);

        ToolResult result = new FileWriteTool().execute(new ToolCall("talos.write_file", Map.of(
                "path", "big.txt", "content", "small\n")), ctx);

        assertTrue(result.success(), result.errorMessage());
        assertSame(MutationPreimage.none(), result.mutationPreimage());
        assertEquals("small\n", Files.readString(workspace.resolve("big.txt")));
    }

    @Test
    void aPriorThatIsNotStrictUtf8CarriesNoPreimage() throws Exception {
        Files.write(workspace.resolve("data.txt"), new byte[] {(byte) 0xC3, (byte) 0x28, '\n'});

        ToolResult result = new FileWriteTool().execute(new ToolCall("talos.write_file", Map.of(
                "path", "data.txt", "content", "text\n")), ctx);

        assertTrue(result.success(), result.errorMessage());
        assertSame(MutationPreimage.none(), result.mutationPreimage());
        assertEquals("text\n", Files.readString(workspace.resolve("data.txt")));
    }

    @Test
    void aProtectedTargetCarriesNoPreimage() throws Exception {
        Files.writeString(workspace.resolve(".env"), "TOKEN=p18-canary-7f3a\n", StandardCharsets.UTF_8);

        ToolResult result = new FileWriteTool().execute(new ToolCall("talos.write_file", Map.of(
                "path", ".env", "content", "TOKEN=x\n")), ctx);

        // The tool itself writes the protected target, and the preimage is withheld by the safe-target rule.
        assertTrue(result.success(), result.errorMessage());
        assertEquals("TOKEN=x\n", Files.readString(workspace.resolve(".env")));
        assertSame(MutationPreimage.none(), result.mutationPreimage(), result.toString());
    }

    @Test
    void aFailedEditCarriesNoPreimage() throws Exception {
        Files.writeString(workspace.resolve("config.ini"), PRIOR, StandardCharsets.UTF_8);

        ToolResult result = new FileEditTool().execute(new ToolCall("talos.edit_file", Map.of(
                "path", "config.ini", "old_string", "port=8080", "new_string", "port=9000")), ctx);

        assertFalse(result.success());
        assertSame(MutationPreimage.none(), result.mutationPreimage());
        assertEquals(PRIOR, Files.readString(workspace.resolve("config.ini")));
    }

    @Test
    void bytesThatDoNotHashToTheTokenGiveNone() throws Exception {
        byte[] bytes = PRIOR.getBytes(StandardCharsets.UTF_8);
        String token = Hash.sha256Hex("changed".getBytes(StandardCharsets.UTF_8));

        assertSame(MutationPreimage.none(), MutationPreimageCapture.existing(workspace, "config.ini", bytes, token));
        assertEquals(PRIOR, MutationPreimageCapture.existing(
                workspace, "config.ini", bytes, Hash.sha256Hex(bytes)).text());
    }

    @Test
    void aWriteReadsTheTargetOnlyThroughTheCapturedToken() throws Exception {
        Path target = workspace.resolve("config.ini");
        Files.writeString(target, PRIOR, StandardCharsets.UTF_8);
        AtomicFileWriter writer = new AtomicFileWriter();
        AtomicFileWriter.Preimage token = writer.capture(target);
        Files.writeString(target, "[SERVER]\nport=7070\n", StandardCharsets.UTF_8);

        // The target changed after the token was captured, so the bytes read later are not the replaced bytes.
        assertSame(MutationPreimage.none(), MutationPreimageCapture.beforeWrite(
                workspace, "config.ini", target, token));
        assertArrayEquals("[SERVER]\nport=7070\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
        assertEquals("[SERVER]\nport=7070\n", MutationPreimageCapture.beforeWrite(
                workspace, "config.ini", target, writer.capture(target)).text());
        assertEquals(MutationPreimage.absent("new.ini"), MutationPreimageCapture.beforeWrite(
                workspace, "new.ini", workspace.resolve("new.ini"), writer.capture(workspace.resolve("new.ini"))));
    }
}
