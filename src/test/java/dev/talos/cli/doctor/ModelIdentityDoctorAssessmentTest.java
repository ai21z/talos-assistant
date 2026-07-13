package dev.talos.cli.doctor;

import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelIdentityDoctorAssessmentTest {

    @Test
    void qualifiedIdentityPassesWithBoundedAgreementFacts() {
        ModelIdentitySnapshot identity = base()
                .resolvedProfile("qwen3-8b")
                .artifactState("EXACT")
                .artifactVerificationIdentity("a".repeat(64))
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .propsState("AVAILABLE")
                .propsResponseSha256("b".repeat(64))
                .chatAvailable(true)
                .build();

        ModelIdentityDoctorAssessment assessment = ModelIdentityDoctorAssessment.assess(identity);

        assertEquals(ProbeResult.Status.PASS, assessment.status());
        assertTrue(assessment.detail().contains("identity qualified"), assessment.detail());
        assertTrue(assessment.detail().contains("qwen3-8b"), assessment.detail());
        assertTrue(assessment.detail().contains("artifact exact"), assessment.detail());
        assertTrue(assessment.detail().contains("serving matched"), assessment.detail());
        assertTrue(assessment.detail().contains("tools native"), assessment.detail());
        assertTrue(assessment.detail().contains("props available"), assessment.detail());
        assertFalse(assessment.detail().contains("C:/private"), assessment.detail());
    }

    @Test
    void qualifiedGraniteIdentityPassesAsTheExactCatalogProfile() {
        var profile = dev.talos.engine.llamacpp.LlamaCppModelCatalog
                .byAlias("granite-4.1-3b")
                .orElseThrow();
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel(profile.alias())
                .configuredModelFile("C:/private/" + profile.artifactFile())
                .declaredProfile(profile.alias())
                .resolvedProfile(profile.alias())
                .observedModelFile("D:/private/" + profile.artifactFile())
                .artifactState("EXACT")
                .artifactVerificationIdentity(profile.artifactSha256())
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveContextTokens(profile.runtime().contextTokens())
                .engineLane(profile.runtime().compatibleEngineLanes().getFirst())
                .disposition(profile.disposition().label())
                .propsState("AVAILABLE")
                .propsResponseSha256("b".repeat(64))
                .chatAvailable(true)
                .build();

        ModelIdentityDoctorAssessment assessment = ModelIdentityDoctorAssessment.assess(identity);

        assertEquals(ProbeResult.Status.PASS, assessment.status());
        assertTrue(assessment.detail().contains("identity qualified"), assessment.detail());
        assertTrue(assessment.detail().contains("granite-4.1-3b"), assessment.detail());
        assertTrue(assessment.detail().contains("artifact exact"), assessment.detail());
        assertTrue(assessment.detail().contains("serving matched"), assessment.detail());
        assertTrue(assessment.detail().contains("tools native"), assessment.detail());
        assertFalse(assessment.detail().contains("C:/private"), assessment.detail());
        assertFalse(assessment.detail().contains("D:/private"), assessment.detail());
    }

    @Test
    void reachableConnectOnlyServerWithoutPropsWarnsRatherThanPassesOrFails() {
        ModelIdentitySnapshot identity = base()
                .artifactState("UNCHECKED")
                .servedIdentityState("UNVERIFIED")
                .resolution("UNVERIFIED")
                .effectiveToolMode("CHAT_ONLY")
                .propsState("UNAVAILABLE")
                .chatAvailable(true)
                .reasonCodes(List.of("SERVER_FACTS_UNAVAILABLE"))
                .build();

        ModelIdentityDoctorAssessment assessment = ModelIdentityDoctorAssessment.assess(identity);

        assertEquals(ProbeResult.Status.WARN, assessment.status());
        assertTrue(assessment.detail().contains("identity unverified"), assessment.detail());
        assertTrue(assessment.detail().contains("server-facts-unavailable"), assessment.detail());
    }

    @Test
    void conflictingServedIdentityFailsEvenWhenHttpChatCouldRespond() {
        ModelIdentitySnapshot identity = base()
                .artifactState("EXACT")
                .servedIdentityState("MISMATCHED")
                .resolution("REFUSED")
                .effectiveToolMode("CHAT_ONLY")
                .propsState("AVAILABLE")
                .chatAvailable(false)
                .reasonCodes(List.of("SERVER_MODEL_MISMATCH"))
                .build();

        ModelIdentityDoctorAssessment assessment = ModelIdentityDoctorAssessment.assess(identity);

        assertEquals(ProbeResult.Status.FAIL, assessment.status());
        assertTrue(assessment.detail().contains("identity refused"), assessment.detail());
        assertTrue(assessment.detail().contains("server-model-mismatch"), assessment.detail());
    }

    @Test
    void malformedPropsContractFailsRatherThanMasqueradingAsOrdinaryAbsence() {
        ModelIdentitySnapshot identity = base()
                .artifactState("UNCHECKED")
                .servedIdentityState("UNVERIFIED")
                .resolution("UNVERIFIED")
                .effectiveToolMode("CHAT_ONLY")
                .propsState("REFUSED")
                .chatAvailable(true)
                .reasonCodes(List.of("SERVER_FACTS_UNAVAILABLE"))
                .build();

        ModelIdentityDoctorAssessment assessment = ModelIdentityDoctorAssessment.assess(identity);

        assertEquals(ProbeResult.Status.FAIL, assessment.status());
        assertTrue(assessment.detail().contains("props refused"), assessment.detail());
    }

    private static ModelIdentitySnapshot.Builder base() {
        return ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel("qwen3-8b")
                .configuredModelFile("C:/private/Qwen3-8B-Q4_K_M.gguf")
                .declaredProfile("qwen3-8b")
                .observedModelFile("D:/private/Qwen3-8B-Q4_K_M.gguf")
                .effectiveContextTokens(8192)
                .engineLane("win-x64-cuda-13.3:b9918");
    }
}
