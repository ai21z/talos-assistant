package dev.talos.app.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @deprecated Replaced by {@link TerminalFirstRun} which works on all platforms
 * including headless (WSL, SSH, Docker). Will be removed in a future version
 * along with the JavaFX dependency.
 */
@Deprecated(since = "0.9.0", forRemoval = true)
public class FirstRunWizard extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(FirstRunWizard.class);

    private static final Path SENTINEL =
            Paths.get(System.getProperty("user.home"), ".talos", "first_run_done");

    private TextArea logArea;  // live output area

    public static boolean shouldRunWizard() {
        return !Files.exists(SENTINEL);
    }

    public static void launchWizard() {
        Application.launch(FirstRunWizard.class);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Talos - First Run");

        var status = new Label(checkOllamaInstalled() ? "Ollama detected." : "Ollama not found.");
        var installBtn = new Button("Install Ollama (winget)");
        installBtn.setDisable(checkOllamaInstalled());
        installBtn.setOnAction(e -> runWingetInstall(status));

        var modelInfo = new TextArea("""
                Pick models to download later:
                 - qwen2.5:3b           (lite)
                 - qwen2.5:7b-instruct  (coder-default)
                 - llama3.1:8b-instruct (general)
                """);
        modelInfo.setEditable(false);
        modelInfo.setPrefRowCount(5);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPromptText("Setup log will appear here...");
        logArea.setPrefRowCount(8);

        var proceed = new Button("Finish & Start");
        proceed.setOnAction(e -> {
            try {
                Files.createDirectories(SENTINEL.getParent());
                Files.writeString(SENTINEL, "ok");
            } catch (IOException ex) {
                LOG.warn("Failed to write first-run sentinel {}", SENTINEL, ex);
            }
            stage.close();
            Platform.exit();
        });

        var v = new VBox(12,
                status,
                installBtn,
                new Label("Models (you can pull later):"),
                modelInfo,
                new Label("Installer output:"),
                logArea,
                proceed);
        v.setPadding(new Insets(16));
        stage.setScene(new Scene(v, 560, 420));
        stage.show();
    }

    private boolean checkOllamaInstalled() {
        try {
            Process p = new ProcessBuilder("ollama", "version")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void runWingetInstall(Label status) {
        status.setText("Installing Ollama via winget...");
        // Run on background thread to avoid blocking the JavaFX UI thread.
        Thread t = new Thread(() -> {
            try {
                Process p = new ProcessBuilder(
                        "winget", "install", "--exact", "Ollama.Ollama",
                        "--silent", "--accept-package-agreements", "--accept-source-agreements")
                        .redirectErrorStream(true)
                        .start();

                StringBuilder sb = new StringBuilder();
                try (var r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append(System.lineSeparator());
                    }
                }
                int code = p.waitFor();
                String output = sb.toString();
                LOG.info("winget install output (exit {}):\n{}", code, output);

                Platform.runLater(() -> {
                    logArea.setText(output); // <-- use the StringBuilder content (fixes Qodana warning)
                    status.setText(code == 0
                            ? "Ollama installed."
                            : "Install failed (see installer output below).");
                });
            } catch (Exception ex) {
                LOG.warn("winget install failed", ex);
                Platform.runLater(() ->
                        status.setText("Install failed: " + ex.getMessage()));
            }
        }, "winget-install");
        t.setDaemon(true);
        t.start();
    }
}
