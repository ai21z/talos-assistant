package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.util.Sanitize.ExecutableJsonProjectionResult;
import dev.talos.core.util.Sanitize.ExecutableJsonSuppressionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionFailureTraceEventTest {
    @ParameterizedTest
    @EnumSource(value = ExecutableJsonProjectionResult.class, names = "EXACT", mode = EnumSource.Mode.EXCLUDE)
    void projectionFailureAddsOnlyFiniteMetadata(ExecutableJsonProjectionResult failure) throws Exception {
        var event = TurnTraceEvent.toolCallAuthorityRejected(
                "now", "tool_loop", null, "PROVIDER_TEXT_JSON", "TEXT_PROJECTION_MISMATCH", failure);
        var authority = (Map<?, ?>) event.data().get("executionAuthority");
        assertEquals(Set.of("schemaVersion", "status", "kind", "reasonCode", "projectionFailure"),
                authority.keySet());
        assertEquals(failure.name(), authority.get("projectionFailure"));
        assertEquals("TEXT_PROJECTION_MISMATCH", authority.get("reasonCode"));
        assertEquals("REJECTED", authority.get("status"));
        var serialized = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(event));
        assertEquals(failure.name(), serialized.path("data").path("executionAuthority")
                .path("projectionFailure").asText());
    }

    @Test
    void existingRejectionDoesNotAcquireProjectionMetadata() {
        var event = TurnTraceEvent.toolCallAuthorityRejected(
                "now", "tool_loop", null, "PROVIDER_TEXT_JSON", "TEXT_PROJECTION_SOURCE_MISSING");
        assertFalse(((Map<?, ?>) event.data().get("executionAuthority")).containsKey("projectionFailure"));
    }

    @ParameterizedTest
    @EnumSource(value = ExecutableJsonSuppressionStage.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void suppressionStageAddsOnlyFiniteMetadata(ExecutableJsonSuppressionStage stage) throws Exception {
        var event = TurnTraceEvent.toolCallAuthorityRejected("now", "tool_loop", null,
                "PROVIDER_TEXT_JSON", "TEXT_PROJECTION_MISMATCH",
                ExecutableJsonProjectionResult.SUPPRESSED_OR_UNSELECTED_PROPOSALS, stage);
        var authority = (Map<?, ?>) event.data().get("executionAuthority");
        assertEquals(Set.of("schemaVersion", "status", "kind", "reasonCode", "projectionFailure",
                "projectionFailureStage"), authority.keySet());
        assertEquals(stage.name(), authority.get("projectionFailureStage"));
        var serialized = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(event));
        assertEquals(stage.name(), serialized.path("data").path("executionAuthority")
                .path("projectionFailureStage").asText());
        assertThrows(IllegalArgumentException.class, () -> TurnTraceEvent.toolCallAuthorityRejected(
                "now", "tool_loop", null, "PROVIDER_TEXT_JSON", "TEXT_PROJECTION_MISMATCH",
                ExecutableJsonProjectionResult.EXACT_ROOTS_MISMATCH, stage));
    }

    @Test
    void mixedSuppressionKindsSerializeAsFiniteNamesWithoutSource() throws Exception {
        var kinds = Set.of(dev.talos.core.util.Sanitize.ExecutableJsonSuppressionKind.DUPLICATE_RECOGNIZED_ROOT,
                dev.talos.core.util.Sanitize.ExecutableJsonSuppressionKind.SELECTED_FENCE_TAIL_POTENTIAL);
        var event = TurnTraceEvent.toolCallAuthorityRejected("now", "tool_loop", null,
                "PROVIDER_TEXT_JSON", "TEXT_PROJECTION_MISMATCH",
                ExecutableJsonProjectionResult.SUPPRESSED_OR_UNSELECTED_PROPOSALS,
                ExecutableJsonSuppressionStage.SOURCE_SUPPRESSION, kinds);
        var authority = (Map<?, ?>) event.data().get("executionAuthority");
        assertEquals(Set.of("schemaVersion", "status", "kind", "reasonCode", "projectionFailure",
                "projectionFailureStage", "projectionSuppressionKinds"), authority.keySet());
        assertEquals(java.util.List.of("DUPLICATE_RECOGNIZED_ROOT", "SELECTED_FENCE_TAIL_POTENTIAL"),
                authority.get("projectionSuppressionKinds"));
        String serialized = new ObjectMapper().writeValueAsString(event);
        assertFalse(serialized.contains("sourceText"));
        assertFalse(serialized.contains("sourceOffset"));
        assertThrows(IllegalArgumentException.class, () -> TurnTraceEvent.toolCallAuthorityRejected(
                "now", "tool_loop", null, "PROVIDER_TEXT_JSON", "TEXT_PROJECTION_MISMATCH",
                ExecutableJsonProjectionResult.EXACT_ROOTS_MISMATCH,
                ExecutableJsonSuppressionStage.NONE, kinds));
    }

    @ParameterizedTest
    @EnumSource(dev.talos.core.tool.ToolProtocolText.SelectedFenceTailShape.class)
    void tailShapeAddsOnlyEnumMetadata(dev.talos.core.tool.ToolProtocolText.SelectedFenceTailShape shape)
            throws Exception {
        var kinds = Set.of(dev.talos.core.util.Sanitize.ExecutableJsonSuppressionKind.SELECTED_FENCE_TAIL_POTENTIAL);
        var event = TurnTraceEvent.toolCallAuthorityRejected("now", "tool_loop", null,
                "PROVIDER_TEXT_JSON", "TEXT_PROJECTION_MISMATCH",
                ExecutableJsonProjectionResult.SUPPRESSED_OR_UNSELECTED_PROPOSALS,
                ExecutableJsonSuppressionStage.SOURCE_SUPPRESSION, kinds, Set.of(shape));
        var authority = (Map<?, ?>) event.data().get("executionAuthority");
        assertEquals(Set.of("schemaVersion", "status", "kind", "reasonCode", "projectionFailure",
                "projectionFailureStage", "projectionSuppressionKinds", "projectionFenceTailShapes"), authority.keySet());
        assertEquals(java.util.List.of(shape.name()), authority.get("projectionFenceTailShapes"));
        var serialized = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(event));
        assertEquals(shape.name(), serialized.path("data").path("executionAuthority")
                .path("projectionFenceTailShapes").get(0).asText());
        assertThrows(IllegalArgumentException.class, () -> TurnTraceEvent.toolCallAuthorityRejected(
                "now", "tool_loop", null, "PROVIDER_TEXT_JSON", "TEXT_PROJECTION_MISMATCH",
                ExecutableJsonProjectionResult.SUPPRESSED_OR_UNSELECTED_PROPOSALS,
                ExecutableJsonSuppressionStage.SOURCE_SUPPRESSION, Set.of(), Set.of(shape)));
    }

    @Test
    void mixedTailShapesAreDeterministicAndLegacyOverloadAddsNone() {
        var kinds = Set.of(dev.talos.core.util.Sanitize.ExecutableJsonSuppressionKind.SELECTED_FENCE_TAIL_POTENTIAL);
        var shapes = java.util.EnumSet.allOf(dev.talos.core.tool.ToolProtocolText.SelectedFenceTailShape.class);
        var event = TurnTraceEvent.toolCallAuthorityRejected("now", "tool_loop", null,
                "PROVIDER_TEXT_JSON", "TEXT_PROJECTION_MISMATCH",
                ExecutableJsonProjectionResult.SUPPRESSED_OR_UNSELECTED_PROPOSALS,
                ExecutableJsonSuppressionStage.SOURCE_SUPPRESSION, kinds, shapes);
        assertEquals(shapes.stream().sorted().map(Enum::name).toList(),
                ((Map<?, ?>) event.data().get("executionAuthority")).get("projectionFenceTailShapes"));
        var legacy = TurnTraceEvent.toolCallAuthorityRejected("now", "tool_loop", null,
                "PROVIDER_TEXT_JSON", "TEXT_PROJECTION_MISMATCH",
                ExecutableJsonProjectionResult.SUPPRESSED_OR_UNSELECTED_PROPOSALS,
                ExecutableJsonSuppressionStage.SOURCE_SUPPRESSION, kinds);
        assertFalse(((Map<?, ?>) legacy.data().get("executionAuthority")).containsKey("projectionFenceTailShapes"));
    }

    @Test
    void exactOrUnrelatedResultCannotBeRecordedAsProjectionFailure() {
        assertThrows(IllegalArgumentException.class, () -> TurnTraceEvent.toolCallAuthorityRejected(
                "now", "tool_loop", null, "PROVIDER_TEXT_JSON", "TEXT_PROJECTION_MISMATCH",
                ExecutableJsonProjectionResult.EXACT));
        assertThrows(IllegalArgumentException.class, () -> TurnTraceEvent.toolCallAuthorityRejected(
                "now", "tool_loop", null, "PROVIDER_TEXT_JSON", "TEXT_PROTOCOL_INELIGIBLE",
                ExecutableJsonProjectionResult.EXACT_ROOTS_MISMATCH));
    }
}
