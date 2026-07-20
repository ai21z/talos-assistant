package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Complete stable-link preflight for the Windows qualification PTY process boundary. */
final class QualificationPtyFilesystemBoundary {
    private QualificationPtyFilesystemBoundary() {}

    @FunctionalInterface
    interface CheckedAction {
        void run() throws Exception;
    }

    record Plan(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path packetRoot,
            List<Path> requiredPacketFiles,
            List<Path> requiredPacketDirectories,
            List<Path> packetOutputTargets,
            NoFollowPathPolicy.GuardedRoot runFiles,
            Path runRoot,
            List<Path> requiredRunFiles,
            List<Path> runOutputTargets,
            NoFollowPathPolicy.GuardedRoot workspaceFiles,
            Path workspace,
            List<Path> absoluteInputFiles,
            Path installedRoot,
            Path installedLauncher
    ) {
        Plan {
            packetFiles = Objects.requireNonNull(packetFiles, "packet files");
            packetRoot = Objects.requireNonNull(packetRoot, "packet root");
            requiredPacketFiles = List.copyOf(requiredPacketFiles);
            requiredPacketDirectories = List.copyOf(requiredPacketDirectories);
            packetOutputTargets = List.copyOf(packetOutputTargets);
            runFiles = Objects.requireNonNull(runFiles, "run files");
            runRoot = Objects.requireNonNull(runRoot, "run root");
            requiredRunFiles = List.copyOf(requiredRunFiles);
            runOutputTargets = List.copyOf(runOutputTargets);
            workspaceFiles = Objects.requireNonNull(workspaceFiles, "workspace files");
            workspace = Objects.requireNonNull(workspace, "workspace");
            absoluteInputFiles = List.copyOf(absoluteInputFiles);
            installedRoot = Objects.requireNonNull(installedRoot, "installed root");
            installedLauncher = Objects.requireNonNull(
                    installedLauncher, "installed launcher");
        }
    }

    static void validate(Plan plan) throws Exception {
        Objects.requireNonNull(plan, "PTY filesystem plan");
        for (Path file : plan.requiredPacketFiles()) {
            plan.packetFiles().requireRegularFile(file);
        }
        for (Path directory : plan.requiredPacketDirectories()) {
            plan.packetFiles().requireDirectory(directory);
        }
        for (Path target : plan.packetOutputTargets()) {
            plan.packetFiles().requireRegularFileTarget(target);
        }
        for (Path file : plan.requiredRunFiles()) {
            plan.runFiles().requireRegularFile(file);
        }
        for (Path target : plan.runOutputTargets()) {
            plan.runFiles().requireRegularFileTarget(target);
        }
        plan.runFiles().requireDirectory(plan.runRoot());
        plan.workspaceFiles().requireDirectory(plan.workspace());

        // These are immutable qualification inputs. A stable linked descendant
        // anywhere in the active packet/run/workspace invalidates the launch.
        plan.packetFiles().walk(plan.packetRoot());
        plan.runFiles().walk(plan.runRoot());
        plan.workspaceFiles().walk(plan.workspace());

        for (Path file : plan.absoluteInputFiles()) {
            NoFollowPathPolicy.requireUnlinkedRegularFile(file);
        }

        NoFollowPathPolicy.GuardedRoot installedFiles =
                NoFollowPathPolicy.bindExistingDirectory(plan.installedRoot());
        installedFiles.requireRegularFile(plan.installedLauncher());
        Path installedLibrary = installedFiles.requireDirectory(
                plan.installedRoot().resolve("lib"));
        installedFiles.walk(installedLibrary);
    }

    static void validateThenStart(
            Plan plan,
            CheckedAction startMonitor,
            CheckedAction startTalos) throws Exception {
        Objects.requireNonNull(startMonitor, "monitor start action");
        Objects.requireNonNull(startTalos, "Talos start action");
        validate(plan);
        startMonitor.run();
        startTalos.run();
    }
}
