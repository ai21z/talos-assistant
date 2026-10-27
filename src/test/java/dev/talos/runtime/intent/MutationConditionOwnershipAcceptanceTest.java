package dev.talos.runtime.intent;

import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independently specified request witnesses, not model generations or mocked
 * parser decisions. The oracle concerns the owner of a condition, rather than
 * any particular parser rule or diagnostic reason.
 */
class MutationConditionOwnershipAcceptanceTest {
    private static final Set<TargetRole> POSITIVE_ROLES = Set.of(
            TargetRole.MUST_MUTATE,
            TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE,
            TargetRole.OUTPUT_DESTINATION,
            TargetRole.MAY_MUTATE);

    @ParameterizedTest(name = "{0}")
    @MethodSource("selectorBehaviorWitnesses")
    void desiredRuntimeEventsDoNotConditionTheRequestedEdit(Witness witness) {
        assertAuthority(witness);
    }

    static Stream<Witness> selectorBehaviorWitnesses() {
        return Stream.of(
                new Witness("U01 original preservation regression",
                        "Keep styles.css unchanged, including its current visual asset references. "
                                + "Update index.html and scripts.js so #teaser-button updates #teaser-status when clicked.",
                        Set.of("index.html", "scripts.js"), Set.of("styles.css"), Set.of()),
                allow("P01 independent selector names", "Update launch.js so #launch-button updates #launch-status when clicked.",
                        "launch.js"),
                new Witness("P02 independent two-file selector behavior",
                        "Update panel.html and panel.js so #submit-control updates #submit-status when clicked.",
                        Set.of("panel.html", "panel.js"), Set.of(), Set.of()),
                new Witness("P03 independent preservation and selector behavior",
                        "Keep theme.css unchanged. Update actions.js so #save-action updates #save-result when clicked.",
                        Set.of("actions.js"), Set.of("theme.css"), Set.of()),
                new Witness("P04 independent unconditional action precedes conditioned action",
                        "Update launch.js so #launch-button updates #launch-status when clicked. If approved, update theme.css.",
                        Set.of("launch.js"), Set.of(), Set.of("theme.css")),
                new Witness("P05 independent unconditional action follows conditioned action",
                        "If approved, update theme.css. Update launch.js so #launch-button updates #launch-status when clicked.",
                        Set.of("launch.js"), Set.of(), Set.of("theme.css")),
                allow("P06 pressed event and enables predicate", "Update toggles.js so #power-control enables #power-panel when pressed.",
                        "toggles.js"),
                allow("P07 selected event and hides predicate", "Update menus.js so #mode-choice hides #mode-details when selected.",
                        "menus.js"),
                new Witness("P08 changed ordinary file list and behavior names",
                        "Update client.js and events.js so #send-control changes #send-state when activated.",
                        Set.of("client.js", "events.js"), Set.of(), Set.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("actualOrAmbiguousEditConditionWitnesses")
    void unresolvedEditConditionsCannotSupplyTargetAuthority(Witness witness) {
        assertAuthority(witness);
    }

    static Stream<Witness> actualOrAmbiguousEditConditionWitnesses() {
        return Stream.of(
                deny("C01 leading approval condition", "If the maintainer approves this edit, update controls.js so the switch enables the fan when pressed.",
                        "controls.js"),
                deny("C02 approval before behavior", "Context is available. Update controls.js only if the maintainer approves this edit, so the switch enables the fan when pressed.",
                        "controls.js"),
                deny("C03 approval after behavior", "Context is available. Update controls.js so the switch enables the fan when pressed, but only if the maintainer has approved this edit.",
                        "controls.js"),
                deny("C04 leading approval when", "When you have approval to make the edit, update queue.js so the worker retries when the connection drops.",
                        "queue.js"),
                deny("C05 approval only after", "Context is available. Update queue.js only after the maintainer approves the edit so the worker retries when the connection drops.",
                        "queue.js"),
                deny("C06 trailing provided condition", "Context is available. Update upload.js so the progress label changes when the upload finishes, provided that the maintainer has approved the edit.",
                        "upload.js"),
                deny("A01 ambiguous if needed owner", "Context is available. Update controls.js so the switch enables the fan when pressed, if needed.",
                        "controls.js"),
                deny("A02 ambiguous approval owner", "Context is available. Update controls.js so the switch enables the fan when approved.",
                        "controls.js"),
                new Witness("M04 ambiguous same-sentence condition ownership",
                        "Context is available. Update controls.js so the switch enables the fan when pressed and update styles.css if approved.",
                        Set.of(), Set.of(), Set.of("controls.js", "styles.css")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reportedProhibitedAndMalformedWitnesses")
    void behaviorClauseCannotLaunderReportedProhibitedOrMalformedActions(Witness witness) {
        assertAuthority(witness);
    }

    static Stream<Witness> reportedProhibitedAndMalformedWitnesses() {
        return Stream.of(
                deny("R01 reported ticket", "The ticket says to update queue.js so the worker retries when the connection drops. Explain the ticket.",
                        "queue.js"),
                deny("R02 quoted guide", "The guide says \"Update upload.js so the progress label changes when the upload finishes.\" Summarize the guide.",
                        "upload.js"),
                new Witness("R03 prohibited edit",
                        "Do not update controls.js so the switch enables the fan when pressed.",
                        Set.of(), Set.of("controls.js"), Set.of()),
                deny("X01 NUL inside action", "Context is available. Up\u0000date controls.js so the switch enables the fan when pressed.",
                        "controls.js"),
                deny("X02 NUL inside condition", "Context is available. Update controls.js so the switch enables the fan wh\u0000en pressed.",
                        "controls.js"),
                deny("X03 lone CR inside behavior", "Context is available. Update controls.js so the switch enables\rfan operation when pressed.",
                        "controls.js"),
                deny("X04 unclosed quote", "Context is available. Update controls.js so the switch enables the fan when \"pressed.",
                        "controls.js"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("selectorBehaviorSafetyWitnesses")
    void supportedBehaviorBodyCannotBypassScopeBindingOrRawSafety(Witness witness) {
        assertAuthority(witness);
    }

    static Stream<Witness> selectorBehaviorSafetyWitnesses() {
        return Stream.of(
                deny("S01 approval between target and behavior",
                        "Update client.js only if the maintainer approves this edit, so #send-control changes #send-state when activated.",
                        "client.js"),
                deny("S02 approval after complete behavior",
                        "Update client.js so #send-control changes #send-state when activated, but only if the maintainer approves this edit.",
                        "client.js"),
                deny("S03 leading approval around supported behavior",
                        "If the maintainer approves this edit, update client.js so #send-control changes #send-state when activated.",
                        "client.js"),
                deny("S04 reported supported behavior",
                        "The ticket says to update client.js so #send-control changes #send-state when activated. Explain the ticket.",
                        "client.js"),
                deny("S05 quoted supported behavior",
                        "The guide says \"Update client.js so #send-control changes #send-state when activated.\" Summarize the guide.",
                        "client.js"),
                new Witness("S06 prohibited supported behavior",
                        "Do not update client.js so #send-control changes #send-state when activated.",
                        Set.of(), Set.of("client.js"), Set.of()),
                deny("S07 passive event belongs to another selector",
                        "Update client.js so #send-control changes #send-state when #cancel-control is activated.",
                        "client.js"),
                deny("S08 user event belongs to another selector",
                        "Update client.js so #send-control changes #send-state when the user activates #cancel-control.",
                        "client.js"),
                deny("S09 selector subject identity remains case-sensitive",
                        "Update client.js so #Send-control changes #send-state when #send-control is activated.",
                        "client.js"),
                new Witness("S10 disjunctive file list",
                        "Update client.js or events.js so #send-control changes #send-state when activated.",
                        Set.of(), Set.of(), Set.of("client.js", "events.js")),
                new Witness("S11 and-or file list",
                        "Update client.js and/or events.js so #send-control changes #send-state when activated.",
                        Set.of(), Set.of(), Set.of("client.js", "events.js")),
                new Witness("S12 raw NUL before an actual child action",
                        "Context is available. Update a.js so #x updates #y wh\u0000en clicked, then update b.js.",
                        Set.of(), Set.of(), Set.of("a.js", "b.js")),
                new Witness("S13 raw lone CR before an actual child action",
                        "Context is available. Update a.js so #x updates #y wh\ren clicked, then update b.js.",
                        Set.of(), Set.of(), Set.of("a.js", "b.js")),
                new Witness("S14 two spaces do not detach nested approval",
                        "Edit root.js so it works, and update launch.js  so #b updates #s when clicked, if approved.",
                        Set.of("root.js"), Set.of(), Set.of("launch.js")),
                new Witness("S15 tab does not detach nested approval",
                        "Edit root.js so it works, and update launch.js\tso #b updates #s when clicked, if approved.",
                        Set.of("root.js"), Set.of(), Set.of("launch.js")),
                new Witness("S16 raw NUL immediately after a target",
                        "Context is available. Update a.js\u0000 so #x updates #y when clicked, then update b.js.",
                        Set.of(), Set.of(), Set.of("a.js", "b.js")),
                new Witness("S17 independent fresh sentence after NUL-tainted behavior",
                        "Context is available. Update a.js so #x updates #y wh\u0000en clicked, then update b.js. Edit later.js.",
                        Set.of("later.js"), Set.of(), Set.of("a.js", "b.js")),
                new Witness("S18 independent fresh sentence after CR-tainted behavior",
                        "Context is available. Update a.js so #x updates #y wh\ren clicked, then update b.js. Edit later.js.",
                        Set.of("later.js"), Set.of(), Set.of("a.js", "b.js")),
                new Witness("S19 forbidden winner cannot erase an earlier conditional region",
                        "Update a.js beside b.js if approved. Do not update a.js.",
                        Set.of(), Set.of("a.js"), Set.of("b.js")),
                new Witness("S20 independent edit cannot restore an earlier conditional region",
                        "Update a.js beside b.js if approved. Do not update a.js. Edit later.js.",
                        Set.of("later.js"), Set.of("a.js"), Set.of("b.js")));
    }

    private static void assertAuthority(Witness witness) {
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(witness.request());
        TurnPolicyTrace trace;
        try {
            trace = TurnPolicyTrace.from(resolution, "APPLY", List.of(), List.of());
        } catch (RuntimeException failure) {
            throw new AssertionError(witness.id() + ": " + resolution, failure);
        }
        boolean contentAuthorized = !witness.required().isEmpty();
        boolean globalAllowed = resolution.contract().mutationAllowed();
        Map<String, TargetRole> intentRoles = resolution.intent().targets().targets().stream()
                .collect(Collectors.toUnmodifiableMap(TargetRef::path, TargetRef::role));
        Map<String, TargetRole> traceRoles = trace.rolefulTargets().stream()
                .collect(Collectors.toUnmodifiableMap(TurnPolicyTrace.RolefulTarget::path,
                        target -> TargetRole.valueOf(target.role())));
        Set<String> namedTargets = Stream.of(witness.required(), witness.forbidden(), witness.denied())
                .flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
        String context = witness.id() + ": " + witness.request() + "\n" + resolution;

        assertAll(context,
                () -> assertEquals(globalAllowed, resolution.intent().mutationAllowed(), "intent global flag"),
                () -> assertEquals(globalAllowed, trace.mutationAllowed(), "trace global flag"),
                () -> assertEquals(contentAuthorized, resolution.hasAuthorizedContentMutation(), "content authority"),
                () -> assertEquals(witness.required(), resolution.independentAuthorizedContentMutationPaths(),
                        "exact authorized content targets"),
                () -> assertEquals(witness.required(), reachablePositivePaths(globalAllowed, intentRoles),
                        "global permission and positive role are conjunctive intent authority"),
                () -> assertEquals(witness.required(), reachablePositivePaths(trace.mutationAllowed(), traceRoles),
                        "global permission and positive role are conjunctive trace authority"),
                () -> assertEquals(witness.forbidden(), resolution.contract().forbiddenTargets(),
                        "exact contract forbidden targets"),
                () -> assertEquals(witness.forbidden(), Set.copyOf(trace.forbiddenTargets()),
                        "exact trace forbidden targets"),
                () -> assertEquals(witness.forbidden(), pathsWithRole(intentRoles, TargetRole.FORBIDDEN),
                        "exact intent FORBIDDEN roles"),
                () -> assertEquals(witness.forbidden(), pathsWithRole(traceRoles, TargetRole.FORBIDDEN),
                        "exact trace FORBIDDEN roles"),
                () -> assertEquals(reachablePositivePaths(globalAllowed, intentRoles),
                        reachablePositivePaths(trace.mutationAllowed(), traceRoles),
                        "trace must preserve reachable mutation authority"),
                () -> assertTrue(namedTargets.containsAll(intentRoles.keySet()),
                        "runtime subjects and events are not additional file targets"),
                () -> assertEquals(resolution.contract().mutationRequested(), trace.mutationRequested(),
                        "requested and permitted are distinct flags with consistent projections"),
                () -> assertEquals(resolution.contract().mutationRequested(), resolution.intent().mutationRequested()),
                () -> {
                    if (contentAuthorized) {
                        assertTrue(globalAllowed, "positive target authority requires global permission");
                        assertEquals(witness.required(), pathsWithRole(intentRoles, TargetRole.MUST_MUTATE),
                                "exact intent MUST_MUTATE roles");
                        assertEquals(witness.required(), pathsWithRole(traceRoles, TargetRole.MUST_MUTATE),
                                "exact trace MUST_MUTATE roles");
                        assertTrue(resolution.contract().mutationRequested(), "a positive must remain an edit request");
                        assertEquals(witness.required(), resolution.contract().expectedTargets(),
                                "only unconditional mutations drive expected mutation scope");
                        assertEquals(witness.required(), Set.copyOf(trace.expectedTargets()));
                    }
                },
                () -> {
                    for (String path : witness.denied()) {
                        assertFalse(reachablePositivePaths(globalAllowed, intentRoles).contains(path),
                                path + " has no reachable intent authority");
                        assertFalse(reachablePositivePaths(trace.mutationAllowed(), traceRoles).contains(path),
                                path + " has no reachable trace authority");
                    }
                    for (String path : witness.forbidden()) {
                        assertFalse(resolution.contract().expectedTargets().contains(path),
                                path + " must not drive expected progress");
                    }
                });
    }

    private static Set<String> pathsWithRole(Map<String, TargetRole> roles, TargetRole role) {
        return roles.entrySet().stream().filter(entry -> entry.getValue() == role)
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> reachablePositivePaths(boolean allowed, Map<String, TargetRole> roles) {
        if (!allowed) return Set.of();
        return roles.entrySet().stream().filter(entry -> POSITIVE_ROLES.contains(entry.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    private static Witness allow(String id, String request, String path) {
        return new Witness(id, request, Set.of(path), Set.of(), Set.of());
    }

    private static Witness deny(String id, String request, String path) {
        return new Witness(id, request, Set.of(), Set.of(), Set.of(path));
    }

    private record Witness(String id, String request, Set<String> required,
                           Set<String> forbidden, Set<String> denied) {
        @Override
        public String toString() {
            return id;
        }
    }
}
