package dev.talos.spi.types;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderContextMembershipTest {
    private static final String BODY = "{\"messages\":[{\"role\":\"system\",\"content\":\"secret context\"}]}";
    private static final ProviderContextMembership.MessageKey KEY =
            ProviderContextMembership.key("system", "secret context");

    @Test void exactDecodedStringsDetermineMembershipAndDoNotLeakThroughDiagnostics() {
        var membership = ProviderContextMembership.decode(BODY);
        assertTrue(membership.observed());
        assertTrue(membership.contains(KEY));
        assertFalse(membership.contains(ProviderContextMembership.key("System", "secret context")));
        assertFalse(membership.contains(ProviderContextMembership.key("system ", "secret context")));
        assertFalse(membership.contains(ProviderContextMembership.key("user", "secret context")));
        assertFalse(membership.contains(ProviderContextMembership.key("system", "secret context ")));
        assertFalse(membership.contains(null));
        assertFalse(membership.toString().contains("secret context"));
        assertFalse(KEY.toString().contains("secret context"));
        assertFalse(ProviderContextMembership.key("secret role", "content").toString().contains("secret role"));
        assertEquals(membership, ProviderContextMembership.decode(BODY));
        assertEquals(membership.hashCode(), ProviderContextMembership.decode(BODY).hashCode());
        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ProviderContextMembership.key("user", "abc").contentHash());
        assertTrue(ProviderContextMembership.decode(
                "{\"messages\":[{\"role\":\"system\",\"content\":\"secret \\u0063ontext\"}]}").contains(KEY));
    }

    @Test void quotedMessagesAndSubstringMatchesCannotManufactureMembership() {
        for (String body : List.of(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"quoted secret context\"}]}",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"{\\\"role\\\":\\\"system\\\","
                        + "\\\"content\\\":\\\"secret context\\\"}\"}]}",
                "{\"messages\":[],\"metadata\":{\"role\":\"system\",\"content\":\"secret context\"}}",
                "{\"metadata\":{\"messages\":[{\"role\":\"system\",\"content\":\"secret context\"}]}}")) {
            assertFalse(ProviderContextMembership.decode(body).contains(KEY), body);
        }
    }

    @Test void malformedDuplicateAndUnsupportedShapesAreUnknownWithBoundedBodyIdentity() {
        for (String body : List.of(
                "", "null", "[]", "{}", "{\"messages\":null}", "{\"messages\":{}}",
                "{\"messages\":[]", "{\"messages\":[]} trailing", "{\"messages\":[]} {}",
                "{\"messages\":[],\"messages\":[]}",
                "{\"messages\":[{\"role\":\"user\",\"role\":\"system\",\"content\":\"secret context\"}]}",
                "{\"messages\":[{\"role\":\"system\",\"content\":\"a\",\"content\":\"secret context\"}]}",
                "{\"messages\":[],\"metadata\":{\"duplicate\":1,\"duplicate\":2}}",
                "{\"messages\":[{\"content\":\"secret context\"}]}",
                "{\"messages\":[{\"role\":\"system\"}]}",
                "{\"messages\":[{\"role\":4,\"content\":\"secret context\"}]}",
                "{\"messages\":[{\"role\":\"system\",\"content\":null}]}",
                "{\"messages\":[{\"role\":\"system\",\"content\":[{\"text\":\"secret context\"}]}]}",
                "{\"messages\":[\"secret context\"]}",
                "{\"messages\":[{\"role\":\"system\",\"content\":\"secret context\"},null]}",
                "{\"messages\":[{\"role\":\"system\",\"content\":\"\\uD800\"}]}")) {
            var membership = assertDoesNotThrow(() -> ProviderContextMembership.decode(body));
            assertFalse(membership.observed(), body);
            assertFalse(membership.contains(KEY), body);
            assertEquals(ProviderTransportHandoff.DispatchEvidence.fromProviderBody(body, List.of()).providerBodyHash(),
                    membership.bodyHash());
        }
        assertSame(ProviderContextMembership.unknown(), ProviderContextMembership.decode(null));
    }

    @Test void emptyMessagesAreObservedAndOtherMessageMetadataIsAccepted() {
        var empty = ProviderContextMembership.decode("{\"messages\":[]}");
        assertTrue(empty.observed());
        assertFalse(empty.contains(KEY));
        assertTrue(ProviderContextMembership.decode(
                "{\"messages\":[{\"role\":\"system\",\"content\":\"secret context\","
                        + "\"metadata\":{\"nested\":[1,true,null]}}]}").contains(KEY));
    }

    @Test void byteStringRoleMessageAndNestingLimitsFailClosed() {
        String empty = "{\"messages\":[]}";
        String atBodyLimit = empty + " ".repeat(ProviderContextMembership.MAX_BODY_BYTES - empty.length());
        assertTrue(ProviderContextMembership.decode(atBodyLimit).observed());
        assertEquals("", ProviderContextMembership.decode(atBodyLimit + " ").bodyHash());
        assertEquals("", ProviderContextMembership.decode(
                "\u20ac".repeat(ProviderContextMembership.MAX_BODY_BYTES / 3 + 1)).bodyHash());

        String stringPrefix = "{\"messages\":[{\"role\":\"user\",\"content\":\"";
        String atStringLimit = "x".repeat(ProviderContextMembership.MAX_STRING_CHARS);
        assertTrue(ProviderContextMembership.decode(stringPrefix + atStringLimit + "\"}]}").observed());
        assertFalse(ProviderContextMembership.decode(stringPrefix + atStringLimit + "x\"}]}").observed());
        assertFalse(ProviderContextMembership.decode(
                "{\"messages\":[],\"ignored\":\"" + atStringLimit + "x\"}").observed());
        assertFalse(ProviderContextMembership.decode(
                "{\"messages\":[{\"role\":\"" + "r".repeat(ProviderContextMembership.MAX_ROLE_CHARS + 1)
                        + "\",\"content\":\"c\"}]}").observed());

        String message = "{\"role\":\"user\",\"content\":\"c\"}";
        String atMessageLimit = "{\"messages\":[" + (message + ",").repeat(ProviderContextMembership.MAX_MESSAGES - 1)
                + message + "]}";
        assertTrue(ProviderContextMembership.decode(atMessageLimit).observed());
        assertFalse(ProviderContextMembership.decode("{\"messages\":["
                + (message + ",").repeat(ProviderContextMembership.MAX_MESSAGES) + message + "]}").observed());
        assertFalse(ProviderContextMembership.decode("{\"messages\":[],\"nested\":"
                + "[".repeat(ProviderContextMembership.MAX_NESTING_DEPTH)
                + "0" + "]".repeat(ProviderContextMembership.MAX_NESTING_DEPTH) + "}").observed());
        assertThrows(IllegalArgumentException.class, () -> ProviderContextMembership.key("system", "\uD800"));
    }

    @Test void dispatchDecodesTheActualBodyAndIgnoresUnserializedRequestFields() {
        var request = new ChatRequest("local", "model", "secret context", "", List.of(), null,
                List.of(new ChatMessage("system", "secret context")));
        var dispatch = ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                "{\"messages\":[{\"role\":\"system\",\"content\":\"altered\"}]}", List.of(), request);
        assertTrue(dispatch.contextMembership().observed());
        assertFalse(dispatch.contextMembership().contains(KEY));
        assertEquals(dispatch.providerBodyHash(), dispatch.contextMembership().bodyHash());
        assertFalse(ProviderTransportHandoff.DispatchEvidence.fromProviderBody("{}", List.of(), request)
                .contextMembership().observed());
    }

    @Test void legacyConstructorsAndProcessReceiptPreservePriorReadMetadata() {
        String body = "{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"one\",\"content\":\"source\"}]}";
        var request = new ChatRequest("local", "model", "", "", List.of(), null,
                List.of(ChatMessage.toolResult("one", "source")), List.of(),
                new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.TEXT, "",
                        List.of(ProviderReadInputBinding.REQUEST_TAG)).withSingleAttempt(true));
        var dispatch = ProviderTransportHandoff.DispatchEvidence.fromProviderBody(body, List.of(), request);
        assertTrue(dispatch.readInputMembership().available());
        var receipt = receipt();
        var bound = dispatch.withProcessReceipt(receipt);
        assertEquals(Optional.of(receipt), bound.processReceipt());
        assertSame(dispatch.readInputMembership(), bound.readInputMembership());
        assertSame(dispatch.contextMembership(), bound.contextMembership());
        assertEquals(bound, bound.withProcessReceipt(receipt));
        assertFalse(new ProviderTransportHandoff.DispatchEvidence(dispatch.providerBodyHash(), List.of())
                .contextMembership().observed());
        assertFalse(new ProviderTransportHandoff.DispatchEvidence(dispatch.providerBodyHash(), List.of(), Optional.of(receipt))
                .contextMembership().observed());
        var legacy = new ProviderTransportHandoff.DispatchEvidence(dispatch.providerBodyHash(), List.of(),
                Optional.of(receipt), dispatch.readInputMembership());
        assertSame(dispatch.readInputMembership(), legacy.readInputMembership());
        assertFalse(legacy.contextMembership().observed());
        assertFalse(ProviderTransportHandoff.DispatchEvidence.unbound().contextMembership().observed());
        assertEquals(ProviderContextMembership.unknown(), new ProviderTransportHandoff.DispatchEvidence(
                dispatch.providerBodyHash(), List.of(), Optional.empty(), dispatch.readInputMembership(), null)
                .contextMembership());
    }

    private static ProviderProcessLaunchReceipt receipt() {
        return new ProviderProcessLaunchReceipt(
                8101L, 1_721_600_000_000L, 1_721_600_000_100L, 1_721_600_000_200L,
                "0123456789abcdef0123456789abcdef",
                "a".repeat(64), "b".repeat(64), "c".repeat(64),
                "e".repeat(64), ProviderProcessLaunchReceipt.LaunchArgumentSet.VERIFICATION,
                "f".repeat(64), ProviderProcessLaunchReceipt.EnvironmentContract.QUALIFICATION_RELEVANT_ALLOWLIST_V1,
                "127.0.0.1", 8080, true, "1".repeat(64), "qualification-model",
                ProviderProcessLaunchReceipt.ServedIdentity.MATCHED,
                ProviderProcessLaunchReceipt.EndpointObserverKind.WINDOWS_NETSTAT_NOFOLLOW_SHA256,
                "2".repeat(64),
                ProviderProcessLaunchReceipt.EndpointOwnershipBoundary
                        .WINDOWS_NETSTAT_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT,
                ProviderProcessLaunchReceipt.StableEndpointAssumption
                        .WINDOWS_TRUSTED_HELPER_AND_STABLE_LISTENER_OWNER_BETWEEN_OBSERVATION_AND_NETWORK_TRANSITION,
                ProviderProcessLaunchReceipt.ContentVerificationBoundary.IMMEDIATE_PRELAUNCH_NOFOLLOW_SHA256,
                ProviderProcessLaunchReceipt.StableFilesystemAssumption.STABLE_LOCAL_FILES_DURING_PROCESS_LOAD);
    }
}
