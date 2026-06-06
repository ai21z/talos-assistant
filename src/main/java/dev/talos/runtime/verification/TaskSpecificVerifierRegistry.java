package dev.talos.runtime.verification;

import dev.talos.runtime.capability.CapabilityProfile;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.capability.VerifierProfile;
import dev.talos.runtime.task.TaskContract;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TaskSpecificVerifierRegistry {
    private static final List<Lane> LANES = List.of(
            new SourceDerivedLane(),
            new StaticWebLane());

    private TaskSpecificVerifierRegistry() {}

    static Result verify(
            Path root,
            TaskContract contract,
            CapabilityProfile profile,
            Set<String> mutatedPaths,
            List<String> facts,
            List<String> problems,
            Map<String, String> readFileBodies
    ) {
        VerifierProfile verifierProfile = profile == null ? VerifierProfile.NONE : profile.verifierProfile();
        Context context = new Context(root, contract, profile, mutatedPaths, facts, problems, readFileBodies);
        for (Lane lane : LANES) {
            if (lane.supports(verifierProfile)) return lane.verify(context);
        }
        return Result.none();
    }

    record Result(
            boolean webCoherenceRequired,
            SourceDerivedArtifactVerifier.Result sourceDerivedVerification,
            VerificationReport report
    ) {
        Result {
            sourceDerivedVerification = sourceDerivedVerification == null
                    ? SourceDerivedArtifactVerifier.Result.notRequired()
                    : sourceDerivedVerification;
            report = report == null ? VerificationReport.empty() : report;
        }

        static Result none() {
            return new Result(
                    false,
                    SourceDerivedArtifactVerifier.Result.notRequired(),
                    VerificationReport.empty());
        }
    }

    private record Context(
            Path root,
            TaskContract contract,
            CapabilityProfile profile,
            Set<String> mutatedPaths,
            List<String> facts,
            List<String> problems,
            Map<String, String> readFileBodies
    ) {}

    private interface Lane {
        boolean supports(VerifierProfile profile);

        Result verify(Context context);
    }

    private static final class SourceDerivedLane implements Lane {
        @Override
        public boolean supports(VerifierProfile profile) {
            return profile == VerifierProfile.SOURCE_DERIVED;
        }

        @Override
        public Result verify(Context context) {
            SourceDerivedArtifactVerifier.Result result =
                    SourceDerivedArtifactVerifier.verify(context.contract(), context.root());
            context.facts().addAll(result.facts());
            context.problems().addAll(result.problems());
            return new Result(false, result, result.report());
        }
    }

    private static final class StaticWebLane implements Lane {
        @Override
        public boolean supports(VerifierProfile profile) {
            return profile == VerifierProfile.STATIC_WEB;
        }

        @Override
        public Result verify(Context context) {
            String profileFact = StaticWebCapabilityProfile.profileFact(context.profile());
            if (!profileFact.isBlank()) context.facts().add(profileFact);
            if (StaticWebCapabilityProfile.requiresSeparateAssetMutations(context.profile())) {
                StaticTaskVerifier.verifyPrimaryWebMutationCoverage(
                        context.mutatedPaths(),
                        context.facts(),
                        context.problems());
            }
            VerificationReport report = StaticTaskVerifier.verifySmallWebWorkspace(
                    context.root(),
                    context.contract(),
                    context.profile(),
                    context.mutatedPaths(),
                    context.facts(),
                    context.problems(),
                    context.readFileBodies());
            return new Result(true, SourceDerivedArtifactVerifier.Result.notRequired(), report);
        }
    }
}
