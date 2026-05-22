package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalGateTest {

    @Test void noOpAlwaysApproves() {
        ApprovalGate gate = new NoOpApprovalGate();
        assertTrue(gate.approve("send email", "to user@example.com"));
        assertTrue(gate.approve("delete file", null));
        assertTrue(gate.approve(null, null));
    }

    @Test void customGateCanDeny() {
        ApprovalGate gate = (desc, detail) -> false;
        assertFalse(gate.approve("anything", "detail"));
    }

    @Test void conditionalGate() {
        // Gate that only approves "read" operations
        ApprovalGate gate = (desc, detail) ->
                desc != null && desc.toLowerCase().startsWith("read");

        assertTrue(gate.approve("read file", null));
        assertFalse(gate.approve("delete file", null));
        assertFalse(gate.approve(null, null));
    }
}

