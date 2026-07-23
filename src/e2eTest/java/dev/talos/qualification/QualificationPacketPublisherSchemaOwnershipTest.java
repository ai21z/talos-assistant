package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationPacketPublisherSchemaOwnershipTest {
    private static final Pattern DIRECT_FILE_WRITE = Pattern.compile(
            "Files\\s*\\.\\s*(write|writeString|newOutputStream|move|copy|createFile)\\s*\\(");

    @TempDir
    Path temp;

    @Test
    void commitSchemaRejectsNullCoercedFractionalUnknownAndDuplicateFields()
            throws Exception {
        List<JsonCorruption> corruptions = List.of(
                new JsonCorruption("null", bytes -> mutate(bytes,
                        root -> root.putNull("transactionId"))),
                new JsonCorruption("textual", bytes -> mutate(bytes,
                        root -> root.put("schemaVersion", "1"))),
                new JsonCorruption("fractional", bytes -> mutate(bytes,
                        root -> root.put("protocolVersion", 6.5d))),
                new JsonCorruption("unknown", bytes -> mutate(bytes,
                        root -> root.put("unexpectedAuthority", true))),
                new JsonCorruption("duplicate", this::duplicateSchemaName));

        for (JsonCorruption corruption : corruptions) {
            Fixture fixture = committedProviderFixture("commit-schema-" + corruption.name());
            Path commit = commitPath(fixture);
            Files.write(commit, corruption.apply(Files.readAllBytes(commit)));

            assertReaderAndWriterRejectWithoutMutation(
                    fixture,
                    QualificationPacketPublisher.FailureReason.INVALID_COMMIT,
                    QualificationPacketPublisher.FailureReason.INVALID_COMMIT);
        }
    }

    @Test
    void intentSchemaRejectsNullCoercedFractionalUnknownAndDuplicateFields()
            throws Exception {
        List<JsonCorruption> corruptions = List.of(
                new JsonCorruption("null", bytes -> mutate(bytes,
                        root -> root.putNull("desiredCommit"))),
                new JsonCorruption("textual", bytes -> mutate(bytes,
                        root -> root.put("schemaVersion", "1"))),
                new JsonCorruption("fractional", bytes -> mutate(bytes,
                        root -> root.put("protocolVersion", 6.5d))),
                new JsonCorruption("unknown", bytes -> mutate(bytes,
                        root -> root.put("unexpectedAuthority", true))),
                new JsonCorruption("duplicate", this::duplicateSchemaName));

        for (JsonCorruption corruption : corruptions) {
            Fixture fixture = openProviderIntent("intent-schema-" + corruption.name());
            Path intent = intentPath(fixture);
            Files.write(intent, corruption.apply(Files.readAllBytes(intent)));

            assertReaderAndWriterRejectWithoutMutation(
                    fixture,
                    QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY,
                    QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY);
        }
    }

    @Test
    void memberAndStagePathsMustBeCanonicalPacketRelativePaths() throws Exception {
        List<String> invalidPaths = List.of(
                "../escape.json",
                "/absolute.json",
                "runs\\qwen14\\prompt-debug.txt",
                "runs/qwen14/../prompt-debug.txt");
        for (int index = 0; index < invalidPaths.size(); index++) {
            String invalid = invalidPaths.get(index);
            Fixture commitFixture = committedProviderFixture("member-path-" + index);
            Path commit = commitPath(commitFixture);
            Files.write(commit, mutate(Files.readAllBytes(commit), root -> {
                ArrayNode members = (ArrayNode) root.get("members");
                ((ObjectNode) members.get(0)).put("relativePath", invalid);
            }));
            assertReaderAndWriterRejectWithoutMutation(
                    commitFixture,
                    QualificationPacketPublisher.FailureReason.INVALID_COMMIT,
                    QualificationPacketPublisher.FailureReason.INVALID_COMMIT);

            Fixture intentFixture = openProviderIntent("stage-path-" + index);
            Path intent = intentPath(intentFixture);
            Files.write(intent, mutate(Files.readAllBytes(intent), root -> {
                ArrayNode stages = (ArrayNode) root.get("stagePaths");
                stages.set(0, TextNode.valueOf(invalid));
            }));
            assertReaderAndWriterRejectWithoutMutation(
                    intentFixture,
                    QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY,
                    QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY);
        }
    }

    @Test
    void campaignPhaseIsStrictAndMustMatchThePublicationLifecycle() throws Exception {
        List<PhaseCorruption> activeCorruptions = List.of(
                new PhaseCorruption("null", root -> root.putNull("campaignPhase")),
                new PhaseCorruption("numeric", root -> root.put("campaignPhase", 1)),
                new PhaseCorruption("unknown", root -> root.put("campaignPhase", "PAUSED")),
                new PhaseCorruption("provider-ended", root -> root.put("campaignPhase", "ENDED")));
        for (PhaseCorruption corruption : activeCorruptions) {
            Fixture fixture = openProviderIntent("phase-" + corruption.name());
            Path intent = intentPath(fixture);
            Files.write(intent, mutate(Files.readAllBytes(intent), corruption.mutation()));
            assertReaderAndWriterRejectWithoutMutation(
                    fixture,
                    QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY,
                    QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY);
        }

        Fixture score = openScorecardIntent("phase-score-active");
        Path scoreIntent = intentPath(score);
        Files.write(scoreIntent, mutate(Files.readAllBytes(scoreIntent),
                root -> root.put("campaignPhase", "ACTIVE")));
        assertReaderAndWriterRejectWithoutMutation(
                score,
                QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY,
                QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY);
    }

    @Test
    void filenamesAndEmbeddedTransactionIdentitiesMustAgree() throws Exception {
        Fixture renamedCommit = committedProviderFixture("renamed-commit");
        Path commit = commitPath(renamedCommit);
        Files.move(commit, commit.resolveSibling(otherHash(
                commit.getFileName().toString().replace(".json", "")) + ".json"));
        assertReaderAndWriterRejectWithoutMutation(
                renamedCommit,
                QualificationPacketPublisher.FailureReason.INVALID_COMMIT,
                QualificationPacketPublisher.FailureReason.INVALID_COMMIT);

        Fixture embeddedCommit = committedProviderFixture("embedded-commit");
        Path embeddedCommitPath = commitPath(embeddedCommit);
        Files.write(embeddedCommitPath, mutate(Files.readAllBytes(embeddedCommitPath), root ->
                root.put("transactionId", otherHash(root.path("transactionId").textValue()))));
        assertReaderAndWriterRejectWithoutMutation(
                embeddedCommit,
                QualificationPacketPublisher.FailureReason.INVALID_COMMIT,
                QualificationPacketPublisher.FailureReason.INVALID_COMMIT);

        Fixture renamedIntent = openProviderIntent("renamed-intent");
        Path intent = intentPath(renamedIntent);
        Files.move(intent, intent.resolveSibling(otherHash(
                intent.getFileName().toString().replace(".json", "")) + ".json"));
        assertReaderAndWriterRejectWithoutMutation(
                renamedIntent,
                QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY,
                QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY);

        Fixture embeddedIntent = openProviderIntent("embedded-intent");
        Path embeddedIntentPath = intentPath(embeddedIntent);
        Files.write(embeddedIntentPath, mutate(Files.readAllBytes(embeddedIntentPath), root -> {
            ObjectNode desired = (ObjectNode) root.get("desiredCommit");
            desired.put("transactionId", otherHash(desired.path("transactionId").textValue()));
        }));
        assertReaderAndWriterRejectWithoutMutation(
                embeddedIntent,
                QualificationPacketPublisher.FailureReason.INVALID_COMMIT,
                QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY);
    }

    @Test
    void authorityWritersRouteFinalEvidenceThroughThePacketPublisher() throws Exception {
        Map<String, List<String>> requiredKinds = new LinkedHashMap<>();
        requiredKinds.put("QualificationExecutionIdentityWriter.java",
                List.of("EXECUTION_IDENTITY"));
        requiredKinds.put("QualificationPtyCaptureMain.java",
                List.of("PTY_CAPTURE", "PTY_FAILURE"));
        requiredKinds.put("QualificationApprovalFinalizer.java",
                List.of("APPROVAL_EVIDENCE"));
        requiredKinds.put("QualificationRequirementFinalizer.java",
                List.of("MECHANICAL_REQUIREMENTS"));
        requiredKinds.put("QualificationProviderEvidence.java",
                List.of("PROVIDER_EVIDENCE"));
        requiredKinds.put("QualificationProviderEvidenceFinalizer.java",
                List.of("PROVIDER_EVIDENCE"));
        requiredKinds.put("QualificationTraceEvidencePublication.java",
                List.of("TRACE_EVIDENCE"));
        requiredKinds.put("QualificationRunFinalizer.java",
                List.of("RUN_FINALIZATION"));
        requiredKinds.put("QualificationCampaignCompletion.java",
                List.of("CAMPAIGN_COMPLETION"));
        requiredKinds.put("ModelQualificationMain.java", List.of("SCORECARD"));

        Path sourceRoot = repositoryRoot()
                .resolve("src/e2eTest/java/dev/talos/qualification");
        for (Map.Entry<String, List<String>> contract : requiredKinds.entrySet()) {
            String fileName = contract.getKey();
            String source = Files.readString(sourceRoot.resolve(fileName), StandardCharsets.UTF_8);
            assertTrue(source.contains("lease.publish("),
                    () -> fileName + " must publish through an active packet lease");
            for (String kind : contract.getValue()) {
                assertTrue(source.contains(
                                "QualificationPacketPublisher.PublicationKind." + kind),
                        () -> fileName + " must own " + kind + " publication");
            }
            assertFalse(source.contains(
                            "QualificationArtifactPublication.publishNewOrVerifyExact("),
                    () -> fileName + " must not call the legacy leaf publisher");
            assertFalse(source.contains("QualificationJson.write("),
                    () -> fileName + " must not write authority JSON directly");
            assertFalse(source.contains("new AtomicFileWriter().write"),
                    () -> fileName + " must not own a parallel atomic writer");
            assertNoDirectFinalPathWrite(fileName, source);
        }
    }

    private Fixture committedProviderFixture(String name) throws IOException {
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve(name), QualificationTestPacket.protocol6Catalog());
        Path prompt = packet.runRoot().resolve("prompt-debug.txt");
        Path model = packet.runRoot().resolve("llama.log");
        return new Fixture(
                packet,
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(
                        new QualificationPacketPublisher.Member(prompt, Files.readAllBytes(prompt)),
                        new QualificationPacketPublisher.Member(model, Files.readAllBytes(model))));
    }

    private Fixture openProviderIntent(String name) throws Exception {
        Fixture fixture = committedProviderFixture(name);
        fixture.packet().removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                List.of(
                        fixture.packet().runRoot().resolve(
                                QualificationCausalFinalizer.OUTPUT_FILE),
                        fixture.packet().runRoot().resolve(
                                QualificationRunFinalizer.CANARY_FILE),
                        fixture.packet().runRoot().resolve(
                                QualificationRunFinalizer.RESULT_FILE)));
        fixture.packet().removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                List.of(fixture.packet().runRoot().resolve(
                        QualificationRequirementFinalizer.OUTPUT_FILE)));
        fixture.packet().removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                List.of(fixture.packet().runRoot().resolve(
                        QualificationApprovalFinalizer.OUTPUT_FILE)));
        fixture.packet().removePublicationForTest(fixture.kind(), fixture.paths());
        createOpenIntent(fixture);
        return fixture;
    }

    private Fixture openScorecardIntent(String name) throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve(name), catalog,
                catalog.admission().scenarios().getFirst());
        endCampaign(packet);
        Path target = packet.root().resolve("scorecards/qwen14-standard-admission.json");
        Fixture fixture = new Fixture(
                packet,
                QualificationPacketPublisher.PublicationKind.SCORECARD,
                List.of(new QualificationPacketPublisher.Member(
                        target, "{}\n".getBytes(StandardCharsets.UTF_8))));
        createOpenIntent(fixture);
        return fixture;
    }

    private static void createOpenIntent(Fixture fixture) {
        assertThrows(IOException.class, () -> QualificationPacketPublisher.withLease(
                fixture.packet().root(),
                Duration.ofSeconds(5),
                (stage, ordinal) -> {
                    if (stage == QualificationPacketPublisher.FaultStage.AFTER_MEMBER_STAGE_CREATE
                            && ordinal == 0) {
                        throw new IOException("injected after intent publication");
                    }
                },
                lease -> lease.publish(fixture.kind(), fixture.members())));
        assertTrue(Files.isRegularFile(intentPath(fixture)));
    }

    private static void assertReaderAndWriterRejectWithoutMutation(
            Fixture fixture,
            QualificationPacketPublisher.FailureReason readerReason,
            QualificationPacketPublisher.FailureReason writerReason) throws IOException {
        Map<String, String> before = treeState(fixture.packet().root());

        QualificationPacketPublisher.PublicationException reader = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationPacketPublisher.requireSettledControlState(
                        fixture.packet().root()));
        assertEquals(readerReason, reader.reason());
        assertEquals(before, treeState(fixture.packet().root()),
                "rejected reader changed packet bytes or mtimes");

        QualificationPacketPublisher.PublicationException writer = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationPacketPublisher.withLease(
                        fixture.packet().root(),
                        lease -> lease.publish(fixture.kind(), fixture.members())));
        assertEquals(writerReason, writer.reason());
        assertEquals(before, treeState(fixture.packet().root()),
                "rejected writer changed packet bytes or mtimes");
    }

    private static Path commitPath(Fixture fixture) throws IOException {
        return QualificationPacketPublisher.commitPathForTest(
                fixture.packet().root(), fixture.kind(), fixture.paths());
    }

    private static Path intentPath(Fixture fixture) {
        try {
            String transactionId = QualificationPacketPublisher.transactionIdFor(
                    fixture.packet().root(), fixture.kind(), fixture.paths());
            return fixture.packet().root()
                    .resolve(QualificationPacketPublisher.CONTROL_DIRECTORY)
                    .resolve("intents")
                    .resolve(transactionId + ".json");
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static byte[] mutate(byte[] canonical, Consumer<ObjectNode> mutation)
            throws IOException {
        ObjectNode root = (ObjectNode) QualificationJson.readTree(canonical);
        mutation.accept(root);
        return QualificationJson.bytes(root);
    }

    private byte[] duplicateSchemaName(byte[] canonical) {
        String json = new String(canonical, StandardCharsets.UTF_8);
        int firstLineEnd = json.indexOf('\n');
        if (firstLineEnd < 0) throw new IllegalArgumentException("canonical JSON has no object body");
        String duplicate = "  \"schemaName\" : \"duplicate\",\n";
        return (json.substring(0, firstLineEnd + 1)
                + duplicate
                + json.substring(firstLineEnd + 1)).getBytes(StandardCharsets.UTF_8);
    }

    private static void endCampaign(QualificationTestPacket packet) throws IOException {
        QualificationCampaignCompletion.complete(
                packet.root(),
                QualificationProtocol.BankKind.ADMISSION,
                QualificationTestPacket.protocol6Catalog());
    }

    private static Map<String, String> treeState(Path root) throws IOException {
        Map<String, String> state = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    state.put(relative, "directory");
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    String bytes = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
                    state.put(relative, "file:" + Files.getLastModifiedTime(
                            path, LinkOption.NOFOLLOW_LINKS) + ":" + bytes);
                } else {
                    state.put(relative, "other");
                }
            }
        }
        return Map.copyOf(state);
    }

    private static String otherHash(String hash) {
        char first = hash.charAt(0) == '0' ? '1' : '0';
        return first + hash.substring(1);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("src/e2eTest/java"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("qualification source root is unavailable");
    }

    private static void assertNoDirectFinalPathWrite(String fileName, String source) {
        Matcher matcher = DIRECT_FILE_WRITE.matcher(source);
        List<String> operations = new ArrayList<>();
        while (matcher.find()) operations.add(matcher.group(1));
        if (fileName.equals("QualificationPtyCaptureMain.java")) {
            assertEquals(List.of("newOutputStream"), operations,
                    "PTY capture may write only its non-evidence process stop marker directly");
            assertTrue(Pattern.compile(
                            "Files\\.newOutputStream\\(\\s*stopFile\\s*,",
                            Pattern.MULTILINE).matcher(source).find(),
                    "the one PTY direct write must remain scoped to stopFile");
        } else {
            assertTrue(operations.isEmpty(),
                    () -> fileName + " contains direct final-path file writes: " + operations);
        }
    }

    @FunctionalInterface
    private interface ByteCorruption {
        byte[] apply(byte[] canonical) throws IOException;
    }

    private record JsonCorruption(String name, ByteCorruption corruption) {
        byte[] apply(byte[] canonical) throws IOException {
            return corruption.apply(canonical);
        }
    }

    private record PhaseCorruption(String name, Consumer<ObjectNode> mutation) {}

    private record Fixture(
            QualificationTestPacket packet,
            QualificationPacketPublisher.PublicationKind kind,
            List<QualificationPacketPublisher.Member> members) {
        Fixture {
            members = List.copyOf(members);
        }

        List<Path> paths() {
            return members.stream().map(QualificationPacketPublisher.Member::path).toList();
        }
    }
}
