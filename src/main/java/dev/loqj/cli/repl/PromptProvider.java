package dev.loqj.cli.repl;

/**
 * Interface for providing dynamic prompts that can update based on current mode
 */
public interface PromptProvider {
    /**
     * Generate the current prompt string based on mode and context
     */
    String getPrompt();

    /**
     * Update the prompt when mode changes
     */
    void onModeChanged(String newMode);
}
