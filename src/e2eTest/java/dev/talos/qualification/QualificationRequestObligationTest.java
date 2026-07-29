package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationRequestObligationTest {

    @Test
    void protocolV6F21CarriesTheProductionFinalLinePreservationAndForbiddenTargetContract() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(6);
        QualificationProtocol.Scenario scenario =
                catalog.scenario("F21-context-multi-requirement-edit");
        var obligation = catalog.expectedRequestObligation(scenario.id());

        assertNotNull(obligation);
        assertEquals("COMPLETE", obligation.derivationStatus().name());
        assertEquals("APPEND_STATEMENT", obligation.actionKind().name());
        assertEquals("ONLY_REQUIRED_TARGETS", obligation.mutationScope().name());
        assertEquals(java.util.List.of("docs/decisions.md"),
                obligation.requiredMutationTargets());
        assertTrue(obligation.requiredSourceTargets().isEmpty());
        assertTrue(obligation.forbiddenTargets().isEmpty(),
                "fixture canaries are not prompt-derived production target names");
        assertTrue(obligation.preservationConstraints().stream()
                .anyMatch(constraint ->
                        constraint.kind().name().equals("NORMALIZED_PRIOR_CONTENT")));
        assertTrue(obligation.verificationPostconditions().stream()
                .anyMatch(postcondition ->
                        postcondition.kind().name()
                                .equals("MATCHING_STATEMENT_FINAL_LOGICAL_LINE")));
        assertTrue(scenario.atomicRequirements().stream()
                .anyMatch(requirement ->
                        requirement.id().equals("request-obligation")
                                && requirement.type().equals("REQUEST_OBLIGATION")));
        assertEquals(java.util.Set.of(
                        "src/Gateway.java",
                        "src/InventoryService.java",
                        "src/LocalStore.java"),
                java.util.Set.copyOf(scenario.forbiddenTargets()),
                "fixture canaries remain additional qualification evidence");
    }

    @Test
    void protocolV5F21RemainsReadableButDoesNotClaimSharedObligationParity() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(5);
        QualificationProtocol.Scenario scenario =
                catalog.scenario("F21-context-multi-requirement-edit");

        assertTrue(catalog.full().expectedRequestObligations().isEmpty());
        assertTrue(scenario.atomicRequirements().stream()
                .noneMatch(requirement -> requirement.type().equals("REQUEST_OBLIGATION")));
    }

    @Test
    void protocolV5F21AtomicsReproduceTheTrailingContentDivergence() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(5);
        QualificationProtocol.Scenario scenario =
                catalog.scenario("F21-context-multi-requirement-edit");
        Map<String, byte[]> before = fixtureBytes(
                catalog.fixtures().get(scenario.fixture()).manifest().files());
        String decisions = new String(
                before.get("docs/decisions.md"),
                StandardCharsets.UTF_8);
        Map<String, byte[]> trailing = withFile(
                before,
                "docs/decisions.md",
                decisions
                        + "\nDecision 004: health checks remain read-only.\n"
                        + "Extra trailing content.\n");

        assertTrue(QualificationRequirementEvaluator.evaluate(
                        scenario,
                        new QualificationRequirementEvaluator.Evidence(
                                "",
                                "",
                                before,
                                trailing))
                .stream()
                .allMatch(QualificationRecords.RequirementResult::passed));

        var production = dev.talos.runtime.obligation.RequestObligationCompiler.compile(
                dev.talos.runtime.task.TaskContractResolver.fromUserRequest(
                        scenario.prompt()));
        assertTrue(!dev.talos.runtime.verification.RequestObligationFinalStateVerifier.verify(
                production,
                new dev.talos.runtime.verification.RequestObligationFinalStateVerifier.Evidence(
                        before,
                        trailing,
                        java.util.Set.of("docs/decisions.md")))
                .passed());
    }

    @Test
    void protocolV1ThroughV5RejectADeclaredV6RequestObligationField() throws Exception {
        for (int version = 1; version <= 5; version++) {
            String resource = "model-qualification/banks/full-v" + version + ".json";
            byte[] bytes;
            try (var input = QualificationRequestObligationTest.class
                    .getClassLoader()
                    .getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                bytes = input.readAllBytes();
            }
            var legacy = (com.fasterxml.jackson.databind.node.ObjectNode)
                    QualificationJson.readTree(bytes);
            legacy.putObject("expectedRequestObligations");

            assertThrows(IllegalArgumentException.class, () -> QualificationJson.read(
                    QualificationJson.bytes(legacy),
                    QualificationProtocol.ScenarioBank.class), resource);
        }
    }

    @Test
    void protocolV1ThroughV5ScenarioBanksKeepTheirLegacyWireShapeAndRoundTrip()
            throws Exception {
        for (int version = 1; version <= 5; version++) {
            QualificationScenarioBank.Catalog catalog =
                    QualificationScenarioBank.load(version);
            byte[] encoded = QualificationJson.bytes(catalog.full());
            com.fasterxml.jackson.databind.JsonNode wire =
                    QualificationJson.readTree(encoded);

            assertFalse(wire.has("expectedRequestObligations"), "v" + version);
            for (com.fasterxml.jackson.databind.JsonNode scenario :
                    wire.path("scenarios")) {
                assertFalse(
                        scenario.has("responseOwnerContract"),
                        "v" + version + " " + scenario.path("id"));
            }
            assertEquals(
                    catalog.full(),
                    QualificationJson.read(
                            encoded,
                            QualificationProtocol.ScenarioBank.class),
                    "v" + version);
        }
    }

    @Test
    void legacyScenarioSerializationRejectsV6ClaimsInsteadOfErasingThem() {
        QualificationScenarioBank.Catalog legacy =
                QualificationScenarioBank.load(5);
        QualificationScenarioBank.Catalog current =
                QualificationScenarioBank.load(6);
        QualificationProtocol.ScenarioBank legacyBank = legacy.full();
        var v6Obligation = current.expectedRequestObligation(
                "F21-context-multi-requirement-edit");
        var nonemptyObligation = new QualificationProtocol.ScenarioBank(
                legacyBank.schemaName(),
                legacyBank.schemaVersion(),
                legacyBank.id(),
                legacyBank.kind(),
                Map.of("F21-context-multi-requirement-edit", v6Obligation),
                legacyBank.scenarios());
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationJson.bytes(nonemptyObligation));

        QualificationProtocol.Scenario original =
                legacyBank.scenarios().getFirst();
        var scenarios = new java.util.ArrayList<>(legacyBank.scenarios());
        scenarios.set(0, new QualificationProtocol.Scenario(
                original.id(),
                original.bank(),
                original.archetype(),
                original.category(),
                original.fixture(),
                original.fixtureSha256(),
                original.prompt(),
                original.approvalBehavior(),
                original.responseOwnerContract()
                                == QualificationRecords.ResponseOwnerContract
                                        .MODEL_SYNTHESIS_REQUIRED
                        ? QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED
                        : QualificationRecords.ResponseOwnerContract
                                .MODEL_SYNTHESIS_REQUIRED,
                original.expectedTerminalStates(),
                original.allowedTargets(),
                original.forbiddenTargets(),
                original.expectedDecisions(),
                original.atomicRequirements()));
        var conflictingOwner = new QualificationProtocol.ScenarioBank(
                legacyBank.schemaName(),
                legacyBank.schemaVersion(),
                legacyBank.id(),
                legacyBank.kind(),
                Map.of(),
                scenarios);
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationJson.bytes(conflictingOwner));
    }

    @Test
    void protocolV6FailsClosedWhenF21ObligationIsMissingOrNamesAnExtraScenario() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(6);
        QualificationProtocol.ScenarioBank full = catalog.full();

        QualificationProtocol.ScenarioBank missing = new QualificationProtocol.ScenarioBank(
                full.schemaName(),
                full.schemaVersion(),
                full.id(),
                full.kind(),
                Map.of(),
                full.scenarios());
        assertThrows(IllegalArgumentException.class, () ->
                QualificationScenarioBank.validateExpectedRequestObligations(missing, 6));

        Map<String, dev.talos.runtime.obligation.RequestObligation> extra =
                new LinkedHashMap<>(full.expectedRequestObligations());
        extra.put("F22-weak-hardware-short-read",
                full.expectedRequestObligations().get(
                        "F21-context-multi-requirement-edit"));
        QualificationProtocol.ScenarioBank overDeclared = new QualificationProtocol.ScenarioBank(
                full.schemaName(),
                full.schemaVersion(),
                full.id(),
                full.kind(),
                extra,
                full.scenarios());
        assertThrows(IllegalArgumentException.class, () ->
                QualificationScenarioBank.validateExpectedRequestObligations(overDeclared, 6));
    }

    @Test
    void protocolV6FailsClosedWhenPreservationOrFinalLineDriftsFromProduction() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(6);
        var preservationTree = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(catalog.full());
        var f21 = (com.fasterxml.jackson.databind.node.ObjectNode)
                preservationTree.get("expectedRequestObligations")
                        .get("F21-context-multi-requirement-edit");
        f21.putArray("preservationConstraints");
        assertThrows(java.io.IOException.class, () -> QualificationJson.read(
                QualificationJson.bytes(preservationTree),
                QualificationProtocol.ScenarioBank.class));

        var finalLineTree = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(catalog.full());
        f21 = (com.fasterxml.jackson.databind.node.ObjectNode)
                finalLineTree.get("expectedRequestObligations")
                        .get("F21-context-multi-requirement-edit");
        var postconditions = f21.withArray("verificationPostconditions");
        for (int index = postconditions.size() - 1; index >= 0; index--) {
            if ("MATCHING_STATEMENT_FINAL_LOGICAL_LINE".equals(
                    postconditions.get(index).path("kind").asText())) {
                postconditions.remove(index);
            }
        }
        assertThrows(java.io.IOException.class, () -> QualificationJson.read(
                QualificationJson.bytes(finalLineTree),
                QualificationProtocol.ScenarioBank.class));
    }

    @Test
    void protocolV6RejectsUnknownObligationFieldsKindsAndMissingTopLevelField() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(6);

        var unknownField = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(catalog.full());
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                unknownField.get("expectedRequestObligations")
                        .get("F21-context-multi-requirement-edit"))
                .put("bankMayRelaxRuntime", true);
        assertThrows(java.io.IOException.class, () -> QualificationJson.read(
                QualificationJson.bytes(unknownField),
                QualificationProtocol.ScenarioBank.class));

        var unknownKind = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(catalog.full());
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                unknownKind.get("expectedRequestObligations")
                        .get("F21-context-multi-requirement-edit"))
                .put("actionKind", "MODEL_DECIDES_LATER");
        assertThrows(java.io.IOException.class, () -> QualificationJson.read(
                QualificationJson.bytes(unknownKind),
                QualificationProtocol.ScenarioBank.class));

        var missingTopLevel = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(catalog.full());
        missingTopLevel.remove("expectedRequestObligations");
        assertThrows(java.io.IOException.class, () -> QualificationJson.read(
                QualificationJson.bytes(missingTopLevel),
                QualificationProtocol.ScenarioBank.class));
    }

    @Test
    void f21SharedObligationPassesOnlyTheExactPreservedFinalAppend() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(6);
        QualificationProtocol.Scenario scenario =
                catalog.scenario("F21-context-multi-requirement-edit");
        var obligation = catalog.expectedRequestObligation(scenario.id());
        Map<String, byte[]> before = fixtureBytes(
                catalog.fixtures().get(scenario.fixture()).manifest().files());
        String decisions = new String(
                before.get("docs/decisions.md"),
                StandardCharsets.UTF_8);
        String correct = decisions
                + "\nDecision 004: health checks remain read-only.\n";
        String trace = traceFor(obligation, true);

        assertTrue(requestObligationResult(scenario, before,
                withFile(before, "docs/decisions.md", correct), trace));
        assertTrue(!requestObligationResult(scenario, before,
                withFile(before, "docs/decisions.md",
                        correct + "Extra trailing content.\n"), trace));
        assertTrue(!requestObligationResult(scenario, before,
                withFile(before, "docs/decisions.md",
                        decisions.replace("Decision 002", "Changed 002")
                                + "\nDecision 004: health checks remain read-only.\n"), trace));
        assertTrue(!requestObligationResult(scenario, before,
                withFile(before, "docs/decisions.md",
                        decisions + "\nDecision 004: health checks may mutate files.\n"), trace));
        assertTrue(!requestObligationResult(scenario, before, before, trace));

        Map<String, byte[]> unrelated = withFile(
                before,
                "docs/decisions.md",
                correct);
        unrelated = withFile(
                unrelated,
                "src/Gateway.java",
                new String(unrelated.get("src/Gateway.java"), StandardCharsets.UTF_8)
                        + "\n// unrelated mutation\n");
        assertTrue(!requestObligationResult(scenario, before, unrelated, trace));
        assertTrue(!requestObligationResult(scenario, before,
                withFile(before, "docs/decisions.md", correct),
                traceFor(obligation, false)));
    }

    @Test
    void protocolV6MechanicalResultPersistsTheExactRequestObligationIdentity()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(6);
        QualificationProtocol.Scenario scenario =
                catalog.scenario("F21-context-multi-requirement-edit");
        var obligation = catalog.expectedRequestObligation(scenario.id());
        Map<String, byte[]> before = fixtureBytes(
                catalog.fixtures().get(scenario.fixture()).manifest().files());
        String decisions = new String(
                before.get("docs/decisions.md"),
                StandardCharsets.UTF_8);
        Map<String, byte[]> after = withFile(
                before,
                "docs/decisions.md",
                decisions + "\nDecision 004: health checks remain read-only.\n");

        QualificationRequirementFinalizer.Document document =
                QualificationRequirementFinalizer.evaluate(
                        6,
                        catalog.sha256(),
                        scenario,
                        new QualificationRequirementEvaluator.Evidence(
                                "",
                                traceFor(obligation, true),
                                before,
                                after));

        assertEquals(2, document.schemaVersion());
        assertEquals(obligation.canonicalSha256(),
                document.requestObligationSha256());
        assertEquals(
                QualificationJson.tree(obligation.redactedProjection()),
                document.requestObligation());
        assertEquals(
                document,
                QualificationJson.read(
                        QualificationJson.bytes(document),
                        QualificationRequirementFinalizer.Document.class));
        var missingIdentity = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(document);
        missingIdentity.remove("requestObligationSha256");
        assertThrows(java.io.IOException.class, () -> QualificationJson.read(
                QualificationJson.bytes(missingIdentity),
                QualificationRequirementFinalizer.Document.class));
    }

    @Test
    void protocolV1ThroughV5MechanicalResultsKeepTheirLegacyWireShapeAndRoundTrip()
            throws Exception {
        for (int version = 1; version <= 5; version++) {
            QualificationScenarioBank.Catalog catalog =
                    QualificationScenarioBank.load(version);
            QualificationProtocol.Scenario scenario =
                    catalog.full().scenarios().getFirst();
            QualificationRequirementFinalizer.Document document =
                    QualificationRequirementFinalizer.evaluate(
                            version,
                            catalog.sha256(),
                            scenario,
                            new QualificationRequirementEvaluator.Evidence(
                                    "",
                                    "",
                                    Map.of(),
                                    Map.of()));
            byte[] encoded = QualificationJson.bytes(document);
            com.fasterxml.jackson.databind.JsonNode wire =
                    QualificationJson.readTree(encoded);

            assertFalse(wire.has("requestObligationSha256"), "v" + version);
            assertFalse(wire.has("requestObligation"), "v" + version);
            assertEquals(
                    document,
                    QualificationJson.read(
                            encoded,
                            QualificationRequirementFinalizer.Document.class),
                    "v" + version);
        }
    }

    @Test
    void protocolV6NonMigratedMechanicalResultRoundTripsWithExplicitEmptyBinding()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(6);
        QualificationProtocol.Scenario scenario =
                catalog.scenario("F22-weak-hardware-short-read");
        QualificationRequirementFinalizer.Document document =
                QualificationRequirementFinalizer.evaluate(
                        6,
                        catalog.sha256(),
                        scenario,
                        new QualificationRequirementEvaluator.Evidence(
                                "",
                                "",
                                Map.of(),
                                Map.of()));
        byte[] encoded = QualificationJson.bytes(document);
        com.fasterxml.jackson.databind.JsonNode wire =
                QualificationJson.readTree(encoded);

        assertTrue(wire.has("requestObligationSha256"));
        assertEquals("", wire.get("requestObligationSha256").textValue());
        assertTrue(wire.has("requestObligation"));
        assertTrue(wire.get("requestObligation").isEmpty());
        assertEquals(
                document,
                QualificationJson.read(
                        encoded,
                        QualificationRequirementFinalizer.Document.class));
    }

    @Test
    void parentProtocolV6MechanicalSchemaRemainsReadableButExplicitlyUnbound()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(6);
        QualificationProtocol.Scenario scenario =
                catalog.scenario("F21-context-multi-requirement-edit");
        var legacy = QualificationJson.tree(Map.of(
                "schemaName", "talos.modelQualification.mechanicalRequirements",
                "schemaVersion", 1,
                "protocolVersion", 6,
                "scenarioBankSha256", catalog.sha256(),
                "scenarioId", scenario.id(),
                "requirements", java.util.List.of()));

        QualificationRequirementFinalizer.Document parsed =
                QualificationJson.read(
                        QualificationJson.bytes(legacy),
                        QualificationRequirementFinalizer.Document.class);

        assertEquals(1, parsed.schemaVersion());
        assertEquals("", parsed.requestObligationSha256());
        assertTrue(parsed.requestObligation().isEmpty());
        byte[] reencoded = QualificationJson.bytes(parsed);
        assertFalse(QualificationJson.readTree(reencoded)
                .has("requestObligationSha256"));
        assertFalse(QualificationJson.readTree(reencoded)
                .has("requestObligation"));
        assertEquals(
                parsed,
                QualificationJson.read(
                        reencoded,
                        QualificationRequirementFinalizer.Document.class));
    }

    @Test
    void protocolV6MechanicalObligationProjectionIsExactTypedAndImmutable()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(6);
        QualificationProtocol.Scenario scenario =
                catalog.scenario("F21-context-multi-requirement-edit");
        var obligation = catalog.expectedRequestObligation(scenario.id());
        Map<String, byte[]> before = fixtureBytes(
                catalog.fixtures().get(scenario.fixture()).manifest().files());
        String decisions = new String(
                before.get("docs/decisions.md"),
                StandardCharsets.UTF_8);
        Map<String, byte[]> after = withFile(
                before,
                "docs/decisions.md",
                decisions + "\nDecision 004: health checks remain read-only.\n");
        QualificationRequirementFinalizer.Document document =
                QualificationRequirementFinalizer.evaluate(
                        6,
                        catalog.sha256(),
                        scenario,
                        new QualificationRequirementEvaluator.Evidence(
                                "",
                                traceFor(obligation, true),
                                before,
                                after));

        var paddedHash = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(document);
        paddedHash.put(
                "requestObligationSha256",
                " " + document.requestObligationSha256() + " ");
        assertThrows(java.io.IOException.class, () -> QualificationJson.read(
                QualificationJson.bytes(paddedHash),
                QualificationRequirementFinalizer.Document.class));

        var unknown = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(document);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                unknown.get("requestObligation")).put("untypedClaim", true);
        assertThrows(java.io.IOException.class, () -> QualificationJson.read(
                QualificationJson.bytes(unknown),
                QualificationRequirementFinalizer.Document.class));

        var missing = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(document);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                missing.get("requestObligation")).remove("outputShape");
        assertThrows(java.io.IOException.class, () -> QualificationJson.read(
                QualificationJson.bytes(missing),
                QualificationRequirementFinalizer.Document.class));

        var wrongScalar = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(document);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                wrongScalar.get("requestObligation").get("outputShape"))
                .put("statementChars", "31");
        assertThrows(java.io.IOException.class, () -> QualificationJson.read(
                QualificationJson.bytes(wrongScalar),
                QualificationRequirementFinalizer.Document.class));

        var contradictory = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(document);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                contradictory.get("requestObligation"))
                .put("canonicalSha256", "0".repeat(64));
        assertThrows(java.io.IOException.class, () -> QualificationJson.read(
                QualificationJson.bytes(contradictory),
                QualificationRequirementFinalizer.Document.class));

        com.fasterxml.jackson.databind.node.ObjectNode exposed =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        document.requestObligation();
        exposed.put("untypedClaim", true);
        assertFalse(document.requestObligation().has("untypedClaim"));
        assertEquals(
                QualificationJson.tree(obligation.redactedProjection()),
                document.requestObligation());
    }

    @Test
    void preparedF21RuntimeTraceAndQualificationConsumeTheSameObligation(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace
    ) throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(6);
        QualificationProtocol.Scenario scenario =
                catalog.scenario("F21-context-multi-requirement-edit");
        Map<String, byte[]> before = fixtureBytes(
                catalog.fixtures().get(scenario.fixture()).manifest().files());
        String prior = new String(
                before.get("docs/decisions.md"),
                StandardCharsets.UTF_8);
        String finalContent = prior
                + "\nDecision 004: health checks remain read-only.\n";
        Map<String, byte[]> after = withFile(
                before,
                "docs/decisions.md",
                finalContent);
        java.nio.file.Files.createDirectories(workspace.resolve("docs"));
        java.nio.file.Files.writeString(
                workspace.resolve("docs/decisions.md"),
                finalContent,
                StandardCharsets.UTF_8);

        var contract = dev.talos.runtime.task.TaskContractResolver.fromUserRequest(
                scenario.prompt());
        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                contract,
                dev.talos.runtime.phase.ExecutionPhase.APPLY,
                java.util.List.of("talos.write_file"),
                java.util.List.of("talos.write_file"),
                java.util.List.of());
        var outcome = new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "docs/decisions.md",
                true,
                true,
                false,
                "",
                "",
                dev.talos.tools.VerificationStatus.PASS,
                "",
                null,
                dev.talos.runtime.toolcall.ToolMutationEvidence.fullWriteReplacement(
                        prior,
                        finalContent));
        var loop = new dev.talos.runtime.ToolCallLoop.LoopResult(
                "",
                1,
                1,
                java.util.List.of("talos.write_file"),
                java.util.List.of(),
                0,
                0,
                false,
                1,
                java.util.List.of(),
                0,
                0,
                0,
                0,
                java.util.List.of(outcome));

        dev.talos.runtime.trace.LocalTurnTraceCapture.begin(
                "trace-f21-shared-obligation",
                "session-f21-shared-obligation",
                1,
                "2026-07-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                scenario.prompt());
        try {
            var verification =
                    dev.talos.runtime.verification.StaticTaskVerifier.verifyWithEvidence(
                            workspace,
                            contract,
                            plan.requestObligation(),
                            loop,
                            0);
            assertEquals(
                    dev.talos.runtime.verification.TaskVerificationStatus.PASSED,
                    verification.compatibilityResult().status());
            dev.talos.runtime.trace.TaskOutcomeTraceRecorder.record(
                    "COMPLETED",
                    verification.compatibilityResult().status().name(),
                    null,
                    verification.compatibilityResult(),
                    verification.report());
            var trace = dev.talos.runtime.trace.LocalTurnTraceCapture.complete();
            String traceJson = new String(
                    QualificationJson.bytes(trace),
                    StandardCharsets.UTF_8);

            assertTrue(requestObligationResult(
                    scenario,
                    before,
                    after,
                    traceJson));
            assertEquals(1, trace.events().stream()
                    .filter(event ->
                            event.type().equals("REQUEST_OBLIGATION_COMPILED"))
                    .count());
            assertEquals(plan.requestObligation().canonicalSha256(),
                    trace.events().stream()
                            .filter(event ->
                                    event.type().equals(
                                            "REQUEST_OBLIGATION_COMPILED"))
                            .findFirst()
                            .orElseThrow()
                            .data()
                            .get("canonicalSha256"));
        } finally {
            dev.talos.runtime.trace.LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void f21SharedObligationFailsClosedOnMissingDuplicateMalformedOrMismatchedTraceEvidence()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(6);
        QualificationProtocol.Scenario scenario =
                catalog.scenario("F21-context-multi-requirement-edit");
        var obligation = catalog.expectedRequestObligation(scenario.id());
        Map<String, byte[]> before = fixtureBytes(
                catalog.fixtures().get(scenario.fixture()).manifest().files());
        String decisions = new String(
                before.get("docs/decisions.md"),
                StandardCharsets.UTF_8);
        Map<String, byte[]> after = withFile(
                before,
                "docs/decisions.md",
                decisions + "\nDecision 004: health checks remain read-only.\n");
        String validTrace = traceFor(obligation, true);

        assertTrue(requestObligationResult(scenario, before, after, validTrace));
        assertTrue(!requestObligationResult(scenario, before, after, ""));
        assertTrue(!requestObligationResult(scenario, before, after, "{"));

        var duplicate = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.readTree(validTrace.getBytes(StandardCharsets.UTF_8));
        var duplicateEvents = duplicate.withArray("events");
        duplicateEvents.add(duplicateEvents.get(0).deepCopy());
        assertTrue(!requestObligationResult(
                scenario,
                before,
                after,
                jsonText(duplicate)));

        var mismatched = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.readTree(validTrace.getBytes(StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                mismatched.withArray("events").get(0).get("data"))
                .put("canonicalSha256", "0".repeat(64));
        assertTrue(!requestObligationResult(
                scenario,
                before,
                after,
                jsonText(mismatched)));

        var wrongTarget = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.readTree(validTrace.getBytes(StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                wrongTarget.withArray("events").get(1).get("data"))
                .put("pathHint", "docs/other.md");
        assertTrue(!requestObligationResult(
                scenario,
                before,
                after,
                jsonText(wrongTarget)));

        var wrongScalar = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.readTree(validTrace.getBytes(StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                wrongScalar.withArray("events").get(1).get("data"))
                .put("expectedBytes", "49");
        assertTrue(!requestObligationResult(
                scenario,
                before,
                after,
                jsonText(wrongScalar)));

        var wrongOrder = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.readTree(validTrace.getBytes(StandardCharsets.UTF_8));
        var wrongOrderEvents = wrongOrder.withArray("events");
        com.fasterxml.jackson.databind.JsonNode obligationEvent =
                wrongOrderEvents.remove(0);
        wrongOrderEvents.insert(1, obligationEvent);
        assertTrue(!requestObligationResult(
                scenario,
                before,
                after,
                jsonText(wrongOrder)));

        var conflicting = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.readTree(validTrace.getBytes(StandardCharsets.UTF_8));
        var failedExpectation = conflicting.withArray("events").get(1).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) failedExpectation.get("data"))
                .put("status", "FAILED");
        conflicting.withArray("events").insert(2, failedExpectation);
        assertTrue(!requestObligationResult(
                scenario,
                before,
                after,
                jsonText(conflicting)));
    }

    private static boolean requestObligationResult(
            QualificationProtocol.Scenario scenario,
            Map<String, byte[]> before,
            Map<String, byte[]> after,
            String trace
    ) {
        return QualificationRequirementEvaluator.evaluate(
                        scenario,
                        new QualificationRequirementEvaluator.Evidence(
                                "",
                                trace,
                                before,
                                after))
                .stream()
                .filter(result -> result.id().equals("request-obligation"))
                .findFirst()
                .orElseThrow()
                .passed();
    }

    private static Map<String, byte[]> fixtureBytes(Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, value) ->
                bytes.put(path, value.getBytes(StandardCharsets.UTF_8)));
        return Map.copyOf(bytes);
    }

    private static Map<String, byte[]> withFile(
            Map<String, byte[]> files,
            String path,
            String content
    ) {
        Map<String, byte[]> copy = new LinkedHashMap<>(files);
        copy.put(path, content.getBytes(StandardCharsets.UTF_8));
        return Map.copyOf(copy);
    }

    private static String traceFor(
            dev.talos.runtime.obligation.RequestObligation obligation,
            boolean includeStructuredExpectation
    ) throws Exception {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(Map.of());
        var events = root.putArray("events");
        var obligationEvent = events.addObject();
        obligationEvent.put("type", "REQUEST_OBLIGATION_COMPILED");
        var obligationData = obligationEvent.putObject("data");
        obligationData.setAll((com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(obligation.redactedProjection()));
        if (includeStructuredExpectation) {
            var output = obligation.outputShape();
            var expectation = new dev.talos.runtime.expectation.AppendStatementExpectation(
                    output.target(),
                    output.label(),
                    output.statement(),
                    "request-obligation-v" + obligation.schemaVersion());
            String observed = output.label() + ": " + output.statement() + ".";
            var expectationEvent = events.addObject();
            expectationEvent.put("type", "EXPECTATION_VERIFIED");
            var expectationData = expectationEvent.putObject("data");
            expectationData.put("kind", expectation.kind());
            expectationData.put("status", "PASSED");
            expectationData.put(
                    "pathHint",
                    dev.talos.runtime.obligation.RequestObligation.diagnosticPath(
                            output.target()));
            expectationData.put("sourcePattern", expectation.sourcePattern());
            expectationData.put("expectedHash", expectation.expectedHash());
            expectationData.put("expectedBytes", expectation.expectedBytes());
            expectationData.put("expectedChars", expectation.expectedChars());
            expectationData.put("expectedLines", 1);
            expectationData.put(
                    "observedHash",
                    dev.talos.runtime.expectation.LiteralContentExpectation.hash(observed));
            expectationData.put(
                    "observedBytes",
                    dev.talos.runtime.expectation.LiteralContentExpectation.byteCount(observed));
            expectationData.put(
                    "observedChars",
                    dev.talos.runtime.expectation.LiteralContentExpectation.charCount(observed));
            expectationData.put("observedLines", 1);
        }
        var verificationEvent = events.addObject();
        verificationEvent.put("type", "VERIFICATION_COMPLETED");
        var verificationData = verificationEvent.putObject("data");
        verificationData.put("status", "PASSED");
        verificationData.put("problemCount", 0);
        verificationData.put("requiredClaimCount", 0);
        verificationData.put("unsatisfiedRequiredClaimCount", 0);
        verificationData.putArray("authoritativeProofKinds");
        return new String(QualificationJson.bytes(root), StandardCharsets.UTF_8);
    }

    private static String jsonText(com.fasterxml.jackson.databind.JsonNode value) {
        return new String(QualificationJson.bytes(value), StandardCharsets.UTF_8);
    }
}
