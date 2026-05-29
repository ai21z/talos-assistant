package dev.talos.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Bytecode-level enforcement of Talos package-direction invariants.
 *
 * <p>These rules mirror the regex-based {@code validateArchitectureBoundaries}
 * ratchet in {@code build.gradle.kts} (baselined via
 * {@code config/architecture-boundary-baseline.txt}). ArchUnit operates on
 * compiled bytecode, so it additionally catches dependencies the source scanner
 * cannot see from imports/fully-qualified names alone: method return and
 * parameter types, generic type arguments, field types, annotations, and thrown
 * exceptions.
 *
 * <p>If a rule here fails while the regex baseline is clean, that gap is a real
 * architecture finding, not a test defect.
 */
@AnalyzeClasses(
        packages = "dev.talos",
        importOptions = ImportOption.DoNotIncludeTests.class)
class LayeredArchitectureTest {

    private static final String APP = "dev.talos.app..";
    private static final String CLI = "dev.talos.cli..";
    private static final String CORE = "dev.talos.core..";
    private static final String ENGINE = "dev.talos.engine..";
    private static final String RUNTIME = "dev.talos.runtime..";
    private static final String SAFETY = "dev.talos.safety..";
    private static final String SPI = "dev.talos.spi..";
    private static final String TOOLS = "dev.talos.tools..";

    /** Mirrors build rule {@code runtime-core-no-cli}. */
    @ArchTest
    static final ArchRule runtime_and_core_must_not_depend_on_cli =
            noClasses().that().resideInAnyPackage(RUNTIME, CORE)
                    .should().dependOnClassesThat().resideInAPackage(CLI)
                    .because("the CLI is a top adapter layer; runtime and core must stay CLI/framework-neutral");

    /** Mirrors build rule {@code core-no-runtime}. */
    @ArchTest
    static final ArchRule core_must_not_depend_on_runtime =
            noClasses().that().resideInAPackage(CORE)
                    .should().dependOnClassesThat().resideInAPackage(RUNTIME)
                    .because("core is a lower layer than the runtime orchestration layer");

    /** Mirrors build rule {@code tools-no-runtime}. */
    @ArchTest
    static final ArchRule tools_must_not_depend_on_runtime =
            noClasses().that().resideInAPackage(TOOLS)
                    .should().dependOnClassesThat().resideInAPackage(RUNTIME)
                    .because("tools are invoked by the runtime, not the other way around");

    /** Mirrors build rule {@code engine-no-runtime}. */
    @ArchTest
    static final ArchRule engine_must_not_depend_on_runtime =
            noClasses().that().resideInAPackage(ENGINE)
                    .should().dependOnClassesThat().resideInAPackage(RUNTIME)
                    .because("the engine layer must not couple back to runtime orchestration");

    /** Mirrors build rule {@code safety-no-talos-layers}. */
    @ArchTest
    static final ArchRule safety_must_not_depend_on_other_talos_layers =
            noClasses().that().resideInAPackage(SAFETY)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(APP, CLI, CORE, ENGINE, RUNTIME, SPI, TOOLS)
                    .because("safety is the lowest trust layer and must depend on no other Talos layer");

    /** Mirrors build rule {@code spi-no-upper-layers}. */
    @ArchTest
    static final ArchRule spi_must_not_depend_on_upper_layers =
            noClasses().that().resideInAPackage(SPI)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(CLI, CORE, RUNTIME, TOOLS)
                    .because("the SPI seam must not depend on the layers that implement against it");
}
