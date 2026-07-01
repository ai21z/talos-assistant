package dev.talos.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolRiskLevel} and risk-aware {@link ToolDescriptor}.
 */
class ToolRiskLevelTest {

    // ── ToolRiskLevel ───────────────────────────────────────────────

    @Test
    void readOnlyDoesNotRequireApproval() {
        assertFalse(ToolRiskLevel.READ_ONLY.requiresApproval());
    }

    @Test
    void writeRequiresApproval() {
        assertTrue(ToolRiskLevel.WRITE.requiresApproval());
    }

    @Test
    void destructiveRequiresApproval() {
        assertTrue(ToolRiskLevel.DESTRUCTIVE.requiresApproval());
    }

    // ── ToolDescriptor risk level ───────────────────────────────────

    @Test
    void descriptorDefaultsToReadOnly() {
        var desc = new ToolDescriptor("test", "a test tool");
        assertEquals(ToolRiskLevel.READ_ONLY, desc.riskLevel());
    }

    @Test
    void descriptorWithSchemaDefaultsToReadOnly() {
        var desc = new ToolDescriptor("test", "a test tool", "{\"type\":\"object\"}");
        assertEquals(ToolRiskLevel.READ_ONLY, desc.riskLevel());
    }

    @Test
    void descriptorWithExplicitRiskLevel() {
        var desc = new ToolDescriptor("test", "a test tool", null, ToolRiskLevel.WRITE);
        assertEquals(ToolRiskLevel.WRITE, desc.riskLevel());
    }

    @Test
    void descriptorNullRiskLevelDefaultsToReadOnly() {
        var desc = new ToolDescriptor("test", "a test tool", null, null);
        assertEquals(ToolRiskLevel.READ_ONLY, desc.riskLevel());
    }

    @Test
    void descriptorDestructiveRiskLevel() {
        var desc = new ToolDescriptor("delete", "delete files", "{}", ToolRiskLevel.DESTRUCTIVE);
        assertEquals(ToolRiskLevel.DESTRUCTIVE, desc.riskLevel());
        assertTrue(desc.riskLevel().requiresApproval());
    }
}

