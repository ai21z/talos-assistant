package dev.talos.runtime.verification;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Answer selection is carried separately from rendered text. */
public record ReadOnlyWebDiagnosticResult(
        String answer,
        ReadOnlyWebAnswerAssessment assessment,
        List<StaticWebDiagnostic> diagnostics,
        Association association,
        ReadOnlyAnswerComponents components
) {
    public ReadOnlyWebDiagnosticResult {
        assessment = assessment == null ? ReadOnlyWebAnswerAssessment.notApplicable() : assessment;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        association = association == null ? Association.empty() : association;
        components = components == null ? ReadOnlyAnswerComponents.empty() : components;
    }

    public ReadOnlyWebDiagnosticResult(String answer, ReadOnlyWebAnswerAssessment assessment,
                                       List<StaticWebDiagnostic> diagnostics) {
        this(answer, assessment, diagnostics, Association.empty(), ReadOnlyAnswerComponents.empty());
    }

    public ReadOnlyWebDiagnosticResult(String answer, ReadOnlyWebAnswerAssessment assessment,
                                       List<StaticWebDiagnostic> diagnostics, Association association) {
        this(answer, assessment, diagnostics, association, ReadOnlyAnswerComponents.empty());
    }

    public ReadOnlyWebDiagnosticResult withAssociation(String selectionId, String generationId,
                                                       String input, List<String> observationIds) {
        return new ReadOnlyWebDiagnosticResult(answer, assessment, diagnostics,
                new Association(selectionId, generationId, textHash(input), textHash(answer),
                        observationIds, components.active() ? "observed-answer-components-v1" : "observed-static-web-v1"), components);
    }

    public static String textHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable.", unavailable);
        }
    }

    /** Only assessment metadata is carried, no execution authority is granted. */
    public record Association(String selectionId, String generationId, String inputHash,
                              String outputHash, List<String> observationIds, String analysisVersion) {
        public Association {
            selectionId = checked(selectionId, "[a-f0-9-]{36}");
            generationId = checked(generationId, "[A-Za-z0-9._-]{1,512}");
            inputHash = checked(inputHash, "[a-f0-9]{64}");
            outputHash = checked(outputHash, "[a-f0-9]{64}");
            analysisVersion = checked(analysisVersion, "observed-static-web-v1|observed-answer-components-v1");
            observationIds = observationIds == null ? List.of() : List.copyOf(observationIds);
            if (observationIds.size() > 8) throw new IllegalArgumentException("Observation limit exceeded.");
            observationIds.forEach(value -> checked(value, "[a-f0-9-]{36}:[1-9][0-9]{0,18}"));
        }

        public static Association empty() {
            return new Association("", "", "", "", List.of(), "");
        }

        private static String checked(String value, String pattern) {
            if (value == null) return "";
            if (!value.isEmpty() && !value.matches(pattern))
                throw new IllegalArgumentException("Invalid assessment identity.");
            return value;
        }
    }

    public static ReadOnlyWebDiagnosticResult unaffected(String answer) {
        return new ReadOnlyWebDiagnosticResult(answer, ReadOnlyWebAnswerAssessment.notApplicable(), List.of());
    }
}
