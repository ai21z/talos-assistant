package dev.talos.cli.doctor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NvidiaGpuQueryTest {

    @Test
    void parsesNvidiaSmiCsvLineIntoFacts() {
        var facts = NvidiaGpuQuery.parse("NVIDIA GeForce RTX 5070 Ti, 16303, 15873, 610.62").orElseThrow();

        assertEquals("NVIDIA GeForce RTX 5070 Ti", facts.name());
        assertEquals(16_303L, facts.vramTotalMb());
        assertEquals(15_873L, facts.vramFreeMb());
        assertEquals("610.62", facts.driverVersion());
    }

    @Test
    void parseToleratesUnitSuffixesAndPicksFirstGpuLine() {
        var facts = NvidiaGpuQuery.parse(
                "NVIDIA RTX A2000, 6144 MiB, 5000 MiB, 551.61\nNVIDIA RTX A4000, 16384 MiB, 12000 MiB, 551.61\n")
                .orElseThrow();

        assertEquals("NVIDIA RTX A2000", facts.name());
        assertEquals(6_144L, facts.vramTotalMb());
        assertEquals("551.61", facts.driverVersion());
    }

    @Test
    void parseFailsClosedOnBlankOrMalformedOutput() {
        assertTrue(NvidiaGpuQuery.parse("").isEmpty());
        assertTrue(NvidiaGpuQuery.parse(null).isEmpty());
        assertTrue(NvidiaGpuQuery.parse("No devices were found").isEmpty());
        assertTrue(NvidiaGpuQuery.parse("name only, 123").isEmpty());
        assertTrue(NvidiaGpuQuery.parse("NVIDIA X, not-a-number, 5, 610.62").isEmpty());
    }
}
