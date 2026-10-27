package dev.talos.scripts;

import com.sun.nio.file.ExtendedOpenOption;
import dev.talos.build.FixtureProcessOwnership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BumpPatchScriptTest {

    private static final Path SCRIPT = Path.of("scripts", "bump-patch.ps1").toAbsolutePath();
    private static final long PROCESS_TIMEOUT_SECONDS = 30;
    private static final long TERMINATION_TIMEOUT_SECONDS = 5;
    private static final long FIXTURE_READINESS_TIMEOUT_SECONDS = 15;

    @TempDir
    Path tempDir;

    @Test
    void movesUnreleasedNotesIntoNextNumericPatchVersion() throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, """
                talosVersion=0.9.9
                javaVersion=21
                """);
        writeUtf8(changelog, """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Stabilized beta blocker evidence lanes.
                - Added lane-labeled audit evidence capture.

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the previous beta candidate.
                """);

        LocalDate earliestCandidateDate = LocalDate.now();
        ScriptResult result = runBumpPatch(properties, changelog);
        LocalDate latestCandidateDate = LocalDate.now();

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("talosVersion=0.9.10\njavaVersion=21\n", readUtf8(properties));
        assertCanonicalUtf8Lf(properties);

        String updated = readUtf8(changelog);
        LocalDate candidateDate = candidateDate(updated, "0.9.10");
        assertDateWithin(candidateDate, earliestCandidateDate, latestCandidateDate);
        String expectedHeader = "# Changelog\n\n## [Unreleased]\n\n"
                + "## [0.9.10] - " + candidateDate + "\n\n";
        assertTrue(updated.startsWith(expectedHeader), updated);
        assertTrue(updated.contains("### Changed\n"
                + "- Stabilized beta blocker evidence lanes.\n"
                + "- Added lane-labeled audit evidence capture."));
        assertTrue(updated.indexOf("## [0.9.10]") < updated.indexOf("## [0.9.9]"));
        assertFalse(updated.contains("pending release notes"));
        assertCanonicalUtf8Lf(changelog);
        assertNoOwnedArtifacts(properties.getParent(), changelog.getParent());
    }

    @Test
    void canonicalizesCrLfInputsWithoutAddingTerminalBlankLines() throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, "talosVersion=0.9.9\r\njavaVersion=21\r\n\r\n");
        writeUtf8(changelog, ("""
                # Changelog

                ## [Unreleased]

                ### Fixed
                - Preserved canonical UTF-8 metadata for café profiles.

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the previous beta candidate.
                """).replace("\n", "\r\n"));

        LocalDate earliestCandidateDate = LocalDate.now();
        ScriptResult result = runBumpPatch(properties, changelog);
        LocalDate latestCandidateDate = LocalDate.now();

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("talosVersion=0.9.10\njavaVersion=21\n", readUtf8(properties));
        LocalDate candidateDate = candidateDate(readUtf8(changelog), "0.9.10");
        assertDateWithin(candidateDate, earliestCandidateDate, latestCandidateDate);
        String expectedChangelog = "# Changelog\n\n## [Unreleased]\n\n"
                + "## [0.9.10] - " + candidateDate + "\n\n"
                + "### Fixed\n- Preserved canonical UTF-8 metadata for café profiles.\n\n"
                + "## [0.9.9] - 2026-05-15\n\n"
                + "### Changed\n- Declared the previous beta candidate.\n";
        assertEquals(expectedChangelog, readUtf8(changelog));
        assertCanonicalUtf8Lf(properties);
        assertCanonicalUtf8Lf(changelog);
        assertNoOwnedArtifacts(properties.getParent(), changelog.getParent());
    }

    @Test
    void canonicalizesLoneCrInputsToLf() throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, "talosVersion=0.9.9\rjavaVersion=21\r");
        writeUtf8(changelog, validChangelog("0.9.9").replace("\n", "\r"));

        LocalDate earliestCandidateDate = LocalDate.now();
        ScriptResult result = runBumpPatch(properties, changelog);
        LocalDate latestCandidateDate = LocalDate.now();

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("talosVersion=0.9.10\njavaVersion=21\n", readUtf8(properties));
        assertDateWithin(candidateDate(readUtf8(changelog), "0.9.10"),
                earliestCandidateDate, latestCandidateDate);
        assertCanonicalUtf8Lf(properties);
        assertCanonicalUtf8Lf(changelog);
        assertNoOwnedArtifacts(properties.getParent(), changelog.getParent());
    }

    @Test
    void updatesTheSemanticVersionRecordInsteadOfACanonicalLookingContinuationLine() throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, "other=value\\\ntalosVersion=decoy\ntalosVersion=0.9.9\n");
        writeUtf8(changelog, validChangelog("0.9.9"));

        ScriptResult result = runBumpPatch(properties, changelog);

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("other=value\\\ntalosVersion=decoy\ntalosVersion=0.9.10\n", readUtf8(properties));
        assertCanonicalUtf8Lf(properties);
        assertCanonicalUtf8Lf(changelog);
        assertNoOwnedArtifacts(properties.getParent(), changelog.getParent());
    }

    @Test
    void rejectsMalformedUtf8PropertiesWithoutMutation() throws Exception {
        byte[] malformedProperties = new byte[] {
                't', 'a', 'l', 'o', 's', 'V', 'e', 'r', 's', 'i', 'o', 'n', '=',
                '0', '.', '9', '.', '9', '\n', (byte) 0xC3, 0x28
        };
        assertRejectedBytesWithoutMutation(
                malformedProperties,
                validChangelog("0.9.9").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsMalformedUtf8ChangelogWithoutMutation() throws Exception {
        byte[] valid = validChangelog("0.9.9").getBytes(StandardCharsets.UTF_8);
        byte[] malformedChangelog = java.util.Arrays.copyOf(valid, valid.length + 2);
        malformedChangelog[valid.length] = (byte) 0xC3;
        malformedChangelog[valid.length + 1] = 0x28;
        assertRejectedBytesWithoutMutation(
                "talosVersion=0.9.9\n".getBytes(StandardCharsets.UTF_8),
                malformedChangelog);
    }

    @Test
    void failsClosedWhenUnreleasedSectionIsMissing() throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, "talosVersion=0.9.9\n");
        writeUtf8(changelog, """
                # Changelog

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the previous beta candidate.
                """);
        byte[] propertiesBefore = Files.readAllBytes(properties);
        byte[] changelogBefore = Files.readAllBytes(changelog);

        ScriptResult result = runBumpPatch(properties, changelog);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("CHANGELOG.md must contain a top-level '## [Unreleased]' section"),
                result.output());
        assertArrayEquals(propertiesBefore, Files.readAllBytes(properties));
        assertArrayEquals(changelogBefore, Files.readAllBytes(changelog));
    }

    @Test
    void failsClosedWhenUnreleasedSectionHasNoMaterialNotes() throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, "talosVersion=0.9.9\n");
        writeUtf8(changelog, """
                # Changelog

                ## [Unreleased]

                ### Changed

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the previous beta candidate.
                """);
        byte[] propertiesBefore = Files.readAllBytes(properties);
        byte[] changelogBefore = Files.readAllBytes(changelog);

        ScriptResult result = runBumpPatch(properties, changelog);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Unreleased section has no material release notes"),
                result.output());
        assertArrayEquals(propertiesBefore, Files.readAllBytes(properties));
        assertArrayEquals(changelogBefore, Files.readAllBytes(changelog));
        assertNoOwnedArtifacts(properties.getParent(), changelog.getParent());
    }

    @Test
    void failsClosedWhenTalosVersionIsDuplicated() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\ntalosVersion=0.9.8\n",
                validChangelog("0.9.9"),
                "gradle.properties must contain exactly one talosVersion assignment");
    }

    @Test
    void failsClosedWhenASecondTalosVersionAssignmentIsMalformed() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\ntalosVersion: not-a-version\n",
                validChangelog("0.9.9"),
                "gradle.properties must contain exactly one talosVersion assignment");
    }

    @Test
    void failsClosedWhenTalosVersionIsDuplicatedThroughAUnicodeEscapedKey() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\ntalos\\u0056ersion=0.9.8\n",
                validChangelog("0.9.9"),
                "gradle.properties must contain exactly one talosVersion assignment");
    }

    @Test
    void failsClosedWhenTalosVersionIsDuplicatedThroughAContinuedKey() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\ntalosVer\\\n  sion: 0.9.8\n",
                validChangelog("0.9.9"),
                "gradle.properties must contain exactly one talosVersion assignment");
    }

    @Test
    void failsClosedWhenTalosVersionIsDuplicatedAfterFormFeedWhitespace() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n\ftalosVersion 0.9.8\n",
                validChangelog("0.9.9"),
                "gradle.properties must contain exactly one talosVersion assignment");
    }

    @Test
    void failsClosedWhenTheOnlyTalosVersionKeyUsesANonCanonicalEscape() throws Exception {
        assertRejectedWithoutMutation(
                "talos\\u0056ersion=0.9.9\n",
                validChangelog("0.9.9"),
                "Could not find a numeric talosVersion entry");
    }

    @Test
    void failsClosedOnMalformedUnicodeInAPropertyKey() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\nother\\u12G4=value\n",
                validChangelog("0.9.9"),
                "malformed Unicode escape in a property key");
    }

    @Test
    void failsClosedWhenTalosVersionUsesALeadingZeroComponent() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.09.9\n",
                validChangelog("0.9.9"),
                "Could not find a numeric talosVersion entry");
    }

    @Test
    void failsClosedWhenUnreleasedSectionIsDuplicated() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                """
                        # Changelog

                        ## [Unreleased]

                        ### Changed
                        - First set of candidate notes.

                        ## [Unreleased]

                        ### Changed
                        - Conflicting candidate notes.

                        ## [0.9.9] - 2026-05-15

                        ### Changed
                        - Declared the previous beta candidate.
                        """,
                "CHANGELOG.md must contain exactly one top-level '## [Unreleased]' section");
    }

    @Test
    void failsClosedWhenReleasedVersionHeadingIsDuplicated() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                """
                        # Changelog

                        ## [Unreleased]

                        ### Changed
                        - Prepared the next candidate.

                        ## [0.9.9] - 2026-05-15

                        ### Changed
                        - Declared the previous beta candidate.

                        ## [0.9.9] - 2026-05-14

                        ### Fixed
                        - Conflicting duplicate ledger entry.
                        """,
                "CHANGELOG.md contains a duplicate released-version heading: '0.9.9'");
    }

    @Test
    void failsClosedWhenTopReleasedVersionDoesNotMatchProperties() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                validChangelog("0.9.8"),
                "Top released CHANGELOG.md version 0.9.8 does not match talosVersion 0.9.9");
    }

    @Test
    void failsClosedWhenTopReleasedVersionLacksAnIsoDate() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                """
                        # Changelog

                        ## [Unreleased]

                        ### Changed
                        - Prepared the next candidate.

                        ## [0.9.9]

                        ### Changed
                        - Declared the previous beta candidate.
                        """,
                "Released CHANGELOG.md version 0.9.9 must include an ISO release date");
    }

    @Test
    void failsClosedWhenABracketedReleaseHeadingIsMalformed() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                validChangelog("0.9.9") + "\n## [0.9.8] - not-a-date\n",
                "CHANGELOG.md contains a malformed bracketed top-level release heading");
    }

    @Test
    void failsClosedWhenABracketedReleaseHeadingIsIndentedAsCommonMarkAllows() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                validChangelog("0.9.9") + "\n ## [0.9.10] - 2026-05-01\n",
                "CHANGELOG.md contains a malformed bracketed top-level release heading");
    }

    @Test
    void failsClosedWhenABracketedReleaseHeadingUsesATabAfterTheMarker() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                validChangelog("0.9.9") + "\n##\t[0.9.10] - 2026-05-01\n",
                "CHANGELOG.md contains a malformed bracketed top-level release heading");
    }

    @Test
    void failsClosedWhenAReleaseDateIsNotARealCalendarDate() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                """
                        # Changelog

                        ## [Unreleased]

                        ### Changed
                        - Prepared the next candidate.

                        ## [0.9.9] - 2026-02-30

                        ### Changed
                        - This shape-valid date is not a real calendar date.
                        """,
                "Released CHANGELOG.md version 0.9.9 must include a valid ISO release date");
    }

    @Test
    void failsClosedWhenAReleaseDateUsesYearZero() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                """
                        # Changelog

                        ## [Unreleased]

                        ### Changed
                        - Prepared the next candidate.

                        ## [0.9.9] - 0000-01-01

                        ### Changed
                        - Year zero is outside the supported release ledger range.
                        """,
                "Released CHANGELOG.md version 0.9.9 must include a valid ISO release date");
    }

    @Test
    void failsClosedWhenAReleasedIdentityIsNotNumericSemanticVersion() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                validChangelog("0.9.9") + "\n## [beta] - 2026-05-01\n",
                "released-version headings must use numeric MAJOR.MINOR.PATCH identities");
    }

    @Test
    void failsClosedWhenAReleasedIdentityUsesALeadingZeroComponent() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                validChangelog("0.9.9") + "\n## [0.09.8] - 2026-05-01\n",
                "released-version headings must use numeric MAJOR.MINOR.PATCH identities");
    }

    @Test
    void failsClosedWhenAReleasedIdentityContainsANonAsciiDigit() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                validChangelog("0.9.9").replace("## [0.9.9]", "## [0.9.9\u0661]"),
                "released-version headings must use numeric MAJOR.MINOR.PATCH identities");
    }

    @Test
    void failsClosedWhenTargetCandidateVersionAlreadyExists() throws Exception {
        assertRejectedWithoutMutation(
                "talosVersion=0.9.9\n",
                """
                        # Changelog

                        ## [Unreleased]

                        ### Changed
                        - Prepared the next candidate.

                        ## [0.9.9] - 2026-05-15

                        ### Changed
                        - Declared the previous beta candidate.

                        ## [0.9.10] - 2026-05-01

                        ### Fixed
                        - Conflicting future ledger entry.
                        """,
                "CHANGELOG.md already contains the target candidate version heading '0.9.10'");
    }

    @Test
    void rejectsUsingTheSameFileForBothInputs() throws Exception {
        Path combined = tempDir.resolve("combined.txt");
        writeUtf8(combined, "talosVersion=0.9.9\n" + validChangelog("0.9.9"));
        byte[] before = Files.readAllBytes(combined);

        ScriptResult result = runBumpPatch(combined, combined);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("gradle.properties and CHANGELOG.md must be distinct files"),
                result.output());
        assertArrayEquals(before, Files.readAllBytes(combined));
        assertNoOwnedArtifacts(combined.getParent());
    }

    @Test
    void rejectsAReparsePointInputWithoutChangingItsTarget() throws Exception {
        Path target = tempDir.resolve("real-gradle.properties");
        Path properties = tempDir.resolve("linked-gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(target, "talosVersion=0.9.9\n");
        writeUtf8(changelog, validChangelog("0.9.9"));
        try {
            Files.createSymbolicLink(properties, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "symbolic-link fixture unavailable: " + unavailable.getMessage());
            return;
        }
        byte[] targetBefore = Files.readAllBytes(target);

        ScriptResult result = runBumpPatch(properties, changelog);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("ordinary non-reparse file"), result.output());
        assertArrayEquals(targetBefore, Files.readAllBytes(target));
        assertTrue(Files.exists(properties, LinkOption.NOFOLLOW_LINKS));
        assertNoOwnedArtifacts(tempDir);
    }

    @Test
    void rejectsADanglingReparsePointInputWithoutReplacingIt() throws Exception {
        Path properties = tempDir.resolve("dangling-gradle.properties");
        Path missingTarget = tempDir.resolve("missing-gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(changelog, validChangelog("0.9.9"));
        try {
            Files.createSymbolicLink(properties, missingTarget);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "symbolic-link fixture unavailable: " + unavailable.getMessage());
            return;
        }

        ScriptResult result = runBumpPatch(properties, changelog);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("ordinary non-reparse file"), result.output());
        assertTrue(Files.exists(properties, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(missingTarget));
        assertNoOwnedArtifacts(tempDir);
    }

    @Test
    void preservesExternalPropertiesEditBeforePromotionWithoutClaimingAllOriginalsWereRestored() throws Exception {
        assertExternalDriftPreserved(
                "    $changelogBackupPath = New-TalosOwnedPath -TargetPath $changelogFullPath -Kind \"backup\"",
                true,
                false);
    }

    @Test
    void preservesExternalChangelogEditBeforePromotionWithoutClaimingAllOriginalsWereRestored() throws Exception {
        assertExternalDriftPreserved(
                "    $changelogBackupPath = New-TalosOwnedPath -TargetPath $changelogFullPath -Kind \"backup\"",
                false,
                false);
    }

    @Test
    void restoresPromotedPropertiesAndPreservesExternalChangelogEdit() throws Exception {
        assertExternalDriftPreserved(
                "    Assert-TalosFileBytes -Path $propertiesBackupPath -Expected $propertiesOriginalBytes"
                        + " -Description \"gradle.properties backup\"",
                false,
                true);
    }

    private void assertExternalDriftPreserved(
            String injectionAnchor,
            boolean editProperties,
            boolean propertiesAlreadyPromoted) throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, "talosVersion=0.9.9\njavaVersion=21\n");
        writeUtf8(changelog, validChangelog("0.9.9"));
        byte[] propertiesBefore = Files.readAllBytes(properties);
        byte[] changelogBefore = Files.readAllBytes(changelog);
        Path editedPath = editProperties ? properties : changelog;
        byte[] externalBytes = (readUtf8(editedPath) + "# External edit must survive.\n")
                .getBytes(StandardCharsets.UTF_8);

        String script = readUtf8(SCRIPT).replace("\r\n", "\n");
        int anchorIndex = script.indexOf(injectionAnchor);
        assertTrue(anchorIndex >= 0, "drift fixture injection anchor must exist");
        assertEquals(anchorIndex, script.lastIndexOf(injectionAnchor),
                "drift fixture injection anchor must be unique");
        String promotionCheck = propertiesAlreadyPromoted
                ? "if (-not $propertiesPromoted -or $changelogPromoted) { throw 'Incorrect drift fixture phase.' }\n"
                        + "Assert-TalosFileBytes -Path $propertiesFullPath -Expected $updatedPropertiesBytes"
                        + " -Description 'Drift fixture promoted properties'\n"
                : "if ($propertiesPromoted -or $changelogPromoted) { throw 'Incorrect drift fixture phase.' }\n";
        String injection = promotionCheck
                + "[System.IO.File]::WriteAllBytes("
                + (editProperties ? "$propertiesFullPath" : "$changelogFullPath")
                + ", [System.Convert]::FromBase64String('"
                + Base64.getEncoder().encodeToString(externalBytes) + "'))";
        int injectionIndex = anchorIndex + injectionAnchor.length();
        Path fixtureScript = tempDir.resolve("bump-patch-drift-fixture.ps1");
        writeUtf8(fixtureScript, script.substring(0, injectionIndex) + "\n" + injection
                + script.substring(injectionIndex));

        ScriptResult result = runBumpPatch(fixtureScript, properties, changelog);

        assertNotEquals(0, result.exitCode(), result.output());
        String diagnostic = (editProperties ? "gradle.properties" : "CHANGELOG.md")
                + " preimage bytes changed unexpectedly";
        assertTrue(result.output().contains(diagnostic), result.output());
        assertTrue(result.output().contains("any promoted files were restored to authenticated originals"),
                result.output());
        assertFalse(result.output().contains("authenticated originals were restored"), result.output());
        assertArrayEquals(editProperties ? externalBytes : propertiesBefore, Files.readAllBytes(properties));
        assertArrayEquals(editProperties ? changelogBefore : externalBytes, Files.readAllBytes(changelog));
        assertNoOwnedArtifacts(tempDir);
    }

    @Test
    void restoresPropertiesWhenChangelogAtomicPromotionIsDeniedOnWindows() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"),
                "Windows no-share-delete fixture");
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, "talosVersion=0.9.9\njavaVersion=21\n");
        writeUtf8(changelog, validChangelog("0.9.9"));
        byte[] propertiesBefore = Files.readAllBytes(properties);
        byte[] changelogBefore = Files.readAllBytes(changelog);

        Set<OpenOption> lockOptions = Set.of(
                StandardOpenOption.READ,
                ExtendedOpenOption.NOSHARE_DELETE);
        SeekableByteChannel lock;
        try {
            lock = Files.newByteChannel(changelog, lockOptions);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false, "No-share-delete fixture unavailable: " + unsupported.getMessage());
            return;
        }

        ScriptResult result;
        try (lock) {
            result = runBumpPatch(properties, changelog);
        }
        if (result.exitCode() == 0) {
            assumeTrue(false, "Filesystem did not enforce the requested no-share-delete lock");
        }

        assertTrue(result.output().contains("any promoted files were restored to authenticated originals"),
                result.output());
        assertArrayEquals(propertiesBefore, Files.readAllBytes(properties));
        assertArrayEquals(changelogBefore, Files.readAllBytes(changelog));
        assertNoOwnedArtifacts(properties.getParent(), changelog.getParent());
    }

    private ScriptResult runBumpPatch(Path properties, Path changelog) throws Exception {
        return runBumpPatch(SCRIPT, properties, changelog);
    }

    private ScriptResult runBumpPatch(Path script, Path properties, Path changelog) throws Exception {
        String powershell = requirePowerShell(powershellExecutable());

        List<String> command = new ArrayList<>();
        command.add(powershell);
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(script.toString());
        command.add("-PropertiesPath");
        command.add(properties.toString());
        command.add("-ChangelogPath");
        command.add(changelog.toString());

        Path outputPath = Files.createTempFile(tempDir, "bump-patch-output-", ".log");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(outputPath.toFile())
                    .start();
            int exitCode = awaitExit(process, "bump-patch.ps1");
            String output = Files.readString(outputPath, StandardCharsets.UTF_8);
            return new ScriptResult(exitCode, output);
        } finally {
            deleteEventually(outputPath);
        }
    }

    private static String requirePowerShell(Optional<String> executable) {
        assumeTrue(executable.isPresent(),
                "PowerShell is unavailable; skipping script execution contract test.");
        return executable.orElseThrow();
    }

    @Test
    void missingPowerShellAbortsExecutionContractsInsteadOfFailing() {
        TestAbortedException skipped = assertThrows(TestAbortedException.class,
                () -> requirePowerShell(Optional.empty()));
        assertTrue(skipped.getMessage().contains("PowerShell is unavailable"));
    }

    @Test
    void availablePowerShellIsRetainedForExecutionContracts() {
        assertEquals("pinned-pwsh", requirePowerShell(Optional.of("pinned-pwsh")));
    }

    private Optional<String> powershellExecutable() throws InterruptedException {
        for (String candidate : List.of("pwsh", "powershell")) {
            Path outputPath = null;
            try {
                outputPath = Files.createTempFile(tempDir, "powershell-probe-", ".log");
                Process process = new ProcessBuilder(candidate, "-NoProfile", "-Command", "$PSVersionTable.PSVersion")
                        .redirectErrorStream(true)
                        .redirectOutput(outputPath.toFile())
                        .start();
                if (awaitExit(process, "PowerShell executable probe") == 0) {
                    return Optional.of(candidate);
                }
            } catch (IOException e) {
                // Try the next PowerShell executable name.
            } finally {
                if (outputPath != null) {
                    try {
                        deleteEventually(outputPath);
                    } catch (IOException ignored) {
                        // The bounded child lifecycle matters more than disposable probe output.
                    }
                }
            }
        }
        return Optional.empty();
    }

    private int awaitExit(Process process, String description) throws InterruptedException {
        return awaitExit(process, description, PROCESS_TIMEOUT_SECONDS);
    }

    private int awaitExit(Process process, String description, long timeoutSeconds) throws InterruptedException {
        ProcessHandle root = process.toHandle();
        Set<ProcessHandle> observedDescendants = new LinkedHashSet<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                retainDescendants(root, observedDescendants);
                Thread.sleep(25);
            }
            retainDescendants(root, observedDescendants);
            if (!process.isAlive()) {
                long drainDeadline = System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(TERMINATION_TIMEOUT_SECONDS);
                while (System.nanoTime() < drainDeadline) {
                    retainDescendants(root, observedDescendants);
                    if (observedDescendants.stream().noneMatch(ProcessHandle::isAlive)) break;
                    Thread.sleep(25);
                }
                retainDescendants(root, observedDescendants);
            }
        } catch (InterruptedException interrupted) {
            terminateAfterFailure(process, root, observedDescendants, interrupted);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (RuntimeException | Error failure) {
            terminateAfterFailure(process, root, observedDescendants, failure);
            throw failure;
        }
        if (!process.isAlive()) {
            List<Long> survivors = observedDescendants.stream()
                    .filter(ProcessHandle::isAlive)
                    .map(ProcessHandle::pid)
                    .toList();
            if (!survivors.isEmpty()) {
                AssertionError failure = new AssertionError(
                        description + " left live descendant processes: " + survivors);
                terminateAfterFailure(process, root, observedDescendants, failure);
                throw failure;
            }
            return process.exitValue();
        }
        AssertionError failure = new AssertionError(
                description + " exceeded the " + timeoutSeconds + " second timeout");
        terminateAfterFailure(process, root, observedDescendants, failure);
        throw failure;
    }

    private void retainDescendants(ProcessHandle root, Set<ProcessHandle> observedDescendants) {
        FixtureProcessOwnership.retainDescendants(root, observedDescendants);
    }

    private void terminateAfterFailure(
            Process process,
            ProcessHandle root,
            Set<ProcessHandle> observedDescendants,
            Throwable originalFailure) {
        try {
            terminateProcessTree(process, root, observedDescendants);
        } catch (RuntimeException | Error cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    private void terminateProcessTree(
            Process process,
            ProcessHandle root,
            Set<ProcessHandle> observedDescendants) {
        boolean restoreInterrupt = Thread.interrupted();
        RuntimeException inspectionFailure = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINATION_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                try {
                    retainDescendants(root, observedDescendants);
                } catch (RuntimeException failure) {
                    if (inspectionFailure == null) inspectionFailure = failure;
                }
                for (ProcessHandle descendant : List.copyOf(observedDescendants)) {
                    if (descendant.isAlive()) {
                        try {
                            descendant.destroy();
                        } catch (RuntimeException failure) {
                            if (inspectionFailure == null) inspectionFailure = failure;
                        }
                    }
                }
                if (process.isAlive()) {
                    try {
                        process.destroy();
                    } catch (RuntimeException failure) {
                        if (inspectionFailure == null) inspectionFailure = failure;
                    }
                }
                if (!process.isAlive() && observedDescendants.stream().noneMatch(ProcessHandle::isAlive)) {
                    if (inspectionFailure != null) {
                        throw new AssertionError(
                                "process tree inspection was incomplete during termination",
                                inspectionFailure);
                    }
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                if (Thread.interrupted()) restoreInterrupt = true;
            }

            try {
                retainDescendants(root, observedDescendants);
            } catch (RuntimeException failure) {
                if (inspectionFailure == null) inspectionFailure = failure;
            }
            for (ProcessHandle descendant : List.copyOf(observedDescendants)) {
                if (descendant.isAlive()) {
                    try {
                        descendant.destroyForcibly();
                    } catch (RuntimeException failure) {
                        if (inspectionFailure == null) inspectionFailure = failure;
                    }
                }
            }
            if (process.isAlive()) {
                try {
                    process.destroyForcibly();
                } catch (RuntimeException failure) {
                    if (inspectionFailure == null) inspectionFailure = failure;
                }
            }

            long forcedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINATION_TIMEOUT_SECONDS);
            while (System.nanoTime() < forcedDeadline) {
                try {
                    retainDescendants(root, observedDescendants);
                } catch (RuntimeException failure) {
                    if (inspectionFailure == null) inspectionFailure = failure;
                }
                for (ProcessHandle descendant : List.copyOf(observedDescendants)) {
                    if (descendant.isAlive()) {
                        try {
                            descendant.destroyForcibly();
                        } catch (RuntimeException failure) {
                            if (inspectionFailure == null) inspectionFailure = failure;
                        }
                    }
                }
                if (process.isAlive()) {
                    try {
                        process.destroyForcibly();
                    } catch (RuntimeException failure) {
                        if (inspectionFailure == null) inspectionFailure = failure;
                    }
                }
                if (!process.isAlive() && observedDescendants.stream().noneMatch(ProcessHandle::isAlive)) {
                    if (inspectionFailure != null) {
                        throw new AssertionError(
                                "process tree inspection was incomplete during forced termination",
                                inspectionFailure);
                    }
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                if (Thread.interrupted()) restoreInterrupt = true;
            }

            List<Long> survivors = new ArrayList<>();
            if (process.isAlive()) survivors.add(process.pid());
            observedDescendants.stream()
                    .filter(ProcessHandle::isAlive)
                    .map(ProcessHandle::pid)
                    .forEach(survivors::add);
            AssertionError failure = new AssertionError(
                    "process tree did not terminate after forced destruction; survivors=" + survivors);
            if (inspectionFailure != null) failure.addSuppressed(inspectionFailure);
            throw failure;
        } finally {
            if (restoreInterrupt || Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void assertRejectedWithoutMutation(
            String propertiesContent,
            String changelogContent,
            String expectedDiagnostic) throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, propertiesContent);
        writeUtf8(changelog, changelogContent);
        byte[] propertiesBefore = Files.readAllBytes(properties);
        byte[] changelogBefore = Files.readAllBytes(changelog);

        ScriptResult result = runBumpPatch(properties, changelog);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(expectedDiagnostic), result.output());
        assertArrayEquals(propertiesBefore, Files.readAllBytes(properties));
        assertArrayEquals(changelogBefore, Files.readAllBytes(changelog));
        assertNoOwnedArtifacts(properties.getParent(), changelog.getParent());
    }

    private ProcessHandle awaitChildProcessReady(
            Process parent,
            Path outputPath,
            String description) throws InterruptedException, IOException {
        ProcessHandle root = parent.toHandle();
        Set<ProcessHandle> observedDescendants = new LinkedHashSet<>();
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(FIXTURE_READINESS_TIMEOUT_SECONDS);
        try {
            while (System.nanoTime() < deadline) {
                retainDescendants(root, observedDescendants);
                byte[] output = Files.readAllBytes(outputPath);
                int lineFeed = -1;
                for (int index = 0; index < output.length; index++) {
                    if (output[index] == (byte) '\n') {
                        lineFeed = index;
                        break;
                    }
                }
                if (lineFeed >= 0) {
                    String firstLine = new String(output, 0, lineFeed, StandardCharsets.UTF_8).trim();
                    if (!firstLine.matches("[1-9][0-9]*")) {
                        throw new AssertionError(description + " emitted an invalid child PID: " + firstLine);
                    }
                    long childPid;
                    try {
                        childPid = Long.parseLong(firstLine);
                    } catch (NumberFormatException invalidPid) {
                        throw new AssertionError(
                                description + " emitted an out-of-range child PID: " + firstLine,
                                invalidPid);
                    }
                    ProcessHandle child = ProcessHandle.of(childPid)
                            .filter(ProcessHandle::isAlive)
                            .orElseThrow(() -> new AssertionError(
                                    description + " child PID was not live: " + childPid));
                    long observedParentPid = child.parent().map(ProcessHandle::pid).orElse(-1L);
                    if (observedParentPid != parent.pid()) {
                        throw new AssertionError(description + " child PID " + childPid
                                + " had parent " + observedParentPid + " instead of " + parent.pid());
                    }
                    observedDescendants.add(child);
                    return child;
                }
                if (!parent.isAlive()) {
                    throw new AssertionError(description + " exited before emitting a complete child PID");
                }
                Thread.sleep(25);
            }
            throw new AssertionError(description + " did not emit a complete child PID within "
                    + FIXTURE_READINESS_TIMEOUT_SECONDS + " seconds");
        } catch (InterruptedException interrupted) {
            terminateAfterFailure(parent, root, observedDescendants, interrupted);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (IOException | RuntimeException | Error failure) {
            terminateAfterFailure(parent, root, observedDescendants, failure);
            throw failure;
        }
    }

    @Test
    void timeoutTerminatesAndDrainsTheObservedPowerShellProcessTree() throws Exception {
        String executable = requirePowerShell(powershellExecutable());
        Path outputPath = Files.createTempFile(tempDir, "bump-timeout-tree-", ".log");
        String command = "$child = Start-Process -FilePath '"
                + executable.replace("'", "''")
                + "' -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-Command',"
                + "'Start-Sleep -Seconds 60') -PassThru; "
                + "[Console]::Out.WriteLine($child.Id); [Console]::Out.Flush(); "
                + "Start-Sleep -Seconds 60";
        Process process = new ProcessBuilder(
                executable,
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                command)
                .redirectErrorStream(true)
                .redirectOutput(outputPath.toFile())
                .start();
        ProcessHandle child = null;
        Throwable primaryFailure = null;
        try {
            child = awaitChildProcessReady(process, outputPath, "PowerShell timeout tree fixture");
            AssertionError failure = assertThrows(
                    AssertionError.class,
                    () -> awaitExit(process, "PowerShell timeout tree fixture", 1));

            assertTrue(failure.getMessage().contains("exceeded the 1 second timeout"), failure.getMessage());
            assertFalse(child.isAlive(), "timed-out descendant must not survive");
            assertFalse(process.isAlive(), "timed-out parent must not survive");
        } catch (InterruptedException failure) {
            primaryFailure = failure;
            Thread.currentThread().interrupt();
            throw failure;
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                if (process.isAlive() || (child != null && child.isAlive())) {
                    Set<ProcessHandle> observedDescendants = new LinkedHashSet<>();
                    if (child != null) observedDescendants.add(child);
                    terminateProcessTree(process, process.toHandle(), observedDescendants);
                }
            } catch (RuntimeException | Error cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
            try {
                deleteEventually(outputPath);
            } catch (IOException cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    @Test
    void interruptionTerminatesTheObservedPowerShellProcessTreeAndRestoresInterruptStatus() throws Exception {
        String executable = requirePowerShell(powershellExecutable());
        Path outputPath = Files.createTempFile(tempDir, "bump-interrupt-tree-", ".log");
        String command = "$child = Start-Process -FilePath '"
                + executable.replace("'", "''")
                + "' -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-Command',"
                + "'Start-Sleep -Seconds 60') -PassThru; "
                + "[Console]::Out.WriteLine($child.Id); [Console]::Out.Flush(); "
                + "Start-Sleep -Seconds 60";
        Process process = new ProcessBuilder(
                executable,
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                command)
                .redirectErrorStream(true)
                .redirectOutput(outputPath.toFile())
                .start();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                awaitExit(process, "PowerShell interruption tree fixture");
                waiterFailure.set(new AssertionError("interrupted wait unexpectedly returned"));
            } catch (InterruptedException expected) {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                waiterFailure.set(failure);
            }
        }, "bump-patch-interruption-waiter");
        waiter.start();
        ProcessHandle child = null;
        Throwable primaryFailure = null;
        try {
            child = awaitChildProcessReady(process, outputPath, "PowerShell interruption tree fixture");

            waiter.interrupt();
            waiter.join(TimeUnit.SECONDS.toMillis(2 * TERMINATION_TIMEOUT_SECONDS + 5));

            assertFalse(waiter.isAlive(), "interrupted waiter must finish bounded teardown");
            assertTrue(interruptRestored.get(), "awaitExit must restore interrupt status before rethrowing");
            assertEquals(null, waiterFailure.get(), () -> String.valueOf(waiterFailure.get()));
            assertFalse(child.isAlive(), "interrupted descendant must not survive");
            assertFalse(process.isAlive(), "interrupted parent must not survive");
        } catch (InterruptedException failure) {
            primaryFailure = failure;
            Thread.currentThread().interrupt();
            throw failure;
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            boolean restoreInterrupt = Thread.interrupted();
            Throwable cleanupFailure = null;
            long joinMillis = TimeUnit.SECONDS.toMillis(2 * TERMINATION_TIMEOUT_SECONDS + 5);
            try {
                waiter.interrupt();
                long firstJoinDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(joinMillis);
                while (waiter.isAlive() && System.nanoTime() < firstJoinDeadline) {
                    try {
                        waiter.join(25);
                    } catch (InterruptedException interrupted) {
                        restoreInterrupt = true;
                    }
                }
                if (process.isAlive() || (child != null && child.isAlive())) {
                    Set<ProcessHandle> observedDescendants = new LinkedHashSet<>();
                    if (child != null) observedDescendants.add(child);
                    try {
                        terminateProcessTree(process, process.toHandle(), observedDescendants);
                    } catch (RuntimeException | Error failure) {
                        cleanupFailure = failure;
                    }
                }
                waiter.interrupt();
                long secondJoinDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(joinMillis);
                while (waiter.isAlive() && System.nanoTime() < secondJoinDeadline) {
                    try {
                        waiter.join(25);
                    } catch (InterruptedException interrupted) {
                        restoreInterrupt = true;
                    }
                }
                if (waiter.isAlive()) {
                    AssertionError failure = new AssertionError(
                            "interrupted waiter survived bounded fallback teardown");
                    if (cleanupFailure == null) {
                        cleanupFailure = failure;
                    } else {
                        cleanupFailure.addSuppressed(failure);
                    }
                }
                try {
                    deleteEventually(outputPath);
                } catch (IOException failure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure;
                    } else {
                        cleanupFailure.addSuppressed(failure);
                    }
                }
            } finally {
                if (restoreInterrupt || Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else if (cleanupFailure instanceof IOException ioFailure) {
                    throw ioFailure;
                } else if (cleanupFailure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                } else if (cleanupFailure instanceof Error errorFailure) {
                    throw errorFailure;
                } else {
                    throw new AssertionError("unexpected process-fixture cleanup failure", cleanupFailure);
                }
            }
        }
    }

    private void assertRejectedBytesWithoutMutation(
            byte[] propertiesContent,
            byte[] changelogContent) throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        Files.write(properties, propertiesContent);
        Files.write(changelog, changelogContent);

        ScriptResult result = runBumpPatch(properties, changelog);

        assertNotEquals(0, result.exitCode(), result.output());
        assertArrayEquals(propertiesContent, Files.readAllBytes(properties));
        assertArrayEquals(changelogContent, Files.readAllBytes(changelog));
        assertNoOwnedArtifacts(properties.getParent(), changelog.getParent());
    }

    private String validChangelog(String topReleasedVersion) {
        return """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Prepared the next candidate.

                ## [%s] - 2026-05-15

                ### Changed
                - Declared the previous beta candidate.
                """.formatted(topReleasedVersion);
    }

    private LocalDate candidateDate(String changelog, String version) {
        Pattern heading = Pattern.compile(
                "(?m)^## \\[" + Pattern.quote(version) + "\\] - (\\d{4}-\\d{2}-\\d{2})$");
        Matcher match = heading.matcher(changelog);
        assertTrue(match.find(), changelog);
        return LocalDate.parse(match.group(1));
    }

    private void assertDateWithin(LocalDate actual, LocalDate earliest, LocalDate latest) {
        assertFalse(actual.isBefore(earliest),
                () -> "candidate date " + actual + " predates test start " + earliest);
        assertFalse(actual.isAfter(latest),
                () -> "candidate date " + actual + " follows test completion " + latest);
    }

    private void assertNoOwnedArtifacts(Path... directories) throws IOException {
        for (Path directory : directories) {
            try (var children = Files.list(directory)) {
                List<Path> residue = children
                        .filter(path -> path.getFileName().toString().contains(".talos-bump-"))
                        .toList();
                assertTrue(residue.isEmpty(), "candidate-bump stage or backup residue: " + residue);
            }
        }
    }

    private void deleteEventually(Path path) throws IOException {
        IOException lastFailure = null;
        boolean restoreInterrupt = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINATION_TIMEOUT_SECONDS);
        try {
            do {
                try {
                    Files.deleteIfExists(path);
                    return;
                } catch (IOException failure) {
                    lastFailure = failure;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                if (Thread.interrupted()) restoreInterrupt = true;
            } while (System.nanoTime() < deadline);
        } finally {
            if (restoreInterrupt || Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }
        }
        throw lastFailure;
    }

    private void writeUtf8(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String readUtf8(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private void assertCanonicalUtf8Lf(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), bytes);
        assertFalse(bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF,
                "UTF-8 BOM must be absent");
        assertFalse(content.contains("\r"), "output must be LF-only");
        assertTrue(content.endsWith("\n"), "output must end with LF");
        assertFalse(content.endsWith("\n\n"), "output must have exactly one final LF");
    }

    private record ScriptResult(int exitCode, String output) {
    }
}
