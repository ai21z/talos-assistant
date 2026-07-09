package dev.talos.engine.llamacpp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record LlamaCppLogEvidence(
        Optional<Offload> offload,
        Optional<Fit> fit,
        List<Buffer> buffers,
        List<Timing> timings
) {
    private static final String LOG_LINE_PREFIX = "^(?:\\S+\\s+[A-Z]\\s+)?";
    private static final Pattern OFFLOAD = Pattern.compile(
            LOG_LINE_PREFIX
                    + "load_tensors:\\s+offloaded\\s+(\\d+)/(\\d+)\\s+layers\\s+to\\s+(.+?)\\s*$");
    private static final Pattern FIT_PROJECTED = Pattern.compile(
            LOG_LINE_PREFIX
                    + "common_params_fit_impl:\\s+projected to use\\s+(\\d+)\\s+MiB of device memory vs\\.\\s+(\\d+)\\s+MiB of free device memory");
    private static final Pattern FIT_REMAINING = Pattern.compile(
            LOG_LINE_PREFIX
                    + "common_params_fit_impl:\\s+will leave\\s+(\\d+)\\s+>=\\s+(\\d+)\\s+MiB of free device memory,\\s*(.+?)\\s*$");
    private static final Pattern BUFFER = Pattern.compile(
            LOG_LINE_PREFIX
                    + "(?:load_tensors|llama_kv_cache):\\s+(\\S+)\\s+(model|KV) buffer size\\s+=\\s+([0-9.]+)\\s+MiB");
    private static final Pattern TIMING = Pattern.compile(
            LOG_LINE_PREFIX
                    + "slot print_timing: id\\s+\\d+\\s+\\|\\s+task\\s+(\\d+)\\s+\\|\\s+"
                    + "(prompt eval|eval) time\\s+=\\s+([0-9.]+)\\s+ms\\s+/\\s+(\\d+)\\s+tokens"
                    + "\\s+\\([^,]+,\\s+([0-9.]+)\\s+tokens per second\\)");

    public static LlamaCppLogEvidence parse(String log) {
        Optional<Offload> offload = Optional.empty();
        Integer projectedMiB = null;
        Integer freeMiB = null;
        Integer remainingMiB = null;
        Integer requiredRemainingMiB = null;
        String decision = null;
        List<Buffer> buffers = new ArrayList<>();
        List<Timing> timings = new ArrayList<>();

        String text = log == null ? "" : log;
        for (String line : text.split("\\R")) {
            Matcher offloadMatcher = OFFLOAD.matcher(line);
            if (offloadMatcher.find()) {
                offload = Optional.of(new Offload(
                        Integer.parseInt(offloadMatcher.group(1)),
                        Integer.parseInt(offloadMatcher.group(2)),
                        offloadMatcher.group(3).trim()));
            }

            Matcher projectedMatcher = FIT_PROJECTED.matcher(line);
            if (projectedMatcher.find()) {
                projectedMiB = Integer.parseInt(projectedMatcher.group(1));
                freeMiB = Integer.parseInt(projectedMatcher.group(2));
            }

            Matcher remainingMatcher = FIT_REMAINING.matcher(line);
            if (remainingMatcher.find()) {
                remainingMiB = Integer.parseInt(remainingMatcher.group(1));
                requiredRemainingMiB = Integer.parseInt(remainingMatcher.group(2));
                decision = remainingMatcher.group(3).trim();
            }

            Matcher bufferMatcher = BUFFER.matcher(line);
            if (bufferMatcher.find()) {
                buffers.add(new Buffer(
                        bufferMatcher.group(1),
                        bufferMatcher.group(2),
                        Double.parseDouble(bufferMatcher.group(3))));
            }

            Matcher timingMatcher = TIMING.matcher(line);
            if (timingMatcher.find()) {
                timings.add(new Timing(
                        Integer.parseInt(timingMatcher.group(1)),
                        timingKind(timingMatcher.group(2)),
                        Double.parseDouble(timingMatcher.group(3)),
                        Integer.parseInt(timingMatcher.group(4)),
                        Double.parseDouble(timingMatcher.group(5))));
            }
        }

        Optional<Fit> fit = projectedMiB == null
                || freeMiB == null
                || remainingMiB == null
                || requiredRemainingMiB == null
                || decision == null
                ? Optional.empty()
                : Optional.of(new Fit(projectedMiB, freeMiB, remainingMiB, requiredRemainingMiB, decision));
        return new LlamaCppLogEvidence(offload, fit, List.copyOf(buffers), List.copyOf(timings));
    }

    private static String timingKind(String raw) {
        return "prompt eval".equals(raw) ? "prompt_eval" : "eval";
    }

    public record Offload(int offloadedLayers, int totalLayers, String target) {}

    public record Fit(int projectedDeviceMemoryMiB,
                      int freeDeviceMemoryMiB,
                      int remainingMiB,
                      int requiredRemainingMiB,
                      String decision) {}

    public record Buffer(String device, String kind, double sizeMiB) {}

    public record Timing(int taskId, String kind, double millis, int tokens, double tokensPerSecond) {}
}
