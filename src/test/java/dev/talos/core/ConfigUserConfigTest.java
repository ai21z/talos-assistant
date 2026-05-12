package dev.talos.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigUserConfigTest {

    @TempDir Path tempDir;

    @Test
    void malformedUserConfigIsReportedInsteadOfSilentlyHidden() throws Exception {
        Path userConfig = tempDir.resolve("config.yaml");
        Files.writeString(userConfig, """
                llm:
                  transport: "engine"
                engines:
                  llama_cpp:
                    server_path: "C:\\Users\\arisz\\bad\\llama-server.exe"
                """, StandardCharsets.UTF_8);

        Config config = new Config(userConfig);

        assertEquals(userConfig.toString(), config.getReport().userConfigPath);
        assertTrue(config.getReport().userConfigPresent);
        assertFalse(config.getReport().userConfigLoaded);
        assertFalse(config.getReport().userConfigError.isBlank());
        assertEquals("classpath:config/default-config.yaml", config.getReport().loadedFrom);
    }

    @Test
    void validUserConfigWithSingleQuotedWindowsPathLoads() throws Exception {
        Path userConfig = tempDir.resolve("config.yaml");
        Files.writeString(userConfig, """
                llm:
                  transport: "engine"
                  default_backend: "llama_cpp"
                  model: "qwen2.5-coder-14b"
                engines:
                  llama_cpp:
                    mode: "managed"
                    server_path: 'C:\\Users\\arisz\\Talos\\llama-server.exe'
                    model: "qwen2.5-coder-14b"
                """, StandardCharsets.UTF_8);

        Config config = new Config(userConfig);

        assertEquals(userConfig.toString(), config.getReport().userConfigPath);
        assertTrue(config.getReport().userConfigPresent);
        assertTrue(config.getReport().userConfigLoaded);
        assertEquals("", config.getReport().userConfigError);

        Map<String, Object> engines = CfgUtil.map(config.data.get("engines"));
        Map<String, Object> llamaCpp = CfgUtil.map(engines.get("llama_cpp"));
        assertEquals("C:\\Users\\arisz\\Talos\\llama-server.exe", llamaCpp.get("server_path"));
    }

    @Test
    void absentUserConfigIsReportedAsAbsent() {
        Path userConfig = tempDir.resolve("missing.yaml");

        Config config = new Config(userConfig);

        assertEquals(userConfig.toString(), config.getReport().userConfigPath);
        assertFalse(config.getReport().userConfigPresent);
        assertFalse(config.getReport().userConfigLoaded);
        assertEquals("", config.getReport().userConfigError);
    }
}
