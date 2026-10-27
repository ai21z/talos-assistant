package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationResourceSamplesTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void derivesEveryPublishedResourceValueFromRawSamplesAndMachineTotals() {
        byte[] csv = (QualificationResourceSamples.HEADER + "\n"
                + "2026-07-10T00:00:00Z," + (30 * GIB) + ",1,2048,10,20\n"
                + "2026-07-10T00:00:01Z," + (28 * GIB) + ",3,4096,40,50\n")
                .getBytes(StandardCharsets.UTF_8);

        QualificationResourceSamples.Summary summary =
                QualificationResourceSamples.summarize(csv, machine(32 * GIB, 8 * GIB));

        assertEquals(90, summary.processWorkingSetBytes());
        assertEquals(4 * GIB, summary.systemRamBytes());
        assertEquals(3 * 1024L * 1024L, summary.pagefileOrSwapBytes());
        assertEquals(4 * GIB, summary.gpuVramUsedBytes());
        assertEquals(8 * GIB, summary.gpuVramBytes());
        assertEquals(Instant.parse("2026-07-10T00:00:00Z"), summary.firstSampleAt());
        assertEquals(Instant.parse("2026-07-10T00:00:01Z"), summary.lastSampleAt());
        assertEquals(2, summary.sampleCount());
        assertTrue(summary.matches(new QualificationRecords.ResourceEvidence(
                QualificationRecords.RESOURCE_SCHEMA,
                QualificationRecords.SCHEMA_VERSION,
                90,
                4 * GIB,
                3 * 1024L * 1024L,
                4 * GIB,
                8 * GIB,
                "raw samples")));
    }

    @Test
    void malformedOrContradictoryRawSamplesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> QualificationResourceSamples.parse(new byte[] {(byte) 0xc3, (byte) 0x28}));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationResourceSamples.parse(bytes("wrong\n1\n")));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationResourceSamples.parse(bytes(
                        QualificationResourceSamples.HEADER + "\n"
                                + "2026-07-10T00:00:01Z,1,0,0,0,0\n"
                                + "2026-07-10T00:00:00Z,1,0,0,0,0\n")));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationResourceSamples.summarize(bytes(
                        QualificationResourceSamples.HEADER + "\n"
                                + "2026-07-10T00:00:00Z," + (33 * GIB)
                                + ",0,0,0,0\n"), machine(32 * GIB, 8 * GIB)));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationResourceSamples.summarize(bytes(
                        QualificationResourceSamples.HEADER + "\n"
                                + "2026-07-10T00:00:00Z,1,0,8193,0,0\n"),
                        machine(32 * GIB, 8 * GIB)));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static QualificationRecords.MachineFacts machine(long ram, long vram) {
        return new QualificationRecords.MachineFacts(
                QualificationRecords.MACHINE_SCHEMA,
                QualificationRecords.SCHEMA_VERSION,
                "machine",
                "Windows",
                "11",
                "x86_64",
                "cpu",
                ram,
                "gpu",
                vram,
                "driver",
                "performance",
                "cuda",
                8,
                "system-managed",
                "isolated");
    }
}
