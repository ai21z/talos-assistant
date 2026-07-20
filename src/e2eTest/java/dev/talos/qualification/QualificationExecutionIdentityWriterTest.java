package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationExecutionIdentityWriterTest {
    private static final String COMMIT = "a".repeat(40);
    private static final String VERSION = "0.10.8";

    @TempDir
    Path temp;

    @Test
    void createsExactManifestsAndAnIdenticalSecondInvocationIsANoOp() throws Exception {
        Seed seed = seed("success", false);

        QualificationExecutionIdentityWriter.Result first = write(seed);
        byte[] ledger = Files.readAllBytes(first.executionInputLedger());
        byte[] harness = Files.readAllBytes(first.harnessManifest());
        byte[] runtime = Files.readAllBytes(first.runtimeIdentity());
        QualificationExecutionIdentityWriter.Result second = write(seed);

        assertTrue(first.executionInputLedgerCreated());
        assertTrue(first.harnessManifestCreated());
        assertTrue(first.runtimeIdentityCreated());
        assertFalse(second.executionInputLedgerCreated());
        assertFalse(second.harnessManifestCreated());
        assertFalse(second.runtimeIdentityCreated());
        assertArrayEquals(ledger, Files.readAllBytes(second.executionInputLedger()));
        assertArrayEquals(harness, Files.readAllBytes(second.harnessManifest()));
        assertArrayEquals(runtime, Files.readAllBytes(second.runtimeIdentity()));
        String ledgerText = new String(ledger, StandardCharsets.UTF_8);
        assertTrue(ledgerText.contains("dependency.jar"));
        assertFalse(ledgerText.contains(temp.toString()));
        assertTrue(first.executionInputLedgerSha256().matches("[0-9a-f]{64}"));
        assertTrue(first.harnessManifestSha256().matches("[0-9a-f]{64}"));
        assertTrue(first.runtimeIdentitySha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void printedExecutionInputIdentityIsStrictlyPathFree() {
        Map<String, Object> identity =
                QualificationExecutionInputIdentityMain.currentIdentity();

        assertEquals(Set.of(
                        "finalizerImplementationSha256",
                        "finalizerClasspathSha256",
                        "finalizerClasspathSizeBytes",
                        "finalizerClasspathEntryCount"),
                identity.keySet());
        assertTrue(identity.get("finalizerImplementationSha256").toString()
                .matches("[0-9a-f]{64}"));
        assertTrue(identity.get("finalizerClasspathSha256").toString()
                .matches("[0-9a-f]{64}"));
        assertFalse(QualificationJson.write(identity).contains(temp.toString()));
    }

    @Test
    void refusesCommitDriftAndLeavesBothOutputsAbsent() throws Exception {
        Seed seed = seed("commit", false);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                QualificationExecutionIdentityWriter.write(request(seed, "b".repeat(40))));

        assertTrue(failure.getMessage().contains("campaign and current commit differ"),
                failure::getMessage);
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void refusesDifferentBuiltAndInstalledBytes() throws Exception {
        Seed seed = seed("runtime-drift", false);
        writeIdentityJar(seed.installedTalos(), VERSION, COMMIT, "different");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> write(seed));

        assertTrue(failure.getMessage().contains("artifact bytes differ"), failure::getMessage);
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void refusesActualModelOrExecutableHashDrift() throws Exception {
        for (String kind : new String[] {"model", "engine"}) {
            Seed seed = seed("actual-" + kind, false);
            Path target = kind.equals("model") ? seed.model() : seed.engine();
            Files.writeString(target, "changed bytes", StandardCharsets.UTF_8);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> write(seed));

            assertTrue(failure.getMessage().contains(kind.equals("model")
                            ? "model artifact hash mismatch" : "engine executable hash mismatch"),
                    failure::getMessage);
            assertOutputsAbsent(seed.packet().root());
        }
    }

    @Test
    void refusesFrozenCandidateCalibrationEngineAndMachineManifestDrift() throws Exception {
        record Mutation(String name, String path, String field, String expected) {}
        List<Mutation> cases = List.of(
                new Mutation("candidate", "candidates/qwen14/candidate.json", "license",
                        "frozen candidate manifest hash mismatch"),
                new Mutation("calibration", "candidates/qwen14/calibration-rules.json", "backend",
                        "campaign calibration hash mismatch"),
                new Mutation("engine", "engines/cuda.json", "offloadEvidence",
                        "frozen engine manifest hash mismatch"),
                new Mutation("machine", "machines/reference.json", "osVersion",
                        "frozen machine manifest hash mismatch"));
        for (Mutation mutation : cases) {
            Seed seed = seed("manifest-" + mutation.name(), false);
            Path path = seed.packet().root().resolve(mutation.path());
            ObjectNode json = (ObjectNode) QualificationJson.readTree(path);
            json.put(mutation.field(), "changed-after-freeze");
            QualificationJson.write(path, json);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> write(seed));

            assertTrue(failure.getMessage().contains(mutation.expected()), failure::getMessage);
            assertOutputsAbsent(seed.packet().root());
        }
    }

    @Test
    void refusesUnsafeCaseCollidingHarnessInputs() throws Exception {
        Seed seed = seed("collision", false);
        QualificationExecutionIdentityWriter.HarnessInputSpecification original =
                QualificationJson.read(seed.inputs(),
                        QualificationExecutionIdentityWriter.HarnessInputSpecification.class);
        QualificationJson.write(seed.inputs(),
                new QualificationExecutionIdentityWriter.HarnessInputSpecification(
                        original.schemaName(), original.schemaVersion(), original.protocolVersion(),
                        original.talosCommit(), original.finalizerImplementationSha256(),
                        original.finalizerClasspathSha256(),
                        original.builtTalosArtifactSha256(),
                        original.installedTalosArtifactSha256(),
                        original.candidateManifestSha256(),
                        original.calibrationRulesSha256(),
                        original.engineManifestSha256(),
                        original.machineManifestSha256(),
                        original.modelArtifactSha256(),
                        original.engineExecutableSha256(),
                        original.installedRuntimeClasspath(),
                        original.harnessRoles(),
                        original.packetFiles(),
                        original.repositoryFiles(),
                        Map.of("SRC/INPUT.TXT", hash(seed.repositoryRoot().resolve("src/input.txt")))));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> write(seed));

        assertTrue(failure.getMessage().contains("paths collide"), failure::getMessage);
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void harnessInputSpecificationRejectsScalarCoercionBeforeIdentityPublication()
            throws Exception {
        record ScalarCase(String id, String field, boolean textual) {}
        List<ScalarCase> cases = List.of(
                new ScalarCase("textual-schema-version", "schemaVersion", true),
                new ScalarCase("fractional-schema-version", "schemaVersion", false),
                new ScalarCase("textual-protocol-version", "protocolVersion", true),
                new ScalarCase("fractional-protocol-version", "protocolVersion", false));
        List<String> coercionsAccepted = new java.util.ArrayList<>();

        for (ScalarCase testCase : cases) {
            Seed seed = seed("input-scalar-" + testCase.id(), false);
            ObjectNode specification = (ObjectNode) QualificationJson.readTree(seed.inputs());
            replaceIntegralWithWrongScalarType(
                    specification, testCase.field(), testCase.textual());
            QualificationJson.write(seed.inputs(), specification);
            byte[] malformedInput = Files.readAllBytes(seed.inputs());

            try {
                write(seed);
                coercionsAccepted.add(testCase.id());
            } catch (IOException rejected) {
                assertOutputsAbsent(seed.packet().root());
            }
            assertArrayEquals(malformedInput, Files.readAllBytes(seed.inputs()),
                    "identity generation must not rewrite its input specification");
        }

        assertTrue(coercionsAccepted.isEmpty(),
                () -> "harness input scalar coercions reached identity publication: "
                        + coercionsAccepted);
    }

    @Test
    void malformedHarnessInputStructureIsRejectedBeforeIdentityPublication()
            throws Exception {
        record InvalidInput(
                String id,
                java.util.function.Consumer<ObjectNode> mutation,
                boolean appendTrailingDocument) {}
        List<InvalidInput> cases = List.of(
                new InvalidInput("null-primitive",
                        object -> object.putNull("schemaVersion"), false),
                new InvalidInput("missing-creator",
                        object -> object.remove("schemaName"), false),
                new InvalidInput("unknown-property",
                        object -> object.put("unowned", true), false),
                new InvalidInput("trailing-document",
                        object -> {}, true));

        for (InvalidInput testCase : cases) {
            Seed seed = seed("input-structure-" + testCase.id(), false);
            ObjectNode specification = (ObjectNode) QualificationJson.readTree(seed.inputs());
            testCase.mutation().accept(specification);
            QualificationJson.write(seed.inputs(), specification);
            if (testCase.appendTrailingDocument()) {
                Files.writeString(
                        seed.inputs(),
                        Files.readString(seed.inputs(), StandardCharsets.UTF_8) + "{}\n",
                        StandardCharsets.UTF_8);
            }
            byte[] malformedInput = Files.readAllBytes(seed.inputs());

            assertThrows(IOException.class, () -> write(seed), testCase.id());

            assertOutputsAbsent(seed.packet().root());
            assertArrayEquals(malformedInput, Files.readAllBytes(seed.inputs()),
                    "identity generation must not rewrite malformed input: " + testCase.id());
        }
    }

    @Test
    void executionInputLedgerRejectsScalarCoercionAfterIndependentHashRebinding()
            throws Exception {
        record ScalarCase(String id, String field, boolean artifactField, boolean textual) {}
        List<ScalarCase> cases = List.of(
                new ScalarCase("textual-schema-version", "schemaVersion", false, true),
                new ScalarCase("fractional-schema-version", "schemaVersion", false, false),
                new ScalarCase("textual-artifact-size", "sizeBytes", true, true),
                new ScalarCase("fractional-artifact-size", "sizeBytes", true, false));
        List<String> coercionsAccepted = new java.util.ArrayList<>();

        for (ScalarCase testCase : cases) {
            Seed seed = seed("ledger-scalar-" + testCase.id(), false);
            QualificationExecutionIdentityWriter.Result written = write(seed);
            ObjectNode ledger = (ObjectNode) QualificationJson.readTree(
                    written.executionInputLedger());
            ObjectNode scalarOwner = testCase.artifactField()
                    ? (ObjectNode) ledger.path("artifacts").path(0)
                    : ledger;
            replaceIntegralWithWrongScalarType(
                    scalarOwner, testCase.field(), testCase.textual());
            rebindExecutionLedgerIdentity(written, ledger);

            byte[] ledgerBefore = Files.readAllBytes(written.executionInputLedger());
            byte[] harnessBefore = Files.readAllBytes(written.harnessManifest());
            byte[] runtimeBefore = Files.readAllBytes(written.runtimeIdentity());
            try {
                verify(seed);
                coercionsAccepted.add(testCase.id());
            } catch (IOException rejected) {
                // Exact JSON type rejection is the expected pre-authority outcome.
            }
            assertArrayEquals(ledgerBefore, Files.readAllBytes(written.executionInputLedger()));
            assertArrayEquals(harnessBefore, Files.readAllBytes(written.harnessManifest()));
            assertArrayEquals(runtimeBefore, Files.readAllBytes(written.runtimeIdentity()));
        }

        assertTrue(coercionsAccepted.isEmpty(),
                () -> "rebound execution-ledger scalar coercions passed verification: "
                        + coercionsAccepted);
    }

    @Test
    void refusesCampaignCoordinateRejectedByCanonicalPublicationNamespaceBeforeWriting()
            throws Exception {
        Seed seed = seed("campaign-namespace", false);
        Path candidatePath = seed.packet().root().resolve("candidates/qwen14/candidate.json");
        Path calibrationPath = seed.packet().root().resolve(
                "candidates/qwen14/calibration-rules.json");
        Path campaignPath = seed.packet().root().resolve("campaign.json");

        ObjectNode candidate = (ObjectNode) QualificationJson.readTree(candidatePath);
        candidate.put("alias", "qwen14.");
        QualificationJson.write(candidatePath, candidate);
        ObjectNode calibration = (ObjectNode) QualificationJson.readTree(calibrationPath);
        calibration.put("candidateAlias", "qwen14.");
        QualificationJson.write(calibrationPath, calibration);
        ObjectNode campaign = (ObjectNode) QualificationJson.readTree(campaignPath);
        campaign.put("candidateAlias", "qwen14.");
        campaign.put("calibrationRulesSha256", hash(calibrationPath));
        QualificationJson.write(campaignPath, campaign);
        ObjectNode inputs = (ObjectNode) QualificationJson.readTree(seed.inputs());
        inputs.put("candidateManifestSha256", hash(candidatePath));
        inputs.put("calibrationRulesSha256", hash(calibrationPath));
        QualificationJson.write(seed.inputs(), inputs);

        assertThrows(IllegalArgumentException.class, () ->
                QualificationArtifactPublication.requireWritableCampaign(
                        seed.packet().root(), QualificationTestPacket.protocol6Catalog()));
        assertThrows(IllegalStateException.class, () -> write(seed));
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void refusesAnOmittedCanonicalHarnessRole() throws Exception {
        Seed seed = seed("missing-role", false);
        ObjectNode specification = (ObjectNode) QualificationJson.readTree(seed.inputs());
        ((ObjectNode) specification.path("harnessRoles")).remove("TIMESTAMP_POLICY");
        QualificationJson.write(seed.inputs(), specification);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> write(seed));

        assertTrue(failure.getMessage().contains(
                "required harness role is missing or misbound: TIMESTAMP_POLICY"),
                failure::getMessage);
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void canonicalHarnessRolesExcludeLegacyPowerShellCausalInference() {
        assertFalse(QualificationExecutionIdentityWriter.requiredHarnessRoles()
                .containsValue("tools/trial-stop-policy.ps1"));
    }

    @Test
    void refusesPacketLocalExecutedScriptDriftEvenWhenRepositoryInputsAreUnchanged()
            throws Exception {
        Seed seed = seed("packet-script-drift", false);
        Files.writeString(seed.packet().root().resolve("tools/finalize-trial.ps1"),
                "changed executed script\n", StandardCharsets.UTF_8);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> write(seed));

        assertTrue(failure.getMessage().contains(
                "packet harness input hash mismatch: tools/finalize-trial.ps1"),
                failure::getMessage);
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void refusesLauncherOrderDriftAndUnlistedInstalledLibraries() throws Exception {
        Seed order = seed("launcher-order", false);
        Path bat = order.installedTalos().getParent().getParent().resolve("bin/talos.bat");
        Files.writeString(bat,
                "set CLASSPATH=%APP_HOME%\\lib\\dependency.jar;%APP_HOME%\\lib\\talos.jar\r\n",
                StandardCharsets.UTF_8);

        IllegalStateException orderFailure = assertThrows(
                IllegalStateException.class, () -> write(order));
        assertTrue(orderFailure.getMessage().contains("different runtime classpaths"),
                orderFailure::getMessage);
        assertOutputsAbsent(order.packet().root());

        Seed extra = seed("extra-library", false);
        writeIdentityJar(extra.installedTalos().getParent().resolve("extra.jar"),
                VERSION, COMMIT, "extra");

        IllegalStateException extraFailure = assertThrows(
                IllegalStateException.class, () -> write(extra));
        assertTrue(extraFailure.getMessage().contains("exact installed lib contents"),
                extraFailure::getMessage);
        assertOutputsAbsent(extra.packet().root());
    }

    @Test
    void refusesConflictingExistingOutputWithoutReplacingIt() throws Exception {
        Seed seed = seed("existing", false);
        Path harness = seed.packet().root().resolve(QualificationExecutionIdentity.HARNESS_FILE);
        byte[] conflict = "historical conflict\n".getBytes(StandardCharsets.UTF_8);
        Files.write(harness, conflict);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> write(seed));

        assertTrue(failure.getMessage().contains("conflicts with derived exact bytes"),
                failure::getMessage);
        assertArrayEquals(conflict, Files.readAllBytes(harness));
        assertFalse(Files.exists(seed.packet().root().resolve(
                QualificationExecutionIdentity.RUNTIME_FILE)));
    }

    @Test
    void laterExecutionLedgerMutationBreaksTheHarnessVerificationEdge() throws Exception {
        Seed seed = seed("ledger-mutation", false);
        QualificationExecutionIdentityWriter.Result result = write(seed);
        Files.writeString(result.executionInputLedger(), "{}\n", StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation validation =
                QualificationEvidenceValidator.validateCalibrationPreflight(
                        seed.packet().root(), QualificationTestPacket.protocol6Catalog());

        assertFalse(validation.valid());
        assertTrue(validation.findings().stream().anyMatch(finding ->
                        finding.contains("execution identity is invalid")
                                && finding.contains("execution-input-ledger.json")),
                () -> String.join("\n", validation.findings()));
    }

    @Test
    void refusesFirstWriteAfterAnyRunEvidence() throws Exception {
        Seed seed = seed("historical", false);
        Path runEvidence = seed.packet().root().resolve("runs/one/trace.json");
        Files.createDirectories(runEvidence.getParent());
        Files.writeString(runEvidence, "{}\n", StandardCharsets.UTF_8);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> write(seed));

        assertTrue(failure.getMessage().contains("historical or started packet"),
                failure::getMessage);
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void refusesRegenerationAfterRunEvidenceWithoutReobservingExternalArtifacts()
            throws Exception {
        Seed seed = seed("historical-existing", false);
        write(seed);
        Path runEvidence = seed.packet().root().resolve("runs/one/trace.json");
        Files.createDirectories(runEvidence.getParent());
        Files.writeString(runEvidence, "{}\n", StandardCharsets.UTF_8);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> QualificationExecutionIdentityWriter.write(
                        request(seed, COMMIT), path -> {
                            throw new AssertionError(
                                    "historical identity regeneration reread " + path);
                        }));

        assertTrue(failure.getMessage().contains("historical or started packet"),
                failure::getMessage);
    }

    @Test
    void adaptedLayoutRequiresExactPreflightManifestAndEvidenceBytes() throws Exception {
        Seed seed = seed("preflight", true);
        Files.writeString(seed.packet().root().resolve("preflight/direct-layout-proof.json"),
                "changed after manifest\n", StandardCharsets.UTF_8);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> write(seed));

        assertTrue(failure.getMessage().contains("preflight file hash mismatch"),
                failure::getMessage);
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void artifactHashingStreamsAcrossMultipleBufferWindows() throws Exception {
        Path artifact = temp.resolve("multi-chunk.gguf");
        byte[] bytes = new byte[(3 * 1024 * 1024) + 17];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31);
        }
        Files.write(artifact, bytes);
        String expected = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));

        assertEquals(expected, QualificationExecutionIdentityWriter.sha256File(artifact));
    }

    @Test
    void externalArtifactsAreObservedOnceAndExistingIdentityVerificationIsReadOnly()
            throws Exception {
        Seed seed = seed("read-only-verification", false);
        Map<Path, Integer> observations = new HashMap<>();
        QualificationExecutionIdentityWriter.Result written =
                QualificationExecutionIdentityWriter.write(
                        request(seed, COMMIT), path -> {
                            Path normalized = path.toAbsolutePath().normalize();
                            observations.merge(normalized, 1, Integer::sum);
                            return QualificationExecutionIdentityWriter.sha256File(path);
                        });
        assertEquals(1, observations.get(seed.model().toAbsolutePath().normalize()));
        assertEquals(1, observations.get(seed.engine().toAbsolutePath().normalize()));
        assertEquals(2, observations.size());

        QualificationExecutionIdentityWriter.ExecutionInputLedger ledger =
                QualificationJson.read(
                        written.executionInputLedger(),
                        QualificationExecutionIdentityWriter.ExecutionInputLedger.class);
        assertEquals(2, ledger.externalArtifacts().size());
        for (QualificationExecutionIdentityWriter.ExternalArtifactReceipt receipt
                : ledger.externalArtifacts()) {
            assertEquals("STREAM_READ_WITH_METADATA_BRACKETS_V1",
                    receipt.hashObservationContract());
            assertTrue(receipt.postCaptureContinuityContract()
                    .startsWith("METADATA_CONTINUITY_"));
        }

        byte[] ledgerBefore = Files.readAllBytes(written.executionInputLedger());
        byte[] harnessBefore = Files.readAllBytes(written.harnessManifest());
        byte[] runtimeBefore = Files.readAllBytes(written.runtimeIdentity());
        Files.delete(seed.inputs());
        QualificationExecutionIdentityWriter.VerificationResult first = verify(seed);
        QualificationExecutionIdentityWriter.VerificationResult second = verify(seed);

        assertEquals(first, second);
        assertEquals(written.executionInputLedgerSha256(),
                first.executionInputLedgerSha256());
        assertEquals(
                QualificationExecutionIdentityWriter.EXTERNAL_ARTIFACT_VERIFICATION_SCOPE,
                first.externalArtifactVerificationScope());
        assertEquals("UNAVAILABLE", first.activeProcessLaunchReceipt());
        assertArrayEquals(ledgerBefore, Files.readAllBytes(written.executionInputLedger()));
        assertArrayEquals(harnessBefore, Files.readAllBytes(written.harnessManifest()));
        assertArrayEquals(runtimeBefore, Files.readAllBytes(written.runtimeIdentity()));
        assertEquals(1, observations.get(seed.model().toAbsolutePath().normalize()));
        assertEquals(1, observations.get(seed.engine().toAbsolutePath().normalize()));
    }

    @Test
    void readOnlyVerificationRejectsPostCaptureMetadataDrift() throws Exception {
        Seed seed = seed("metadata-drift", false);
        write(seed);
        long original = Files.getLastModifiedTime(seed.model()).toMillis();
        Files.setLastModifiedTime(seed.model(), FileTime.fromMillis(original + 2_000L));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> verify(seed));

        assertTrue(failure.getMessage().contains(
                "MODEL_ARTIFACT stable metadata changed after identity capture"),
                failure::getMessage);
    }

    @Test
    void readOnlyVerificationRejectsLiveRepositoryInstallAndBuildDrift() throws Exception {
        Seed repository = seed("verify-repository-drift", false);
        write(repository);
        Files.writeString(repository.repositoryRoot().resolve("src/input.txt"),
                "changed repository input\n", StandardCharsets.UTF_8);
        IllegalStateException repositoryFailure = assertThrows(
                IllegalStateException.class, () -> verify(repository));
        assertTrue(repositoryFailure.getMessage().contains(
                "repository harness input hash mismatch: src/input.txt"),
                repositoryFailure::getMessage);

        Seed install = seed("verify-install-drift", false);
        write(install);
        Path launcher = install.installedTalos().getParent().getParent().resolve("bin/talos");
        Files.writeString(launcher, Files.readString(launcher, StandardCharsets.UTF_8)
                + "# changed after freeze\n", StandardCharsets.UTF_8);
        IllegalStateException installFailure = assertThrows(
                IllegalStateException.class, () -> verify(install));
        assertTrue(installFailure.getMessage().contains("UNIX_LAUNCHER hash mismatch"),
                installFailure::getMessage);

        Seed built = seed("verify-built-drift", false);
        write(built);
        writeIdentityJar(built.builtTalos(), VERSION, COMMIT, "changed-built-bytes");
        IllegalStateException builtFailure = assertThrows(
                IllegalStateException.class, () -> verify(built));
        assertTrue(builtFailure.getMessage().contains(
                "built and installed Talos artifact bytes differ"),
                builtFailure::getMessage);
    }

    @Test
    void linkedModelAncestorIsRejectedBeforeHasherOrIdentityPublication() throws Exception {
        Seed seed = seed("linked-model-ancestor", false);
        Path linkedParent = temp.resolve("linked-model-parent");
        createSymbolicLinkOrAbort(linkedParent, seed.model().getParent());
        Path linkedModel = linkedParent.resolve(seed.model().getFileName());
        QualificationExecutionIdentityWriter.Request original = request(seed, COMMIT);
        QualificationExecutionIdentityWriter.Request linkedRequest =
                new QualificationExecutionIdentityWriter.Request(
                        original.packetRoot(),
                        original.repositoryRoot(),
                        original.builtTalosArtifact(),
                        original.installedTalosArtifact(),
                        linkedModel.toAbsolutePath(),
                        original.engineExecutable(),
                        original.harnessInputSpecification(),
                        original.currentCommit(),
                        original.currentVersion());
        java.util.concurrent.atomic.AtomicInteger observations =
                new java.util.concurrent.atomic.AtomicInteger();

        dev.talos.core.security.NoFollowPathPolicy.Violation failure = assertThrows(
                dev.talos.core.security.NoFollowPathPolicy.Violation.class,
                () -> QualificationExecutionIdentityWriter.write(
                        linkedRequest,
                        path -> {
                            observations.incrementAndGet();
                            return QualificationExecutionIdentityWriter.sha256File(path);
                        }));

        assertEquals(
                dev.talos.core.security.NoFollowPathPolicy.Reason.LINKED_COMPONENT,
                failure.reason());
        assertFalse(failure.getMessage().contains(seed.model().getParent().toString()));
        assertEquals(0, observations.get(),
                "linked external artifacts must be rejected before their bytes are observed");
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void linkedEngineAncestorIsRejectedBeforeHasherOrIdentityPublication()
            throws Exception {
        Seed seed = seed("linked-engine-ancestor", false);
        Path linkedParent = temp.resolve("linked-engine-parent");
        createSymbolicLinkOrAbort(linkedParent, seed.engine().getParent());
        QualificationExecutionIdentityWriter.Request original = request(seed, COMMIT);
        QualificationExecutionIdentityWriter.Request linkedRequest =
                new QualificationExecutionIdentityWriter.Request(
                        original.packetRoot(),
                        original.repositoryRoot(),
                        original.builtTalosArtifact(),
                        original.installedTalosArtifact(),
                        original.modelArtifact(),
                        linkedParent.resolve(seed.engine().getFileName()).toAbsolutePath(),
                        original.harnessInputSpecification(),
                        original.currentCommit(),
                        original.currentVersion());
        java.util.concurrent.atomic.AtomicInteger observations =
                new java.util.concurrent.atomic.AtomicInteger();

        dev.talos.core.security.NoFollowPathPolicy.Violation failure = assertThrows(
                dev.talos.core.security.NoFollowPathPolicy.Violation.class,
                () -> QualificationExecutionIdentityWriter.write(
                        linkedRequest,
                        path -> {
                            observations.incrementAndGet();
                            return QualificationExecutionIdentityWriter.sha256File(path);
                        }));

        assertEquals(
                dev.talos.core.security.NoFollowPathPolicy.Reason.LINKED_COMPONENT,
                failure.reason());
        assertEquals(0, observations.get());
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void linkedHarnessInputSpecificationIsRejectedBeforeIdentityPublication()
            throws Exception {
        Seed seed = seed("linked-harness-input", false);
        Path external = Files.copy(
                seed.inputs(),
                temp.resolve("T1170_EXTERNAL_HARNESS_INPUT_CANARY.json"));
        Files.delete(seed.inputs());
        createSymbolicLinkOrAbort(seed.inputs(), external);

        dev.talos.core.security.NoFollowPathPolicy.Violation failure = assertThrows(
                dev.talos.core.security.NoFollowPathPolicy.Violation.class,
                () -> write(seed));

        assertEquals(
                dev.talos.core.security.NoFollowPathPolicy.Reason.LINKED_COMPONENT,
                failure.reason());
        assertFalse(failure.getMessage().contains(external.toString()));
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void linkedDeclaredRepositoryInputIsRejectedBeforeIdentityPublication()
            throws Exception {
        Seed seed = seed("linked-repository-input", false);
        Path repositoryInput = seed.repositoryRoot().resolve("src/input.txt");
        Path external = Files.copy(
                repositoryInput,
                temp.resolve("T1170_EXTERNAL_REPOSITORY_INPUT_CANARY.txt"));
        Files.delete(repositoryInput);
        createSymbolicLinkOrAbort(repositoryInput, external);

        dev.talos.core.security.NoFollowPathPolicy.Violation failure = assertThrows(
                dev.talos.core.security.NoFollowPathPolicy.Violation.class,
                () -> write(seed));

        assertEquals(
                dev.talos.core.security.NoFollowPathPolicy.Reason.LINKED_COMPONENT,
                failure.reason());
        assertFalse(failure.getMessage().contains(external.toString()));
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void linkedInstalledClasspathEntryIsRejectedBeforeIdentityPublication()
            throws Exception {
        Seed seed = seed("linked-installed-classpath", false);
        Path dependency = seed.installedTalos().getParent().resolve("dependency.jar");
        Path external = Files.copy(
                dependency,
                temp.resolve("T1170_EXTERNAL_INSTALLED_CLASSPATH_CANARY.jar"));
        Files.delete(dependency);
        createSymbolicLinkOrAbort(dependency, external);

        dev.talos.core.security.NoFollowPathPolicy.Violation failure = assertThrows(
                dev.talos.core.security.NoFollowPathPolicy.Violation.class,
                () -> write(seed));

        assertEquals(
                dev.talos.core.security.NoFollowPathPolicy.Reason.LINKED_COMPONENT,
                failure.reason());
        assertFalse(failure.getMessage().contains(external.toString()));
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void linkedInstalledLauncherIsRejectedBeforeIdentityPublication()
            throws Exception {
        Seed seed = seed("linked-installed-launcher", false);
        Path launcher = seed.installedTalos().getParent().getParent()
                .resolve("bin/talos");
        Path external = Files.copy(
                launcher,
                temp.resolve("T1170_EXTERNAL_INSTALLED_LAUNCHER_CANARY"));
        Files.delete(launcher);
        createSymbolicLinkOrAbort(launcher, external);

        dev.talos.core.security.NoFollowPathPolicy.Violation failure = assertThrows(
                dev.talos.core.security.NoFollowPathPolicy.Violation.class,
                () -> write(seed));

        assertEquals(
                dev.talos.core.security.NoFollowPathPolicy.Reason.LINKED_COMPONENT,
                failure.reason());
        assertFalse(failure.getMessage().contains(external.toString()));
        assertOutputsAbsent(seed.packet().root());
    }

    @Test
    void linkedGeneratedIdentityOutputCannotOverwriteExternalBytes()
            throws Exception {
        Seed seed = seed("linked-identity-output", false);
        byte[] externalBytes =
                "T1170_EXTERNAL_IDENTITY_OUTPUT_CANARY".getBytes(StandardCharsets.UTF_8);
        Path external = Files.write(
                temp.resolve("external-identity-output.json"),
                externalBytes);
        Path linkedOutput = seed.packet().root().resolve(
                QualificationExecutionIdentityWriter.LEDGER_FILE);
        createSymbolicLinkOrAbort(linkedOutput, external);

        dev.talos.core.security.NoFollowPathPolicy.Violation failure = assertThrows(
                dev.talos.core.security.NoFollowPathPolicy.Violation.class,
                () -> write(seed));

        assertEquals(
                dev.talos.core.security.NoFollowPathPolicy.Reason.LINKED_COMPONENT,
                failure.reason());
        assertFalse(failure.getMessage().contains(external.toString()));
        assertArrayEquals(externalBytes, Files.readAllBytes(external));
        assertTrue(Files.isSymbolicLink(linkedOutput));
        assertFalse(Files.exists(seed.packet().root().resolve(
                QualificationExecutionIdentity.HARNESS_FILE)));
        assertFalse(Files.exists(seed.packet().root().resolve(
                QualificationExecutionIdentity.RUNTIME_FILE)));
    }

    @Test
    void metadataContinuityFallbackIsExplicitWhenThePlatformHasNoFileKey() throws Exception {
        Path artifact = temp.resolve("fallback.gguf");
        Files.writeString(artifact, "model bytes\n", StandardCharsets.UTF_8);
        QualificationExecutionIdentityWriter.ExternalArtifactReceipt captured =
                QualificationExecutionIdentityWriter.captureExternalArtifact(
                        "MODEL_ARTIFACT", "model/fallback.gguf", "MODEL_GGUF", artifact,
                        QualificationExecutionIdentityWriter::sha256File);
        QualificationExecutionIdentityWriter.ExternalArtifactReceipt fallback =
                new QualificationExecutionIdentityWriter.ExternalArtifactReceipt(
                        captured.role(), captured.logicalToken(), captured.kind(),
                        captured.sha256(), captured.hashObservationContract(),
                        captured.sizeBytes(), captured.lastModifiedMillis(),
                        captured.creationTimeMillis(), "",
                        "METADATA_CONTINUITY_SIZE_MTIME_CTIME_V1");

        QualificationExecutionIdentityWriter.verifyExternalArtifactReceipt(
                fallback, artifact, captured.sha256(), captured.sizeBytes(), "MODEL_GGUF");
    }

    private Seed seed(String name, boolean adapted) throws Exception {
        Path root = temp.resolve(name).resolve("packet");
        QualificationTestPacket packet = QualificationTestPacket.create(
                root, QualificationTestPacket.protocol6Catalog());
        if (adapted) QualificationTestPacket.enableSingleLeadingSystemPreflight(packet);
        deleteTree(root.resolve("runs"));
        Files.delete(root.resolve(QualificationExecutionIdentity.HARNESS_FILE));
        Files.delete(root.resolve(QualificationExecutionIdentity.RUNTIME_FILE));
        Files.delete(root.resolve(QualificationExecutionIdentityWriter.LEDGER_FILE));

        Path built = temp.resolve(name).resolve("built/talos.jar");
        Path installed = temp.resolve(name).resolve("installed/lib/talos.jar");
        writeIdentityJar(built, VERSION, COMMIT, "same");
        Files.createDirectories(installed.getParent());
        Files.copy(built, installed);
        Path dependency = installed.getParent().resolve("dependency.jar");
        writeIdentityJar(dependency, VERSION, COMMIT, "dependency");
        Path bin = installed.getParent().getParent().resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("talos.bat"),
                "set CLASSPATH=%APP_HOME%\\lib\\talos.jar;%APP_HOME%\\lib\\dependency.jar\r\n",
                StandardCharsets.UTF_8);
        Files.writeString(bin.resolve("talos"),
                "CLASSPATH=$APP_HOME/lib/talos.jar:$APP_HOME/lib/dependency.jar\n",
                StandardCharsets.UTF_8);

        Path repository = temp.resolve(name).resolve("repository");
        Path repositoryInput = repository.resolve("src/input.txt");
        Path buildInput = repository.resolve("build/classes/input.class");
        Files.createDirectories(repositoryInput.getParent());
        Files.createDirectories(buildInput.getParent());
        Files.writeString(repositoryInput, "repository input\n", StandardCharsets.UTF_8);
        Files.writeString(buildInput, "compiled input\n", StandardCharsets.UTF_8);
        Map<String, String> packetFiles = new java.util.TreeMap<>();
        packetFiles.put("tools/test-harness.txt", hash(root.resolve("tools/test-harness.txt")));
        Map<String, String> repositoryFiles = new java.util.TreeMap<>();
        repositoryFiles.put("src/input.txt", hash(repositoryInput));
        for (String logical : QualificationExecutionIdentityWriter.requiredHarnessRoles().values()) {
            boolean packetLocal = logical.startsWith("tools/");
            Path required = (packetLocal ? root : repository).resolve(logical);
            Files.createDirectories(required.getParent());
            Files.writeString(required, "required harness input " + logical + "\n",
                    StandardCharsets.UTF_8);
            (packetLocal ? packetFiles : repositoryFiles).put(logical, hash(required));
        }

        Path packetInput = root.resolve("tools/test-harness.txt");
        Path candidate = root.resolve("candidates/qwen14/candidate.json");
        Path calibration = root.resolve("candidates/qwen14/calibration-rules.json");
        Path engineManifest = root.resolve("engines/cuda.json");
        Path machine = root.resolve("machines/reference.json");
        Path model = root.resolve("artifacts/model.gguf");
        Path engine = root.resolve("artifacts/llama-server.exe");
        Path inputs = temp.resolve(name).resolve("harness-inputs.json");
        QualificationJson.write(inputs,
                new QualificationExecutionIdentityWriter.HarnessInputSpecification(
                        QualificationExecutionIdentityWriter.INPUT_SCHEMA,
                        QualificationExecutionIdentityWriter.INPUT_SCHEMA_VERSION,
                        QualificationProtocol.VERSION,
                        COMMIT,
                        QualificationExecutionIdentity.currentFinalizerImplementationSha256(),
                        QualificationExecutionIdentity.executingFinalizerClasspathSnapshot().sha256(),
                        hash(built),
                        hash(installed),
                        hash(candidate),
                        hash(calibration),
                        hash(engineManifest),
                        hash(machine),
                        hash(model),
                        hash(engine),
                        List.of(
                                new QualificationExecutionIdentityWriter.ClasspathInput(
                                        "lib/talos.jar", hash(installed)),
                                new QualificationExecutionIdentityWriter.ClasspathInput(
                                        "lib/dependency.jar", hash(dependency))),
                        QualificationExecutionIdentityWriter.requiredHarnessRoles(),
                        packetFiles,
                        repositoryFiles,
                        Map.of("build/classes/input.class", hash(buildInput))));
        return new Seed(
                packet, repository, built, installed,
                model, engine, inputs);
    }

    private static QualificationExecutionIdentityWriter.Result write(Seed seed) throws Exception {
        return QualificationExecutionIdentityWriter.write(request(seed, COMMIT));
    }

    private static QualificationExecutionIdentityWriter.VerificationResult verify(Seed seed)
            throws Exception {
        return QualificationExecutionIdentityWriter.verifyExisting(
                new QualificationExecutionIdentityWriter.VerificationRequest(
                        seed.packet().root().toAbsolutePath(),
                        seed.repositoryRoot().toAbsolutePath(),
                        seed.builtTalos().toAbsolutePath(),
                        seed.installedTalos().toAbsolutePath(),
                        seed.model().toAbsolutePath(),
                        seed.engine().toAbsolutePath(),
                        COMMIT,
                        VERSION));
    }

    private static QualificationExecutionIdentityWriter.Request request(
            Seed seed, String commit) {
        return new QualificationExecutionIdentityWriter.Request(
                seed.packet().root().toAbsolutePath(),
                seed.repositoryRoot().toAbsolutePath(),
                seed.builtTalos().toAbsolutePath(),
                seed.installedTalos().toAbsolutePath(),
                seed.model().toAbsolutePath(),
                seed.engine().toAbsolutePath(),
                seed.inputs().toAbsolutePath(),
                commit,
                VERSION);
    }

    private static void writeIdentityJar(
            Path path, String version, String commit, String payload) throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            writeEntry(output, "META-INF/MANIFEST.MF", """
                    Manifest-Version: 1.0
                    Implementation-Title: Talos
                    Implementation-Version: %s

                    """.formatted(version));
            writeEntry(output, "META-INF/talos-build.properties", """
                    git.commit=%s
                    git.branch=improvement/new-models
                    git.dirty=false
                    """.formatted(commit));
            writeEntry(output, "dev/talos/app/Main.class", payload);
        }
    }

    private static void writeEntry(JarOutputStream output, String name, String value)
            throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static String hash(Path path) throws IOException {
        return QualificationExecutionIdentityWriter.sha256File(path);
    }

    private static void replaceIntegralWithWrongScalarType(
            ObjectNode owner, String field, boolean textual) {
        long canonical = QualificationJson.requiredLong(owner.get(field), field);
        if (textual) {
            owner.put(field, Long.toString(canonical));
        } else {
            owner.put(field, canonical + 0.9d);
        }
    }

    private static void rebindExecutionLedgerIdentity(
            QualificationExecutionIdentityWriter.Result written,
            ObjectNode ledger) throws IOException {
        QualificationJson.write(written.executionInputLedger(), ledger);

        ObjectNode harness = (ObjectNode) QualificationJson.readTree(written.harnessManifest());
        ((ObjectNode) harness.path("files")).put(
                QualificationExecutionIdentityWriter.LEDGER_FILE,
                hash(written.executionInputLedger()));
        QualificationJson.write(written.harnessManifest(), harness);

        ObjectNode runtime = (ObjectNode) QualificationJson.readTree(written.runtimeIdentity());
        runtime.put("harnessManifestSha256", hash(written.harnessManifest()));
        QualificationJson.write(written.runtimeIdentity(), runtime);
    }

    private static void assertOutputsAbsent(Path root) {
        assertFalse(Files.exists(root.resolve(QualificationExecutionIdentity.HARNESS_FILE)));
        assertFalse(Files.exists(root.resolve(QualificationExecutionIdentity.RUNTIME_FILE)));
        assertFalse(Files.exists(root.resolve(QualificationExecutionIdentityWriter.LEDGER_FILE)));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            Assumptions.abort("symbolic-link fixture is unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    private record Seed(
            QualificationTestPacket packet,
            Path repositoryRoot,
            Path builtTalos,
            Path installedTalos,
            Path model,
            Path engine,
            Path inputs) {}
}
