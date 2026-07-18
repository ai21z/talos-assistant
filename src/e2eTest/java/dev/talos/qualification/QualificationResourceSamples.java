package dev.talos.qualification;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict parser and reducer for the raw resource samples captured during a trial. */
final class QualificationResourceSamples {
    static final String HEADER =
            "timestamp,systemAvailableBytes,pagefileUsedMiB,gpuUsedMiB,"
                    + "javaWorkingSetBytes,llamaWorkingSetBytes";
    private static final long MEBIBYTE = 1024L * 1024L;

    private QualificationResourceSamples() {}

    record Sample(
            Instant timestamp,
            long systemAvailableBytes,
            long pagefileUsedMiB,
            long gpuUsedMiB,
            long javaWorkingSetBytes,
            long llamaWorkingSetBytes) {
        Sample {
            Objects.requireNonNull(timestamp, "resource sample timestamp");
            requireNonNegative(systemAvailableBytes, "systemAvailableBytes");
            requireNonNegative(pagefileUsedMiB, "pagefileUsedMiB");
            requireNonNegative(gpuUsedMiB, "gpuUsedMiB");
            requireNonNegative(javaWorkingSetBytes, "javaWorkingSetBytes");
            requireNonNegative(llamaWorkingSetBytes, "llamaWorkingSetBytes");
        }
    }

    record Summary(
            long processWorkingSetBytes,
            long systemRamBytes,
            long pagefileOrSwapBytes,
            long gpuVramUsedBytes,
            long gpuVramBytes,
            Instant firstSampleAt,
            Instant lastSampleAt,
            int sampleCount) {
        Summary {
            Objects.requireNonNull(firstSampleAt, "first resource sample timestamp");
            Objects.requireNonNull(lastSampleAt, "last resource sample timestamp");
            if (sampleCount < 1) throw new IllegalArgumentException("resource sample count must be positive");
        }

        boolean matches(QualificationRecords.ResourceEvidence evidence) {
            return evidence != null
                    && processWorkingSetBytes == evidence.processWorkingSetBytes()
                    && systemRamBytes == evidence.systemRamBytes()
                    && pagefileOrSwapBytes == evidence.pagefileOrSwapBytes()
                    && gpuVramUsedBytes == evidence.gpuVramUsedBytes()
                    && gpuVramBytes == evidence.gpuVramBytes();
        }
    }

    static List<Sample> parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("resource-samples.csv is missing or empty");
        }
        String text = strictUtf8(bytes);
        String normalized = text.replace("\r\n", "\n");
        if (normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("resource-samples.csv contains a lone carriage return");
        }
        String[] lines = normalized.split("\n", -1);
        if (lines.length < 2 || !HEADER.equals(lines[0])) {
            throw new IllegalArgumentException("resource-samples.csv header is invalid");
        }
        int last = lines.length;
        if (last > 0 && lines[last - 1].isEmpty()) last--;
        List<Sample> samples = new ArrayList<>();
        Instant previous = null;
        for (int index = 1; index < last; index++) {
            if (lines[index].isBlank()) {
                throw new IllegalArgumentException("resource-samples.csv contains a blank row");
            }
            String[] fields = lines[index].split(",", -1);
            if (fields.length != 6) {
                throw new IllegalArgumentException(
                        "resource-samples.csv row " + (index + 1) + " must contain six fields");
            }
            Instant timestamp;
            try {
                timestamp = Instant.parse(fields[0]);
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException(
                        "resource-samples.csv row " + (index + 1) + " timestamp is invalid",
                        invalid);
            }
            if (previous != null && timestamp.isBefore(previous)) {
                throw new IllegalArgumentException(
                        "resource-samples.csv timestamps are not monotonic");
            }
            previous = timestamp;
            samples.add(new Sample(
                    timestamp,
                    parseLong(fields[1], index, "systemAvailableBytes"),
                    parseLong(fields[2], index, "pagefileUsedMiB"),
                    parseLong(fields[3], index, "gpuUsedMiB"),
                    parseLong(fields[4], index, "javaWorkingSetBytes"),
                    parseLong(fields[5], index, "llamaWorkingSetBytes")));
        }
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("resource-samples.csv contains no samples");
        }
        return List.copyOf(samples);
    }

    static Summary summarize(byte[] bytes, QualificationRecords.MachineFacts machine) {
        Objects.requireNonNull(machine, "machine facts");
        if (machine.physicalRamBytes() <= 0 || machine.vramBytes() < 0) {
            throw new IllegalArgumentException("machine resource totals are invalid");
        }
        List<Sample> samples = parse(bytes);
        long maxProcess = 0;
        long minAvailable = Long.MAX_VALUE;
        long maxPageMiB = 0;
        long maxGpuMiB = 0;
        for (Sample sample : samples) {
            if (sample.systemAvailableBytes() > machine.physicalRamBytes()) {
                throw new IllegalArgumentException(
                        "resource sample available RAM exceeds frozen physical RAM");
            }
            long combined = Math.addExact(
                    sample.javaWorkingSetBytes(), sample.llamaWorkingSetBytes());
            maxProcess = Math.max(maxProcess, combined);
            minAvailable = Math.min(minAvailable, sample.systemAvailableBytes());
            maxPageMiB = Math.max(maxPageMiB, sample.pagefileUsedMiB());
            maxGpuMiB = Math.max(maxGpuMiB, sample.gpuUsedMiB());
        }
        long gpuUsedBytes = Math.multiplyExact(maxGpuMiB, MEBIBYTE);
        if (gpuUsedBytes > machine.vramBytes()) {
            throw new IllegalArgumentException(
                    "resource sample GPU usage exceeds frozen VRAM");
        }
        return new Summary(
                maxProcess,
                machine.physicalRamBytes() - minAvailable,
                Math.multiplyExact(maxPageMiB, MEBIBYTE),
                gpuUsedBytes,
                machine.vramBytes(),
                samples.getFirst().timestamp(),
                samples.getLast().timestamp(),
                samples.size());
    }

    private static long parseLong(String value, int zeroBasedRow, String field) {
        try {
            long parsed = Long.parseLong(value);
            requireNonNegative(parsed, field);
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "resource-samples.csv row " + (zeroBasedRow + 1)
                            + " has invalid " + field,
                    invalid);
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException(
                    "resource-samples.csv is not valid UTF-8", invalid);
        }
    }
}
