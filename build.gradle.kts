import java.io.File
import java.security.MessageDigest

plugins {
    application
    jacoco
}

val talosReportsDir = layout.buildDirectory.dir("reports/talos")
val qodanaCommunityImage = "jetbrains/qodana-jvm-community:2026.1"
val qodanaDockerCacheVolume = "talos-qodana-cache"
val qodanaDockerGradleVolume = "talos-qodana-gradle-cache"

/**
 * Wall-clock ISO timestamp. Used ONLY for jar manifest Implementation-Vendor.
 * Deliberately NOT used inside coverage/qodana/e2e summary JSON payloads.
 * Version summary is the exception because it records invocation-local jar task
 * state and therefore is intentionally not byte-reproducible across runs.
 */
fun generatedAtIso(): String = Class.forName("java.time.Instant").getMethod("now").invoke(null).toString()

/**
 * Writes a summary payload or, if payload construction throws, a fail-soft
 * fallback JSON that records the error.
 *
 * This preserves the "candidate packet exists even when evidence is malformed"
 * guarantee. A malformed upstream file (truncated SARIF, corrupt JUnit XML,
 * etc.) must not wipe the whole packet — it must produce an explicit
 * "summary-generation-failed" artifact for the reviewer.
 */
fun writeSummarySoft(target: java.io.File, summaryName: String, version: String, payloadBuilder: () -> Any) {
    val payload = try {
        payloadBuilder()
    } catch (t: Throwable) {
        mapOf(
            "summaryStatus" to "summary-generation-failed",
            "summaryName" to summaryName,
            "version" to version,
            "errorClass" to t.javaClass.name,
            "errorMessage" to (t.message ?: "")
        )
    }
    writeJson(target, payload)
}

fun epochMsToIso(epochMs: Long?): String? {
    if (epochMs == null) return null
    val instantClass = Class.forName("java.time.Instant")
    val ofEpochMilli = instantClass.getMethod("ofEpochMilli", Long::class.javaPrimitiveType)
    return ofEpochMilli.invoke(null, epochMs).toString()
}

fun percent(covered: Long, missed: Long): Double? {
    val total = covered + missed
    if (total <= 0L) return null
    return Math.round(covered * 10000.0 / total).toDouble() / 100.0
}

fun reportDateStamp(): String {
    val date = Class.forName("java.time.LocalDate").getMethod("now").invoke(null)
    val formatterClass = Class.forName("java.time.format.DateTimeFormatter")
    val formatter = formatterClass.getMethod("ofPattern", String::class.java).invoke(null, "ddMMyyyy")
    return date.javaClass.getMethod("format", formatterClass).invoke(date, formatter).toString()
}

fun reportIsoDate(): String {
    return Class.forName("java.time.LocalDate").getMethod("now").invoke(null).toString()
}

fun reportVersionStamp(version: String): String {
    return version.filter { it.isDigit() }.ifBlank { version.replace(Regex("[^A-Za-z0-9]"), "") }
}

fun mdPercent(value: Any?): String {
    return when (value) {
        is Number -> "%.2f%%".format(value.toDouble())
        null -> "n/a"
        else -> "$value"
    }
}

fun mdInt(value: Any?): Int {
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        else -> 0
    }
}

fun mdMap(value: Any?): Map<*, *> {
    return value as? Map<*, *> ?: emptyMap<String, Any?>()
}

fun mdList(value: Any?): List<*> {
    return value as? List<*> ?: emptyList<Any?>()
}

fun mdBar(value: Int, max: Int, width: Int = 40): String {
    if (max <= 0) return ".".repeat(width)
    val filled = Math.round(value.toDouble() * width / max.toDouble()).toInt().coerceIn(0, width)
    return "#".repeat(filled) + ".".repeat(width - filled)
}

fun mdSafe(value: Any?): String {
    return value?.toString() ?: "n/a"
}

fun mdBoxLine(text: String): String {
    return "| " + text.take(60).padEnd(60) + " |"
}

fun writeJson(target: java.io.File, payload: Any) {
    target.parentFile.mkdirs()
    target.writeText(
        groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(payload)) + "\n",
        Charsets.UTF_8
    )
}

fun parseXml(file: java.io.File): org.w3c.dom.Document {
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    return factory.newDocumentBuilder().parse(file)
}

fun elements(parent: org.w3c.dom.Element, tagName: String): List<org.w3c.dom.Element> {
    val nodes = parent.getElementsByTagName(tagName)
    val out = mutableListOf<org.w3c.dom.Element>()
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node is org.w3c.dom.Element) out += node
    }
    return out
}

fun extractJsonScenarioResource(testCaseName: String): String? {
    if (testCaseName.isBlank()) return null
    val prefix = "[json-scenario:"
    if (!testCaseName.startsWith(prefix)) return null
    val end = testCaseName.indexOf(']')
    if (end <= prefix.length) return null
    return testCaseName.substring(prefix.length, end)
}

fun gitOutput(vararg args: String): String? {
    return try {
        val output = providers.exec {
            commandLine("git", *args)
        }.standardOutput.asText.get().trim()
        output.ifBlank { null }
    } catch (_: Exception) {
        null
    }
}

/* ---------- Compile / test flags ---------- */

// Always compile as UTF-8 and show useful warnings
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
}

// Tests: JUnit Platform + Vector API for Lucene ANN perf
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--add-modules", "jdk.incubator.vector")
    extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class) {
        excludes = listOf(
            "org.htmlunit.*",
            "org.htmlunit.cssparser.*"
        )
    }
}

/* ---------- Java toolchain ---------- */

java {
    toolchain {
        val ver = (project.findProperty("javaVersion") ?: "21").toString().toInt()
        languageVersion.set(JavaLanguageVersion.of(ver))
    }
}

version = providers.gradleProperty("talosVersion").orNull
    ?: throw GradleException("Missing required gradle property: talosVersion")

fun validateReleaseLedgerText(changelogText: String, expectedVersion: String) {
    val normalized = changelogText.replace("\r\n", "\n").replace("\r", "\n")
    if (normalized.contains("pending release notes")) {
        throw GradleException("CHANGELOG.md contains placeholder text: pending release notes")
    }

    val headings = Regex("(?m)^## \\[([^\\]]+)](?: - (\\d{4}-\\d{2}-\\d{2}))?\\s*$")
        .findAll(normalized)
        .toList()
    if (headings.isEmpty() || headings.first().groupValues[1] != "Unreleased") {
        throw GradleException("CHANGELOG.md must contain a top-level ## [Unreleased] section before released versions")
    }

    val topReleased = headings.firstOrNull { it.groupValues[1] != "Unreleased" }
        ?: throw GradleException("CHANGELOG.md must contain at least one released version section")
    val topReleasedVersion = topReleased.groupValues[1]
    val topReleasedDate = topReleased.groupValues[2]
    if (topReleasedDate.isBlank()) {
        throw GradleException("Top released CHANGELOG.md version $topReleasedVersion must include an ISO release date")
    }
    if (topReleasedVersion != expectedVersion) {
        throw GradleException("Top released CHANGELOG.md version $topReleasedVersion does not match talosVersion $expectedVersion")
    }
}

data class ArchitectureBoundaryRule(
    val id: String,
    val sourcePrefixes: List<String>,
    val forbiddenReferencePrefixes: List<String>
)

data class ArchitectureBoundaryViolation(
    val rule: String,
    val path: String,
    val referencedSymbol: String
) {
    fun key(): String = "$rule|$path|$referencedSymbol"
}

val architectureBoundaryRules = listOf(
    ArchitectureBoundaryRule(
        id = "runtime-core-no-cli",
        sourcePrefixes = listOf(
            "src/main/java/dev/talos/runtime/",
            "src/main/java/dev/talos/core/"
        ),
        forbiddenReferencePrefixes = listOf("dev.talos.cli.")
    ),
    ArchitectureBoundaryRule(
        id = "core-no-runtime",
        sourcePrefixes = listOf("src/main/java/dev/talos/core/"),
        forbiddenReferencePrefixes = listOf("dev.talos.runtime.")
    ),
    ArchitectureBoundaryRule(
        id = "tools-no-runtime",
        sourcePrefixes = listOf("src/main/java/dev/talos/tools/"),
        forbiddenReferencePrefixes = listOf("dev.talos.runtime.")
    ),
    ArchitectureBoundaryRule(
        id = "engine-no-runtime",
        sourcePrefixes = listOf("src/main/java/dev/talos/engine/"),
        forbiddenReferencePrefixes = listOf("dev.talos.runtime.")
    ),
    ArchitectureBoundaryRule(
        id = "safety-no-talos-layers",
        sourcePrefixes = listOf("src/main/java/dev/talos/safety/"),
        forbiddenReferencePrefixes = listOf(
            "dev.talos.app.",
            "dev.talos.cli.",
            "dev.talos.core.",
            "dev.talos.engine.",
            "dev.talos.runtime.",
            "dev.talos.spi.",
            "dev.talos.tools."
        )
    ),
    ArchitectureBoundaryRule(
        id = "spi-no-upper-layers",
        sourcePrefixes = listOf("src/main/java/dev/talos/spi/"),
        forbiddenReferencePrefixes = listOf(
            "dev.talos.cli.",
            "dev.talos.core.",
            "dev.talos.runtime.",
            "dev.talos.tools."
        )
    )
)

fun readArchitectureBoundaryBaseline(file: java.io.File): Set<String> {
    if (!file.isFile) return emptySet()
    return file.readLines(Charsets.UTF_8)
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .toSortedSet()
}

fun stripJavaCommentsAndLiterals(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    var state = "code"
    while (i < source.length) {
        val ch = source[i]
        val next = source.getOrNull(i + 1)
        when (state) {
            "code" -> when {
                ch == '/' && next == '/' -> {
                    out.append("  ")
                    i += 2
                    state = "lineComment"
                }
                ch == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    state = "blockComment"
                }
                ch == '"' && source.getOrNull(i + 1) == '"' && source.getOrNull(i + 2) == '"' -> {
                    out.append("   ")
                    i += 3
                    state = "textBlock"
                }
                ch == '"' -> {
                    out.append(' ')
                    i++
                    state = "string"
                }
                ch == '\'' -> {
                    out.append(' ')
                    i++
                    state = "char"
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
            "lineComment" -> {
                out.append(if (ch == '\n' || ch == '\r') ch else ' ')
                i++
                if (ch == '\n' || ch == '\r') state = "code"
            }
            "blockComment" -> {
                if (ch == '*' && next == '/') {
                    out.append("  ")
                    i += 2
                    state = "code"
                } else {
                    out.append(if (ch == '\n' || ch == '\r') ch else ' ')
                    i++
                }
            }
            "textBlock" -> {
                if (ch == '"' && next == '"' && source.getOrNull(i + 2) == '"'
                    && !hasOddBackslashRunBefore(source, i)) {
                    out.append("   ")
                    i += 3
                    state = "code"
                } else {
                    out.append(if (ch == '\n' || ch == '\r') ch else ' ')
                    i++
                }
            }
            "string" -> {
                if (ch == '\\' && next != null) {
                    out.append("  ")
                    i += 2
                } else {
                    out.append(if (ch == '\n' || ch == '\r') ch else ' ')
                    i++
                    if (ch == '"') state = "code"
                }
            }
            "char" -> {
                if (ch == '\\' && next != null) {
                    out.append("  ")
                    i += 2
                } else {
                    out.append(if (ch == '\n' || ch == '\r') ch else ' ')
                    i++
                    if (ch == '\'') state = "code"
                }
            }
        }
    }
    return out.toString()
}

fun hasOddBackslashRunBefore(source: String, index: Int): Boolean {
    var count = 0
    var cursor = index - 1
    while (cursor >= 0 && source[cursor] == '\\') {
        count++
        cursor--
    }
    return count % 2 == 1
}

fun normalizeJavaTypeReference(candidate: String): String? {
    val parts = candidate.split('.')
    if (parts.size < 4 || parts[0] != "dev" || parts[1] != "talos") return null
    val typeIndex = parts.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
    if (typeIndex < 0) return null
    return parts.take(typeIndex + 1).joinToString(".")
}

fun normalizeJavaImportReference(candidate: String): String? {
    if (candidate.endsWith(".*")) {
        val owner = candidate.removeSuffix(".*")
        if (owner.substringAfterLast('.').firstOrNull()?.isUpperCase() == true) {
            return normalizeJavaTypeReference(owner)
        }
        return candidate
    }
    return normalizeJavaTypeReference(candidate)
}

fun forbiddenSourceReferences(source: String, importPattern: Regex, referencePattern: Regex): Set<String> {
    val stripped = stripJavaCommentsAndLiterals(source)
    val imports = stripped.lineSequence()
        .mapNotNull { importPattern.matchEntire(it)?.groupValues?.get(1) }
        .mapNotNull { normalizeJavaImportReference(it) }
    val fullyQualifiedReferences = referencePattern.findAll(stripped)
        .mapNotNull { normalizeJavaTypeReference(it.value) }
    return (imports + fullyQualifiedReferences).toSortedSet()
}

fun scanArchitectureBoundaryViolations(projectRoot: java.io.File): List<ArchitectureBoundaryViolation> {
    val sourceRoot = projectRoot.resolve("src/main/java")
    if (!sourceRoot.isDirectory) return emptyList()
    val importPattern = Regex("^\\s*import\\s+(?:static\\s+)?(dev\\.talos\\.[A-Za-z0-9_.*]+)\\s*;\\s*(?://.*)?$")
    val referencePattern = Regex("\\bdev\\.talos(?:\\.[A-Za-z_][A-Za-z0-9_]*)+\\b")
    return sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .flatMap { file ->
            val relativePath = projectRoot.toPath().relativize(file.toPath()).toString()
                .replace(File.separatorChar, '/')
            val matchingRules = architectureBoundaryRules.filter { rule ->
                rule.sourcePrefixes.any { relativePath.startsWith(it) }
            }
            if (matchingRules.isEmpty()) {
                emptySequence()
            } else {
                forbiddenSourceReferences(file.readText(Charsets.UTF_8), importPattern, referencePattern)
                    .asSequence()
                    .flatMap { referencedSymbol ->
                        matchingRules.asSequence()
                            .filter { rule ->
                                rule.forbiddenReferencePrefixes.any { referencedSymbol.startsWith(it) }
                            }
                            .map { rule ->
                                ArchitectureBoundaryViolation(rule.id, relativePath, referencedSymbol)
                            }
                    }
            }
        }
        .distinctBy { it.key() }
        .sortedWith(compareBy({ it.rule }, { it.path }, { it.referencedSymbol }))
        .toList()
}

val validateReleaseLedger by tasks.registering {
    description = "Validates changelog/version provenance for candidate evidence."
    group = "verification"
    val changelogFile = layout.projectDirectory.file("CHANGELOG.md")
    inputs.file(changelogFile)
    inputs.property("projectVersion", project.version.toString())

    doLast {
        val file = changelogFile.asFile
        if (!file.isFile) {
            throw GradleException("CHANGELOG.md not found at ${file.absolutePath}")
        }
        validateReleaseLedgerText(file.readText(Charsets.UTF_8), project.version.toString())
    }
}

tasks.named("check") {
    dependsOn(validateReleaseLedger)
}

val validateArchitectureBoundaries by tasks.registering {
    description = "Ratcheted architecture-boundary source-reference scanner for known package-direction debt."
    group = "verification"
    val sourceRoot = layout.projectDirectory.dir("src/main/java")
    val baselineFile = layout.projectDirectory.file("config/architecture-boundary-baseline.txt")
    val jsonReport = talosReportsDir.map { it.file("architecture-boundaries.json") }
    val markdownReport = talosReportsDir.map { it.file("architecture-boundaries.md") }
    inputs.dir(sourceRoot)
    if (baselineFile.asFile.exists()) {
        inputs.file(baselineFile)
    } else {
        inputs.property("architectureBoundaryBaseline", "<missing>")
    }
    outputs.file(jsonReport)
    outputs.file(markdownReport)

    doLast {
        val violations = scanArchitectureBoundaryViolations(projectDir)
        val actualKeys = violations.map { it.key() }.toSortedSet()
        val baselineKeys = readArchitectureBoundaryBaseline(baselineFile.asFile)
        val newViolations = (actualKeys - baselineKeys).toSortedSet()
        val staleBaseline = (baselineKeys - actualKeys).toSortedSet()

        writeJson(
            jsonReport.get().asFile,
            mapOf(
                "summaryStatus" to if (newViolations.isEmpty() && staleBaseline.isEmpty()) {
                    "architecture-boundary-baseline-current"
                } else {
                    "architecture-boundary-baseline-drift"
                },
                "violationCount" to actualKeys.size,
                "baselineCount" to baselineKeys.size,
                "newViolationCount" to newViolations.size,
                "staleBaselineCount" to staleBaseline.size,
                "rules" to architectureBoundaryRules.map {
                    mapOf(
                        "id" to it.id,
                        "sourcePrefixes" to it.sourcePrefixes,
                        "forbiddenReferencePrefixes" to it.forbiddenReferencePrefixes
                    )
                },
                "violations" to violations.map {
                    mapOf(
                        "rule" to it.rule,
                        "path" to it.path,
                        "referencedSymbol" to it.referencedSymbol,
                        "key" to it.key()
                    )
                },
                "newViolations" to newViolations,
                "staleBaseline" to staleBaseline
            )
        )

        val markdown = buildString {
            appendLine("# Architecture Boundary Report")
            appendLine()
            appendLine("| Metric | Count |")
            appendLine("|---|---:|")
            appendLine("| Current forbidden references | ${actualKeys.size} |")
            appendLine("| Baselined forbidden references | ${baselineKeys.size} |")
            appendLine("| New forbidden references | ${newViolations.size} |")
            appendLine("| Stale baseline entries | ${staleBaseline.size} |")
            appendLine()
            appendLine("## Rules")
            appendLine()
            architectureBoundaryRules.forEach { rule ->
                appendLine("- `${rule.id}`: `${rule.sourcePrefixes.joinToString("`, `")}` must not reference `${rule.forbiddenReferencePrefixes.joinToString("`, `")}`")
            }
            appendLine()
            appendLine("## Current Violations")
            appendLine()
            if (actualKeys.isEmpty()) {
                appendLine("None.")
            } else {
                actualKeys.forEach { appendLine("- `$it`") }
            }
            appendLine()
            appendLine("## New Violations")
            appendLine()
            if (newViolations.isEmpty()) {
                appendLine("None.")
            } else {
                newViolations.forEach { appendLine("- `$it`") }
            }
            appendLine()
            appendLine("## Stale Baseline Entries")
            appendLine()
            if (staleBaseline.isEmpty()) {
                appendLine("None.")
            } else {
                staleBaseline.forEach { appendLine("- `$it`") }
            }
        }
        markdownReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(markdown, Charsets.UTF_8)
        }

        if (newViolations.isNotEmpty() || staleBaseline.isNotEmpty()) {
            val message = buildString {
                if (newViolations.isNotEmpty()) {
                    appendLine("New architecture boundary violations detected: ${newViolations.size}")
                    newViolations.take(20).forEach { appendLine(it) }
                    if (newViolations.size > 20) appendLine("... ${newViolations.size - 20} more")
                }
                if (staleBaseline.isNotEmpty()) {
                    appendLine("Stale architecture boundary baseline entries detected: ${staleBaseline.size}")
                    staleBaseline.take(20).forEach { appendLine(it) }
                    if (staleBaseline.size > 20) appendLine("... ${staleBaseline.size - 20} more")
                }
                appendLine("Update config/architecture-boundary-baseline.txt only when intentionally accepting current debt.")
            }.trim()
            throw GradleException(message)
        }
    }
}

tasks.named("check") {
    dependsOn(validateArchitectureBoundaries)
}

/* ---------- Repositories ---------- */

repositories {
    mavenCentral()
}

/* ---------- Dependencies ---------- */

dependencies {
    implementation("info.picocli:picocli:${project.property("picocliVersion")}")
    annotationProcessor("info.picocli:picocli-codegen:${project.property("picocliVersion")}")

    // Lucene 10
    implementation("org.apache.lucene:lucene-core:${project.property("luceneVersion")}")
    implementation("org.apache.lucene:lucene-analysis-common:${project.property("luceneVersion")}")
    implementation("org.apache.lucene:lucene-queryparser:${project.property("luceneVersion")}")

    // Config / Storage / Logging
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:${project.property("jacksonVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-annotations:${project.property("jacksonVersion")}")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${project.property("jacksonVersion")}")
    implementation("org.slf4j:slf4j-api:${project.property("slf4jVersion")}")
    runtimeOnly("ch.qos.logback:logback-classic:${project.property("logbackVersion")}")
    runtimeOnly("org.apache.logging.log4j:log4j-to-slf4j:${project.property("log4jVersion")}")

    // Local document extraction: narrow adapters, not broad recursive parsing.
    implementation("org.apache.pdfbox:pdfbox:${project.property("pdfboxVersion")}")
    implementation("org.apache.poi:poi-ooxml:${project.property("poiVersion")}")

    // Local static-web behavior verification: in-process, workspace-local page execution only.
    implementation("org.htmlunit:htmlunit:${project.property("htmlUnitVersion")}")

    // REPL. 3.30.x (not 4.x: the JNA provider and parts of the 3.x API are
    // removed there). 3.30.12+ fixes the status-bar duplication on terminal
    // resize that affects the T779 status row. Any future bump is a
    // PTY-revalidation event: terminal provider internals shift bytes.
    implementation("org.jline:jline:3.30.13")

    // Unified diff for the approval window (T756): pure Java, zero transitive
    // runtime dependencies, Apache-2.0.
    implementation("io.github.java-diff-utils:java-diff-utils:${project.property("javaDiffUtilsVersion")}")

    // JUnit 5 (explicit engine to avoid Gradle 9 deprecation)
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ArchUnit: bytecode-level architecture boundary guards (complements the
    // regex-based validateArchitectureBoundaries ratchet in this build script).
    testImplementation("com.tngtech.archunit:archunit-junit5:${project.property("archunitVersion")}")
}

/* ---------- Deterministic scripted E2E harness lane ---------- */

val e2eTestSourceSet = sourceSets.create("e2eTest") {
    compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

configurations[e2eTestSourceSet.implementationConfigurationName].extendsFrom(configurations["testImplementation"])
configurations[e2eTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations["testRuntimeOnly"])

val e2eTest by tasks.registering(Test::class) {
    description = "Runs the deterministic scripted end-to-end harness scenario suite."
    group = "verification"
    testClassesDirs = e2eTestSourceSet.output.classesDirs
    classpath = e2eTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
}

val candidateTest by tasks.registering(Test::class) {
    description = "Runs the candidate unit-test lane and preserves results even when tests fail."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    ignoreFailures = true
    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/candidateTest/binary"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/candidateTest"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/candidateTest"))
    shouldRunAfter(tasks.test)
}

tasks.test {
    filter {
        excludeTestsMatching("dev.talos.architecture.intelligence.*")
        excludeTestsMatching("dev.talos.wiki.WikiEvidenceLivenessTest")
    }
}

val cleanArchitectureIntelligenceReport by tasks.registering(Delete::class) {
    description = "Deletes generated architecture intelligence evidence before liveness validation."
    group = "reporting"
    delete(layout.buildDirectory.dir("reports/talos/architecture-intelligence/current"))
}

val architectureIntelligenceReport by tasks.registering(Test::class) {
    description = "Generates the report-only architecture intelligence Markdown and JSON suite for Wave 5 planning."
    group = "reporting"
    dependsOn("writeQodanaSummary")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    inputs.file(layout.projectDirectory.file("gradle.properties"))
    inputs.file(layout.buildDirectory.file("reports/talos/qodana-summary.json"))
    inputs.property("gitHead", providers.provider { gitOutput("rev-parse", "HEAD") ?: "unknown" })
    inputs.property("gitBranch", providers.provider { gitOutput("rev-parse", "--abbrev-ref", "HEAD") ?: "unknown" })
    filter {
        includeTestsMatching("dev.talos.architecture.intelligence.*")
    }
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/architectureIntelligenceReport"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/architectureIntelligenceReport"))
    outputs.dir(layout.buildDirectory.dir("reports/talos/architecture-intelligence/current"))
    mustRunAfter(cleanArchitectureIntelligenceReport)
    shouldRunAfter(tasks.test)
}

val wikiLintStructural by tasks.registering(Test::class) {
    description = "Runs the structural lint for the committed Talos living evidence wiki."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    inputs.dir(layout.projectDirectory.dir("work-cycle-docs/wiki"))
    filter {
        includeTestsMatching("dev.talos.wiki.WikiLintStructuralTest")
    }
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/wikiLintStructural"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/wikiLintStructural"))
    shouldRunAfter(tasks.test)
}

val wikiLintWithEvidence by tasks.registering(Test::class) {
    description = "Runs living evidence wiki lint against generated Talos architecture report JSON."
    group = "verification"
    dependsOn(cleanArchitectureIntelligenceReport, architectureIntelligenceReport, wikiLintStructural)
    mustRunAfter(architectureIntelligenceReport, wikiLintStructural)
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    inputs.dir(layout.projectDirectory.dir("work-cycle-docs/wiki"))
    inputs.dir(layout.buildDirectory.dir("reports/talos/architecture-intelligence/current/data"))
    outputs.file(layout.buildDirectory.file("reports/talos/wiki-lint/current/identity-freshness.json"))
    filter {
        includeTestsMatching("dev.talos.wiki.WikiEvidenceLivenessTest")
    }
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/wikiLintWithEvidence"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/wikiLintWithEvidence"))
}

val wikiEvidenceCloseGate by tasks.registering {
    description = "Runs the living evidence wiki close/candidate gate with generated-report liveness."
    group = "verification"
    dependsOn(wikiLintWithEvidence)
}

val candidateE2eTest by tasks.registering(Test::class) {
    description = "Runs the candidate deterministic scripted e2e harness lane and preserves results even when scenarios fail."
    group = "verification"
    testClassesDirs = e2eTestSourceSet.output.classesDirs
    classpath = e2eTestSourceSet.runtimeClasspath
    ignoreFailures = true
    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/candidateE2eTest/binary"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/candidateE2eTest"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/candidateE2eTest"))
    shouldRunAfter(candidateTest)
}

/* ---------- Application runtime flags ---------- */

application {
    mainClass.set("dev.talos.app.Main")
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        // T880: on Java 18+ System.out/err use stdout.encoding/stderr.encoding, which
        // default to the Windows console code page (e.g. cp1252) and replace the
        // interactive lane glyphs (bullet, arrow) with '?'. Pin them to UTF-8 so a
        // Unicode-capable terminal renders them; the ASCII fallback still covers
        // non-Unicode/redirected output.
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-XX:+UseZGC"
    )
}

/* ---------- Jar manifest attributes ---------- */

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to "Talos",
            "Implementation-Version" to project.version,
            "Main-Class" to "dev.talos.app.Main"
        )
    }
    doFirst {
        manifest.attributes(
            "Implementation-Vendor" to generatedAtIso()
        )
    }
}

/* ---------- Generated build metadata for exploded-class runs ---------- */

val generateBuildVersionResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/buildVersion")
    outputs.dir(outputDir)
    inputs.property("projectVersion", project.version.toString())

    doLast {
        val metaInfDir = outputDir.get().file("META-INF").asFile
        metaInfDir.mkdirs()
        val propsFile = metaInfDir.resolve("talos-version.properties")
        propsFile.writeText(
            "version=${project.version}\n",
            Charsets.UTF_8
        )
    }
}

tasks.processResources {
    from(generateBuildVersionResource)
}

/* ---------- Jar naming ---------- */

tasks.jar {
    archiveBaseName.set("talos")
    archiveVersion.set("") // stable name: talos.jar (referenced by installDist + jpackage)
}

/* ---------- Windows public beta release packaging ---------- */

val windowsReleaseDir = layout.buildDirectory.dir("release/windows")
val publicMsiArtifactName = "Talos-${version}-windows-x64.msi"
val publicAppZipArtifactName = "talos-${version}-windows-x64-app.zip"

fun appendJpackageResources(args: MutableList<String>) {
    val resDir = file("src/main/jpackage")
    if (resDir.exists()) {
        args.addAll(listOf("--resource-dir", resDir.absolutePath))
    }
    val iconFile = file("src/main/jpackage/icon.ico")
    if (iconFile.exists()) {
        args.addAll(listOf("--icon", iconFile.absolutePath))
    }
}

tasks.register<Exec>("jpackageApp") {
    dependsOn(tasks.installDist)

    // Resolve jpackage from JAVA_HOME if present; fall back to PATH
    val jpackageExe = providers.environmentVariable("JAVA_HOME")
        .map { file("$it/bin/jpackage.exe").absolutePath }
        .orElse("jpackage")

    val appDir   = layout.buildDirectory.dir("install/talos")
    val inputDir = appDir.map { it.dir("lib") }
    val destDir  = layout.buildDirectory.dir("dist")
    val appVer   = providers.provider { version.toString() }

    // Build command line at execution time to allow optional resources
    doFirst {
        val staleMsiFiles = destDir.get().asFile
            .listFiles { file -> file.isFile && file.name.endsWith(".msi", ignoreCase = true) }
            ?.toList()
            ?: emptyList()
        project.delete(staleMsiFiles)
        val args = mutableListOf(
            jpackageExe.get(),
            "--type", "msi",
            "--name", "Talos",
            "--app-version", appVer.get(),
            "--vendor", "Vissarion Zounarakis",
            "--dest", destDir.get().asFile.absolutePath,
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", "talos.jar",
            "--main-class", "dev.talos.app.Main",
            "--win-console",
            "--win-per-user-install",
            "--install-dir", "Talos"
        )
        // Keep launcher startup quiet; Lucene falls back when the optional
        // incubator Vector module is not enabled at application launch.

        appendJpackageResources(args)

        commandLine(args)
    }
}

tasks.register<Exec>("jpackageAppImage") {
    dependsOn(tasks.installDist)

    val jpackageExe = providers.environmentVariable("JAVA_HOME")
        .map { file("$it/bin/jpackage.exe").absolutePath }
        .orElse("jpackage")

    val appDir = layout.buildDirectory.dir("install/talos")
    val inputDir = appDir.map { it.dir("lib") }
    val destDir = layout.buildDirectory.dir("dist/windows-app-image")
    val appVer = providers.provider { version.toString() }

    doFirst {
        project.delete(destDir.get().dir("Talos"))
        val args = mutableListOf(
            jpackageExe.get(),
            "--type", "app-image",
            "--name", "Talos",
            "--app-version", appVer.get(),
            "--vendor", "Vissarion Zounarakis",
            "--dest", destDir.get().asFile.absolutePath,
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", "talos.jar",
            "--main-class", "dev.talos.app.Main",
            "--win-console"
        )
        appendJpackageResources(args)

        commandLine(args)
    }
}

tasks.register<Copy>("windowsReleaseMsi") {
    dependsOn("jpackageApp")
    from(layout.buildDirectory.dir("dist")) {
        include("*.msi")
        rename { publicMsiArtifactName }
    }
    into(windowsReleaseDir)
}

tasks.register<Zip>("windowsReleaseAppZip") {
    dependsOn("jpackageAppImage")
    from(layout.buildDirectory.dir("dist/windows-app-image"))
    destinationDirectory.set(windowsReleaseDir)
    archiveFileName.set(publicAppZipArtifactName)
}

tasks.register<Copy>("copyWindowsReleaseBootstrap") {
    from("tools/install-talos.ps1")
    into(windowsReleaseDir)
}

tasks.register("windowsReleaseChecksums") {
    dependsOn("windowsReleaseMsi", "windowsReleaseAppZip", "copyWindowsReleaseBootstrap")

    val checksumFile = windowsReleaseDir.map { it.file("checksums.txt") }
    outputs.file(checksumFile)

    doLast {
        val releaseDir = windowsReleaseDir.get().asFile
        releaseDir.mkdirs()

        fun sha256Hex(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        val artifactNames = listOf(
            publicMsiArtifactName,
            publicAppZipArtifactName,
            "install-talos.ps1"
        )
        val lines = artifactNames.map { name ->
            val artifact = releaseDir.resolve(name)
            if (!artifact.isFile) {
                throw GradleException("Missing Windows release artifact: ${artifact.absolutePath}")
            }
            "${sha256Hex(artifact)}  $name"
        }

        checksumFile.get().asFile.writeText(
            lines.joinToString(System.lineSeparator()) + System.lineSeparator(),
            Charsets.UTF_8
        )
    }
}

tasks.register("windowsReleaseArtifacts") {
    dependsOn("windowsReleaseChecksums")
    group = "distribution"
    description = "Builds Windows x64 public beta artifacts and checksums."
}

/* ---------- JaCoCo code coverage ---------- */

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)       // consumed by Sonar / CI
        html.required.set(true)      // human-readable local report
        csv.required.set(false)
    }
}

val candidateJacocoTestReport by tasks.registering(JacocoReport::class) {
    description = "Writes JaCoCo coverage for the candidate unit-test lane."
    group = "verification"
    dependsOn(candidateTest)
    executionData(layout.buildDirectory.file("jacoco/candidateTest.exec"))
    sourceSets(sourceSets["main"])
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/candidateTest/candidateJacocoTestReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/candidateTest/html"))
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        // T750: floors ratcheted to measured actuals minus ~2 points
        // (2026-06-11 test lane: INSTRUCTION 84.83%, BRANCH 64.78%). The old
        // single 0.65 INSTRUCTION rule could absorb a 20-point regression
        // silently and left BRANCH - the decision-heavy counter for a
        // fail-closed product - entirely ungated. Tighten as coverage grows;
        // never loosen without a ticket.
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.82".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.62".toBigDecimal()
            }
        }
        // Per-package floors for the packages where an untested branch
        // violates doctrine directly (measured 2026-06-11:
        // runtime.policy 89.58/69.58, safety 82.48/64.09, core.secret
        // 39.63/41.18 - the secret floor pins today's low coverage in place
        // until targeted tests raise it).
        rule {
            element = "PACKAGE"
            includes = listOf("dev.talos.runtime.policy")
            limit {
                counter = "INSTRUCTION"
                minimum = "0.87".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.67".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("dev.talos.safety")
            limit {
                counter = "INSTRUCTION"
                minimum = "0.80".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.62".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("dev.talos.core.secret")
            limit {
                counter = "INSTRUCTION"
                minimum = "0.37".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.39".toBigDecimal()
            }
        }
    }
}

val checkGeneratedArtifactCanaries by tasks.registering(JavaExec::class) {
    description = "Scans generated local verification reports for raw privacy canaries."
    group = "verification"
    dependsOn(tasks.test, e2eTest, tasks.jacocoTestReport)
    mainClass.set("dev.talos.runtime.policy.ArtifactCanaryScanCli")
    classpath = sourceSets["main"].runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        listOf(
            "--runtime",
            "--root", layout.buildDirectory.dir("reports").get().asFile.absolutePath,
            "--root", layout.buildDirectory.dir("test-results").get().asFile.absolutePath
        )
    })
}

// Hard local gate: unit tests, deterministic E2E tests, coverage baseline, and generated-artifact canary scan.
tasks.check {
    dependsOn(tasks.test, e2eTest, tasks.jacocoTestCoverageVerification, checkGeneratedArtifactCanaries)
}

tasks.register<JavaExec>("checkRuntimeArtifactCanaries") {
    description = "Scans targeted runtime/live-audit artifact directories for raw privacy canaries."
    group = "verification"
    dependsOn(tasks.classes)
    mainClass.set("dev.talos.runtime.policy.ArtifactCanaryScanCli")
    classpath = sourceSets["main"].runtimeClasspath
    doFirst {
        val roots = providers.gradleProperty("artifactScanRoots").orNull
        if (roots.isNullOrBlank()) {
            throw GradleException(
                "checkRuntimeArtifactCanaries requires -PartifactScanRoots=<dir[,dir...]> " +
                    "so old ignored manual-audit artifacts are not scanned accidentally."
            )
        }
    }
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val roots = providers.gradleProperty("artifactScanRoots")
            .orElse("")
            .get()
        val allowlist = providers.gradleProperty("artifactScanAllowlist")
            .orElse("")
            .get()
        val out = mutableListOf("--runtime")
        roots.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { out.addAll(listOf("--root", it)) }
        allowlist.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { out.addAll(listOf("--allow", it)) }
        out
    })
}

tasks.register<JavaExec>("writeRedactedAuditSnapshot") {
    description = "Writes a canary-safe redacted workspace snapshot for manual/live audit packets."
    group = "verification"
    dependsOn(tasks.classes)
    mainClass.set("dev.talos.runtime.policy.RedactedAuditSnapshotCli")
    classpath = sourceSets["main"].runtimeClasspath
    doFirst {
        val workspace = providers.gradleProperty("auditSnapshotWorkspace").orNull
        val output = providers.gradleProperty("auditSnapshotOutput").orNull
        if (workspace.isNullOrBlank() || output.isNullOrBlank()) {
            throw GradleException(
                "writeRedactedAuditSnapshot requires " +
                    "-PauditSnapshotWorkspace=<dir> -PauditSnapshotOutput=<dir> " +
                    "[-PauditSnapshotLabel=<name>]"
            )
        }
    }
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val workspace = providers.gradleProperty("auditSnapshotWorkspace")
            .orElse("")
            .get()
        val output = providers.gradleProperty("auditSnapshotOutput")
            .orElse("")
            .get()
        val label = providers.gradleProperty("auditSnapshotLabel")
            .orElse("snapshot")
            .get()
        listOf("--workspace", workspace, "--output", output, "--label", label)
    })
}

tasks.register<JavaExec>("runSynchronizedApprovalAudit") {
    description = "Runs the synchronized approval audit bank in scripted or live mode and writes reviewable artifacts."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.harness.SynchronizedApprovalAuditMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val out = mutableListOf<String>()
        val artifactsRoot = providers.gradleProperty("approvalAuditArtifactsRoot")
            .orElse("")
            .get()
        val workspacesRoot = providers.gradleProperty("approvalAuditWorkspacesRoot")
            .orElse("")
            .get()
        val mode = providers.gradleProperty("approvalAuditMode")
            .orElse("")
            .get()
        val config = providers.gradleProperty("approvalAuditConfig")
            .orElse("")
            .get()
        val model = providers.gradleProperty("approvalAuditModel")
            .orElse("")
            .get()
        val scenario = providers.gradleProperty("approvalAuditScenario")
            .orElse("")
            .get()
        if (mode.isNotBlank()) {
            out.addAll(listOf("--mode", mode))
        }
        if (artifactsRoot.isNotBlank()) {
            out.addAll(listOf("--artifacts", artifactsRoot))
        }
        if (workspacesRoot.isNotBlank()) {
            out.addAll(listOf("--workspaces", workspacesRoot))
        }
        if (config.isNotBlank()) {
            out.addAll(listOf("--config", config))
        }
        if (model.isNotBlank()) {
            out.addAll(listOf("--model", model))
        }
        if (scenario.isNotBlank()) {
            out.addAll(listOf("--scenario", scenario))
        }
        out
    })
}

tasks.register<JavaExec>("runSynchronizedApprovalCliSmoke") {
    description = "Runs a synchronized production CLI approval smoke against the installed Talos script."
    group = "verification"
    dependsOn("installDist", "e2eTestClasses")
    mainClass.set("dev.talos.harness.SynchronizedCliApprovalSmokeMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val out = mutableListOf<String>()
        val talos = providers.gradleProperty("cliSmokeTalosCommand")
            .orElse("")
            .get()
        val config = providers.gradleProperty("cliSmokeConfig")
            .orElse("")
            .get()
        val artifacts = providers.gradleProperty("cliSmokeArtifactsRoot")
            .orElse("")
            .get()
        val workspace = providers.gradleProperty("cliSmokeWorkspace")
            .orElse("")
            .get()
        val timeoutMs = providers.gradleProperty("cliSmokeTimeoutMs")
            .orElse("")
            .get()
        if (talos.isNotBlank()) {
            out.addAll(listOf("--talos", talos))
        }
        if (config.isNotBlank()) {
            out.addAll(listOf("--config", config))
        }
        if (artifacts.isNotBlank()) {
            out.addAll(listOf("--artifacts", artifacts))
        }
        if (workspace.isNotBlank()) {
            out.addAll(listOf("--workspace", workspace))
        }
        if (timeoutMs.isNotBlank()) {
            out.addAll(listOf("--timeout-ms", timeoutMs))
        }
        out
    })
}

tasks.register<JavaExec>("prepareSynchronizedApprovalPtyManualAudit") {
    description = "Prepares a manual true-PTY/JLine approval audit packet with fixture workspace and runbook."
    group = "verification"
    dependsOn("installDist", "e2eTestClasses")
    mainClass.set("dev.talos.harness.SynchronizedCliPtyManualAuditMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val out = mutableListOf<String>()
        val talos = providers.gradleProperty("ptyManualTalosCommand")
            .orElse("")
            .get()
        val config = providers.gradleProperty("ptyManualConfig")
            .orElse("")
            .get()
        val artifacts = providers.gradleProperty("ptyManualArtifactsRoot")
            .orElse("")
            .get()
        val workspace = providers.gradleProperty("ptyManualWorkspace")
            .orElse("")
            .get()
        if (talos.isNotBlank()) {
            out.addAll(listOf("--talos", talos))
        }
        if (config.isNotBlank()) {
            out.addAll(listOf("--config", config))
        }
        if (artifacts.isNotBlank()) {
            out.addAll(listOf("--artifacts", artifacts))
        }
        if (workspace.isNotBlank()) {
            out.addAll(listOf("--workspace", workspace))
        }
        out
    })
}

tasks.register<JavaExec>("validateSynchronizedApprovalPtyManualAudit") {
    description = "Validates completed manual true-PTY/JLine approval audit evidence without claiming automated PTY coverage."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.harness.SynchronizedCliPtyManualAuditValidator")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val out = mutableListOf<String>()
        val artifacts = providers.gradleProperty("ptyManualArtifactsRoot")
            .orElse("")
            .get()
        val workspace = providers.gradleProperty("ptyManualWorkspace")
            .orElse("")
            .get()
        if (artifacts.isNotBlank()) {
            out.addAll(listOf("--artifacts", artifacts))
        }
        if (workspace.isNotBlank()) {
            out.addAll(listOf("--workspace", workspace))
        }
        out
    })
}

tasks.register<Exec>("qodanaLocal") {
    description = "Runs optional local Qodana Community analysis using Docker with persistent Qodana/Gradle cache volumes."
    group = "verification"
    doFirst {
        file(".qodana").mkdirs()
    }
    commandLine(
        "docker",
        "run",
        "--rm",
        "-v",
        "${projectDir.absolutePath}:/data/project",
        "-v",
        "${projectDir.resolve(".qodana").absolutePath}:/data/results",
        "-v",
        "$qodanaDockerCacheVolume:/data/cache",
        "-v",
        "$qodanaDockerGradleVolume:/root/.gradle",
        qodanaCommunityImage
    )
}

tasks.register<Exec>("qodanaNativeLocal") {
    description = "Runs optional local Qodana Community analysis in native mode using Qodana CLI."
    group = "verification"
    commandLine(
        "qodana",
        "scan",
        "--linter",
        "qodana-jvm-community",
        "--within-docker",
        "false"
    )
}

tasks.register<Exec>("qodanaNativeFreshLocal") {
    description = "Deletes previous local Qodana outputs, then runs native Qodana into the summary-compatible report path."
    group = "verification"
    val qodanaRoot = projectDir.resolve(".qodana")
    val qodanaReportDir = qodanaRoot.resolve("report")
    val qodanaResultsDir = qodanaReportDir.resolve("results")
    doFirst {
        delete(
            qodanaReportDir,
            qodanaRoot.resolve("qodana.sarif.json"),
            qodanaRoot.resolve("qodana-short.sarif.json"),
            qodanaRoot.resolve("log")
        )
        qodanaResultsDir.mkdirs()
    }
    commandLine(
        "qodana",
        "scan",
        "--linter",
        "qodana-jvm-community",
        "--within-docker",
        "false",
        "--baseline",
        projectDir.resolve("qodana-baseline.sarif.json").absolutePath,
        "--results-dir",
        qodanaResultsDir.absolutePath,
        "--report-dir",
        qodanaReportDir.absolutePath
    )
}

tasks.register<Exec>("gitleaksLocal") {
    description = "Runs optional local secret scanning with the Gitleaks Docker image."
    group = "verification"
    commandLine(
        "docker",
        "run",
        "--rm",
        "-v",
        "${projectDir.absolutePath}:/repo",
        "ghcr.io/gitleaks/gitleaks:latest",
        "git",
        "-v",
        "/repo"
    )
}

tasks.register<Exec>("osvScannerLocal") {
    description = "Runs optional local dependency vulnerability scanning with OSV-Scanner if installed."
    group = "verification"
    commandLine("osv-scanner", "scan", "-r", projectDir.absolutePath)
}

tasks.register("optionalLocalQuality") {
    description = "Runs optional local quality/security tools. These are recommended, not part of the hard test gate."
    group = "verification"
    dependsOn("qodanaLocal", "gitleaksLocal", "osvScannerLocal")
}

/* ---------- Machine-readable quality summaries ---------- */

val writeVersionSummary by tasks.registering {
    description = "Writes build/reports/talos/version-summary.json"
    group = "reporting"
    dependsOn(tasks.jar)
    val outputFile = talosReportsDir.map { it.file("version-summary.json") }
    outputs.file(outputFile)
    // Required: output reflects jarTask.state observed at execution time,
    // which is not expressible as a declared Gradle input (it is per-invocation,
    // not per-source). Without this, Gradle would cache the first run's
    // "built-in-current-run" status and never refresh to "up-to-date-in-current-run"
    // on subsequent invocations.
    outputs.upToDateWhen { false }
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.property("projectVersion", project.version.toString())

    doLast {
        writeSummarySoft(outputFile.get().asFile, "version-summary", project.version.toString()) {
            val jarTask = tasks.jar.get()
            val jarFile = jarTask.archiveFile.get().asFile
            val jarExists = jarFile.exists()
            val jarLastModifiedEpochMs = if (jarExists) jarFile.lastModified() else null
            val jarBuiltAt = epochMsToIso(jarLastModifiedEpochMs)
            val jarTaskState = jarTask.state
            mapOf(
                "version" to project.version.toString(),
                "jarBuiltAt" to jarBuiltAt,
                "sourcePaths" to mapOf(
                    "jarArtifact" to jarFile.absolutePath
                ),
                "artifacts" to listOf(
                    mapOf(
                        "name" to tasks.jar.get().archiveFileName.get(),
                        "path" to jarFile.absolutePath,
                        "exists" to jarExists,
                        "lastModifiedEpochMs" to jarLastModifiedEpochMs,
                        "lastModifiedIso" to jarBuiltAt
                    )
                ),
                "jarTaskStateInCurrentInvocation" to mapOf(
                    "jarExists" to jarExists,
                    "jarLastModifiedEpochMs" to jarLastModifiedEpochMs,
                    "jarLastModifiedIso" to jarBuiltAt,
                    "jarTaskDidWork" to jarTaskState.didWork,
                    "jarTaskUpToDate" to jarTaskState.upToDate,
                    "jarTaskSkipped" to jarTaskState.skipped,
                    "status" to when {
                        !jarExists -> "jar-missing"
                        jarTaskState.didWork -> "built-in-current-run"
                        jarTaskState.upToDate -> "up-to-date-in-current-run"
                        else -> "present-but-task-state-unclear"
                    }
                )
            )
        }
    }
}

val writeCoverageSummary by tasks.registering {
    description = "Writes build/reports/talos/coverage-summary.json from JaCoCo XML and JUnit XML."
    group = "reporting"
    dependsOn(candidateJacocoTestReport)
    val outputFile = talosReportsDir.map { it.file("coverage-summary.json") }
    outputs.file(outputFile)
    val jacocoXmlProvider = layout.buildDirectory.file("reports/jacoco/candidateTest/candidateJacocoTestReport.xml")
    val testResultsDirProvider = layout.buildDirectory.dir("test-results/candidateTest")
    inputs.files(providers.provider {
        val jacocoXml = jacocoXmlProvider.get().asFile
        if (jacocoXml.exists()) files(jacocoXml) else files()
    })
    // Precise input: only TEST-*.xml files drive re-runs, not every neighbor
    // file (binary results, IDE temp, etc.).
    inputs.files(providers.provider {
        val dir = testResultsDirProvider.get().asFile
        if (dir.exists()) fileTree(dir) { include("TEST-*.xml") } else files()
    })
    inputs.property("projectVersion", project.version.toString())

    doLast {
        val jacocoXml = jacocoXmlProvider.get().asFile
        val testResultsDir = testResultsDirProvider.get().asFile
        writeSummarySoft(outputFile.get().asFile, "coverage-summary", project.version.toString()) {
            val jacocoXmlExists = jacocoXml.exists()

            var instructionCovered = 0L
            var instructionMissed = 0L
            var branchCovered = 0L
            var branchMissed = 0L
            var tests = 0
            var failures = 0
            var errors = 0
            var skipped = 0
            var xmlFilesRead = 0

            if (jacocoXmlExists) {
                val report = parseXml(jacocoXml).documentElement
                elements(report, "counter").forEach { node ->
                    when (node.getAttribute("type")) {
                        "INSTRUCTION" -> {
                            instructionCovered = node.getAttribute("covered").toLong()
                            instructionMissed = node.getAttribute("missed").toLong()
                        }
                        "BRANCH" -> {
                            branchCovered = node.getAttribute("covered").toLong()
                            branchMissed = node.getAttribute("missed").toLong()
                        }
                    }
                }
            }

            if (testResultsDir.exists()) {
                testResultsDir.listFiles { file -> file.isFile && file.name.startsWith("TEST-") && file.name.endsWith(".xml") }
                    ?.forEach { xml ->
                        xmlFilesRead++
                        val suite = parseXml(xml).documentElement
                        tests += suite.getAttribute("tests").toInt()
                        failures += suite.getAttribute("failures").toInt()
                        errors += suite.getAttribute("errors").toInt()
                        skipped += suite.getAttribute("skipped").toInt()
                    }
            }

            mapOf(
                "version" to project.version.toString(),
                "sourcePaths" to mapOf(
                    "jacocoXml" to jacocoXml.absolutePath,
                    "testResultsDir" to testResultsDir.absolutePath
                ),
                "coverageDataStatus" to if (jacocoXmlExists) "jacoco-xml-present" else "jacoco-xml-missing",
                "instructionCoverage" to mapOf(
                    "covered" to instructionCovered,
                    "missed" to instructionMissed,
                    "percent" to percent(instructionCovered, instructionMissed)
                ),
                "branchCoverage" to mapOf(
                    "covered" to branchCovered,
                    "missed" to branchMissed,
                    "percent" to percent(branchCovered, branchMissed)
                ),
                "tests" to mapOf(
                    "total" to tests,
                    "passed" to (tests - failures - errors - skipped),
                    "failures" to failures,
                    "errors" to errors,
                    "skipped" to skipped,
                    "status" to when {
                        xmlFilesRead == 0 -> "no-results"
                        failures > 0 || errors > 0 -> "failed"
                        skipped > 0 -> "passed-with-skips"
                        else -> "passed"
                    }
                )
            )
        }
    }
}

val writeQodanaSummary by tasks.registering {
    description = "Writes build/reports/talos/qodana-summary.json from existing Qodana outputs."
    group = "reporting"
    val outputFile = talosReportsDir.map { it.file("qodana-summary.json") }
    outputs.file(outputFile)
    val qodanaRootDir = file(".qodana")
    val qodanaResultsDir = file(".qodana/report/results")
    val qodanaMetaFile = qodanaResultsDir.resolve("metaInformation.json")
    val qodanaProblemsFile = qodanaResultsDir.resolve("result-allProblems.json")
    val qodanaSarifFile = qodanaResultsDir.resolve("qodana.sarif.json")
    inputs.files(providers.provider {
        if (qodanaRootDir.exists()) {
            fileTree(qodanaRootDir)
        } else {
            files()
        }
    })
    inputs.property("projectVersion", project.version.toString())
    inputs.property("gitHead", providers.provider { gitOutput("rev-parse", "HEAD") ?: "unknown" })
    inputs.property("gitBranch", providers.provider { gitOutput("rev-parse", "--abbrev-ref", "HEAD") ?: "unknown" })

    doLast {
        val qodanaRoot = qodanaRootDir
        val resultsDir = qodanaResultsDir
        val metaFile = qodanaMetaFile
        val problemsFile = qodanaProblemsFile
        val sarifFile = qodanaSarifFile
        writeSummarySoft(outputFile.get().asFile, "qodana-summary", project.version.toString()) {
            val currentGitRevision = gitOutput("rev-parse", "HEAD")
            val currentGitBranch = gitOutput("rev-parse", "--abbrev-ref", "HEAD")

            val slurper = groovy.json.JsonSlurper()
            val meta = if (metaFile.exists()) slurper.parse(metaFile) as Map<*, *> else emptyMap<String, Any>()
            val problems = if (problemsFile.exists()) {
                ((slurper.parse(problemsFile) as Map<*, *>)["listProblem"] as? List<*>) ?: emptyList<Any>()
            } else emptyList<Any>()
            val sarifRuns = if (sarifFile.exists()) {
                ((slurper.parse(sarifFile) as Map<*, *>)["runs"] as? List<*>) ?: emptyList<Any>()
            } else emptyList<Any>()
            val qodanaAvailable = qodanaRoot.exists()
            val metaPresent = metaFile.exists()
            val problemsPresent = problemsFile.exists()
            val sarifPresent = sarifFile.exists()
            val firstSarifRun = sarifRuns.firstOrNull { it is Map<*, *> } as? Map<*, *>
            val sarifDriver = ((firstSarifRun?.get("tool") as? Map<*, *>)?.get("driver") as? Map<*, *>)
            val sarifVcs = ((firstSarifRun?.get("versionControlProvenance") as? List<*>)?.firstOrNull() as? Map<*, *>)
            val qodanaAttributes = meta["attributes"] as? Map<*, *>
            val qodanaVcs = qodanaAttributes?.get("vcs") as? Map<*, *>
            val qodanaSarifIdea = qodanaVcs?.get("sarifIdea") as? Map<*, *>
            val qodanaRevision = qodanaSarifIdea?.get("revisionId")?.toString()?.ifBlank { null }
                ?: sarifVcs?.get("revisionId")?.toString()?.ifBlank { null }
            val qodanaBranch = qodanaSarifIdea?.get("branch")?.toString()?.ifBlank { null }
                ?: sarifVcs?.get("branch")?.toString()?.ifBlank { null }

            val severityCounts = linkedMapOf<String, Int>()
            problems.forEach { raw ->
                if (raw is Map<*, *>) {
                    val severity = (raw["severity"]?.toString()?.trim()?.uppercase()).orEmpty().ifBlank { "UNKNOWN" }
                    severityCounts[severity] = (severityCounts[severity] ?: 0) + 1
                }
            }

            var sarifError = 0
            var sarifWarning = 0
            var sarifNote = 0
            var sarifIssueCount = 0
            var newIssues: Int? = 0
            sarifRuns.forEach { run ->
                if (run is Map<*, *>) {
                    val results = run["results"] as? List<*> ?: emptyList<Any>()
                    results.forEach { raw ->
                        if (raw is Map<*, *>) {
                            sarifIssueCount++
                            when (raw["level"]?.toString()?.lowercase()) {
                                "error" -> sarifError++
                                "warning" -> sarifWarning++
                                "note" -> sarifNote++
                            }
                            if (!problemsPresent) {
                                val properties = raw["properties"] as? Map<*, *>
                                val severity = properties?.get("qodanaSeverity")?.toString()?.trim()?.uppercase()
                                    ?.ifBlank { null } ?: "UNKNOWN"
                                severityCounts[severity] = (severityCounts[severity] ?: 0) + 1
                            }
                            val baselineState = raw["baselineState"]?.toString()
                            if (baselineState == null) {
                                newIssues = null
                            } else if (baselineState.equals("new", ignoreCase = true)) {
                                newIssues = (newIssues ?: 0) + 1
                            }
                        }
                    }
                }
            }

            val missingRequiredArtifacts = if (!qodanaAvailable) {
                listOf("metaInformation.json", "result-allProblems.json", "qodana.sarif.json")
            } else {
                listOfNotNull(if (sarifPresent) null else "qodana.sarif.json")
            }
            val missingAuxiliaryArtifacts = if (!qodanaAvailable) {
                emptyList()
            } else {
                listOfNotNull(
                    if (metaPresent) null else "metaInformation.json",
                    if (problemsPresent) null else "result-allProblems.json"
                )
            }
            val requiredArtifactStatus = when {
                !qodanaAvailable -> "qodana-results-missing"
                missingRequiredArtifacts.isEmpty() && missingAuxiliaryArtifacts.isEmpty() -> "all-required-artifacts-present"
                missingRequiredArtifacts.isEmpty() -> "sarif-only-results-present"
                else -> "required-artifacts-missing"
            }
            val revisionStatus = when {
                !qodanaAvailable -> "qodana-results-missing"
                qodanaRevision == null -> "qodana-revision-unavailable"
                currentGitRevision == null -> "current-git-revision-unavailable"
                qodanaRevision == currentGitRevision -> "matches-current-revision"
                else -> "revision-mismatch"
            }
            val branchStatus = when {
                !qodanaAvailable -> "qodana-results-missing"
                qodanaBranch == null -> "qodana-branch-unavailable"
                currentGitBranch == null -> "current-git-branch-unavailable"
                qodanaBranch == currentGitBranch -> "matches-current-branch"
                else -> "branch-mismatch"
            }
            val summaryStatus = when {
                !qodanaAvailable -> "qodana-results-missing"
                missingRequiredArtifacts.isNotEmpty() -> "qodana-results-incomplete"
                revisionStatus == "revision-mismatch" || branchStatus == "branch-mismatch" -> "stale-qodana-provenance"
                revisionStatus != "matches-current-revision" || branchStatus != "matches-current-branch" -> "qodana-provenance-incomplete"
                else -> "qodana-results-match-current-candidate"
            }

            mapOf(
                "version" to project.version.toString(),
                "available" to qodanaAvailable,
                "summaryStatus" to summaryStatus,
                "sourcePaths" to mapOf(
                    "root" to qodanaRoot.absolutePath,
                    "resultsDir" to resultsDir.absolutePath,
                    "metaFile" to metaFile.absolutePath,
                    "problemsFile" to problemsFile.absolutePath,
                    "sarifFile" to sarifFile.absolutePath
                ),
                "requiredArtifacts" to mapOf(
                    "status" to requiredArtifactStatus,
                    "missing" to missingRequiredArtifacts,
                    "auxiliaryMissing" to missingAuxiliaryArtifacts,
                    "files" to mapOf(
                        "metaInformation" to metaPresent,
                        "allProblems" to problemsPresent,
                        "sarif" to sarifPresent
                    )
                ),
                "provenance" to mapOf(
                    "qodanaSourceBranch" to qodanaBranch,
                    "qodanaSourceRevision" to qodanaRevision,
                    "currentGitBranch" to currentGitBranch,
                    "currentGitRevision" to currentGitRevision,
                    "revisionStatus" to revisionStatus,
                    "branchStatus" to branchStatus
                ),
                "linter" to (meta["linter"] ?: sarifDriver?.get("name")),
                "linterVersion" to (meta["linterVersion"] ?: sarifDriver?.get("version")),
                "totalIssues" to ((meta["total"] as? Number)?.toInt() ?: if (problemsPresent) problems.size else sarifIssueCount),
                "severityCounts" to severityCounts,
                "sarifLevelCounts" to mapOf(
                    "error" to sarifError,
                    "warning" to sarifWarning,
                    "note" to sarifNote
                ),
                "criticalIssues" to if (!qodanaRoot.exists()) null else (severityCounts["CRITICAL"] ?: 0),
                "criticalIssuesStatus" to when {
                    !qodanaRoot.exists() -> "qodana-results-missing"
                    severityCounts.isNotEmpty() -> "derived-from-problem-severities"
                    else -> "unknown-problem-severities-missing"
                },
                "highIssues" to (severityCounts["HIGH"] ?: 0),
                "newIssues" to newIssues,
                "newIssuesStatus" to when {
                    !qodanaRoot.exists() -> "qodana-results-missing"
                    newIssues == null -> "unknown-no-baseline-state"
                    else -> "derived-from-sarif-baseline-state"
                }
            )
        }
    }
}

val writeE2eSummary by tasks.registering {
    description = "Writes build/reports/talos/e2e-summary.json from e2eTest JUnit XML."
    group = "reporting"
    dependsOn(candidateE2eTest)
    val outputFile = talosReportsDir.map { it.file("e2e-summary.json") }
    outputs.file(outputFile)
    val e2eResultsDirProvider = layout.buildDirectory.dir("test-results/candidateE2eTest")
    // Precise input: only TEST-*.xml files drive re-runs.
    inputs.files(providers.provider {
        val dir = e2eResultsDirProvider.get().asFile
        if (dir.exists()) fileTree(dir) { include("TEST-*.xml") } else files()
    })
    inputs.dir(file("src/e2eTest/resources/scenarios"))
    inputs.property("projectVersion", project.version.toString())

    doLast {
        val e2eResultsDir = e2eResultsDirProvider.get().asFile
        writeSummarySoft(outputFile.get().asFile, "e2e-summary", project.version.toString()) {
            val scenarioFiles = fileTree("src/e2eTest/resources/scenarios") {
                include("**/*.json")
            }.files.sortedBy { it.name }
            val slurper = groovy.json.JsonSlurper()
            val scenarioMetadata = scenarioFiles.map { file ->
                val parsed = (slurper.parse(file) as? Map<*, *>) ?: emptyMap<String, Any?>()
                val claims = (parsed["claims"] as? List<*>)?.map { it.toString() } ?: emptyList()
                mapOf(
                    "resource" to "scenarios/${file.name}",
                    "name" to ((parsed["name"] as? String) ?: file.nameWithoutExtension),
                    "runner" to ((parsed["runner"] as? String) ?: ""),
                    "v1Pack" to (parsed["v1Pack"] == true),
                    "claims" to claims
                )
            }

            var tests = 0
            var failures = 0
            var errors = 0
            var skipped = 0
            var xmlFilesRead = 0
            val scenarios = mutableListOf<Map<String, Any?>>()
            val jsonScenarioExecutions = mutableListOf<Map<String, Any?>>()

            if (e2eResultsDir.exists()) {
                e2eResultsDir.listFiles { file -> file.isFile && file.name.startsWith("TEST-") && file.name.endsWith(".xml") }
                    ?.sortedBy { it.name }
                    ?.forEach { xml ->
                        xmlFilesRead++
                        val suite = parseXml(xml).documentElement
                        tests += suite.getAttribute("tests").toInt()
                        failures += suite.getAttribute("failures").toInt()
                        errors += suite.getAttribute("errors").toInt()
                        skipped += suite.getAttribute("skipped").toInt()
                        elements(suite, "testcase").forEach { testCase ->
                            val caseName = testCase.getAttribute("name")
                            val className = testCase.getAttribute("classname")
                            val jsonScenarioResource = extractJsonScenarioResource(caseName)
                            val failureNodes = testCase.getElementsByTagName("failure")
                            val errorNodes = testCase.getElementsByTagName("error")
                            val skippedNodes = testCase.getElementsByTagName("skipped")
                            val status = when {
                                failureNodes.length > 0 -> "failed"
                                errorNodes.length > 0 -> "error"
                                skippedNodes.length > 0 -> "skipped"
                                else -> "passed"
                            }
                            scenarios += mapOf(
                                "name" to caseName,
                                "className" to className,
                                "jsonScenarioResource" to jsonScenarioResource,
                                "status" to status,
                                "durationSeconds" to testCase.getAttribute("time").toBigDecimalOrNull(),
                                "failureMessage" to when (status) {
                                    "failed" -> (failureNodes.item(0) as org.w3c.dom.Element).getAttribute("message")
                                    "error" -> (errorNodes.item(0) as org.w3c.dom.Element).getAttribute("message")
                                    else -> null
                                }
                            )
                            if (jsonScenarioResource != null) {
                                jsonScenarioExecutions += mapOf(
                                    "resource" to jsonScenarioResource,
                                    "testCaseName" to caseName,
                                    "className" to className,
                                    "status" to status,
                                    "durationSeconds" to testCase.getAttribute("time").toBigDecimalOrNull(),
                                    "failureMessage" to when (status) {
                                        "failed" -> (failureNodes.item(0) as org.w3c.dom.Element).getAttribute("message")
                                        "error" -> (errorNodes.item(0) as org.w3c.dom.Element).getAttribute("message")
                                        else -> null
                                    }
                                )
                            }
                        }
                    }
            }

            val executedTestCases = scenarios.size
            val jsonScenarioBackedExecutedCases = jsonScenarioExecutions.size
            val untaggedExecutedTestCases = executedTestCases - jsonScenarioBackedExecutedCases
            val executedJsonScenarioResources = jsonScenarioExecutions.mapNotNull { it["resource"] as? String }.distinct().sorted()
            val allJsonScenarioResources = scenarioFiles.map { "scenarios/${it.name}" }
            val unexecutedJsonScenarioResources = allJsonScenarioResources.filterNot(executedJsonScenarioResources::contains)
            fun aggregateScenarioStatus(executions: List<Map<String, Any?>>): String = when {
                executions.any { (it["status"] as? String) == "error" } -> "error"
                executions.any { (it["status"] as? String) == "failed" } -> "failed"
                executions.any { (it["status"] as? String) == "skipped" } -> "skipped"
                executions.any { (it["status"] as? String) == "passed" } -> "passed"
                else -> "not-executed"
            }
            val scenarioStatusByResource = allJsonScenarioResources.associateWith { resource ->
                aggregateScenarioStatus(jsonScenarioExecutions.filter { it["resource"] == resource })
            }
            val passedJsonScenarioResources = scenarioStatusByResource
                .filterValues { it == "passed" }
                .keys
                .sorted()
            val failedJsonScenarioResources = scenarioStatusByResource
                .filterValues { it == "failed" || it == "error" }
                .keys
                .sorted()
            val skippedJsonScenarioResources = scenarioStatusByResource
                .filterValues { it == "skipped" }
                .keys
                .sorted()
            val v1ScenarioMetadata = scenarioMetadata.filter { it["v1Pack"] == true }
            val v1ScenarioResources = v1ScenarioMetadata.mapNotNull { it["resource"] as? String }.sorted()
            val executedV1Resources = v1ScenarioResources.filter(executedJsonScenarioResources::contains)
            val passedV1Resources = v1ScenarioResources.filter(passedJsonScenarioResources::contains)
            val failedV1Resources = v1ScenarioResources.filter(failedJsonScenarioResources::contains)
            val unexecutedV1Resources = v1ScenarioResources.filterNot(executedJsonScenarioResources::contains)
            val v1Claims = v1ScenarioMetadata.flatMap { (it["claims"] as? List<*>)?.map { claim -> claim.toString() } ?: emptyList() }
                .distinct()
                .sorted()
            val executedV1Claims = v1ScenarioMetadata
                .filter { executedJsonScenarioResources.contains(it["resource"] as? String) }
                .flatMap { (it["claims"] as? List<*>)?.map { claim -> claim.toString() } ?: emptyList() }
                .distinct()
                .sorted()
            val passedV1Claims = v1ScenarioMetadata
                .filter { passedJsonScenarioResources.contains(it["resource"] as? String) }
                .flatMap { (it["claims"] as? List<*>)?.map { claim -> claim.toString() } ?: emptyList() }
                .distinct()
                .sorted()
            val unprovenV1Claims = v1Claims.filterNot(passedV1Claims::contains)
            val resourceTraceabilityStatus = when {
                allJsonScenarioResources.isEmpty() -> "no-json-scenarios-defined"
                executedTestCases == 0 -> "no-testcases-executed"
                jsonScenarioBackedExecutedCases == 0 -> "no-tags-detected"
                jsonScenarioBackedExecutedCases == executedTestCases -> "all-executed-cases-traceable"
                else -> "partially-traceable-executed-cases"
            }
            val traceabilityScopeStatus = when {
                allJsonScenarioResources.isEmpty() -> "suite-has-no-json-scenario-subset"
                executedTestCases == 0 -> "suite-did-not-execute"
                jsonScenarioBackedExecutedCases == 0 -> "json-scenario-subset-not-detected-in-results"
                untaggedExecutedTestCases == 0 -> "all-executed-cases-are-json-scenario-backed"
                else -> "suite-mixes-json-scenario-backed-and-non-json-harness-cases"
            }
            val v1PackCoverageStatus = when {
                v1ScenarioResources.isEmpty() -> "no-v1-pack-defined"
                executedTestCases == 0 -> "suite-did-not-execute"
                passedV1Resources.isEmpty() -> "v1-pack-not-proven"
                passedV1Resources.size == v1ScenarioResources.size -> "all-v1-pack-resources-passed"
                else -> "partially-proven-v1-pack"
            }

            mapOf(
                "version" to project.version.toString(),
                "sourcePaths" to mapOf(
                    "resultsDir" to e2eResultsDir.absolutePath,
                    "scenarioResourceDir" to file("src/e2eTest/resources/scenarios").absolutePath
                ),
                "testExecution" to mapOf(
                    "total" to tests,
                    "passed" to (tests - failures - errors - skipped),
                    "failures" to failures,
                    "errors" to errors,
                    "skipped" to skipped,
                    "executedTestCaseCount" to executedTestCases,
                    "status" to when {
                        xmlFilesRead == 0 -> "no-results"
                        failures > 0 || errors > 0 -> "failed"
                        skipped > 0 -> "passed-with-skips"
                        else -> "passed"
                    }
                ),
                "scenarioResources" to mapOf(
                    "jsonScenarioFiles" to scenarioFiles.map { it.name },
                    "jsonScenarioFileCount" to scenarioFiles.size,
                    "jsonScenarioResourcePaths" to allJsonScenarioResources,
                    "metadata" to scenarioMetadata
                ),
                "jsonScenarioCoverage" to mapOf(
                    "executedTestCaseCount" to jsonScenarioBackedExecutedCases,
                    "untaggedExecutedTestCaseCount" to untaggedExecutedTestCases,
                    "executedResourceCount" to executedJsonScenarioResources.size,
                    "passedResourceCount" to passedJsonScenarioResources.size,
                    "resourceCount" to allJsonScenarioResources.size,
                    "resourceTraceabilityStatus" to resourceTraceabilityStatus,
                    "traceabilityScopeStatus" to traceabilityScopeStatus,
                    "executedResources" to executedJsonScenarioResources,
                    "passedResources" to passedJsonScenarioResources,
                    "failedResources" to failedJsonScenarioResources,
                    "skippedResources" to skippedJsonScenarioResources,
                    "unexecutedResources" to unexecutedJsonScenarioResources,
                    "resourceStatuses" to allJsonScenarioResources.map { resource ->
                        mapOf(
                            "resource" to resource,
                            "status" to scenarioStatusByResource.getValue(resource)
                        )
                    },
                    "executions" to jsonScenarioExecutions
                ),
                "v1ScenarioPack" to mapOf(
                    "resourceCount" to v1ScenarioResources.size,
                    "executedResourceCount" to executedV1Resources.size,
                    "passedResourceCount" to passedV1Resources.size,
                    "coverageStatus" to v1PackCoverageStatus,
                    "resources" to v1ScenarioMetadata,
                    "executedResources" to executedV1Resources,
                    "passedResources" to passedV1Resources,
                    "failedResources" to failedV1Resources,
                    "unexecutedResources" to unexecutedV1Resources,
                    "claims" to v1Claims,
                    "executedClaims" to executedV1Claims,
                    "passedClaims" to passedV1Claims,
                    "unprovenClaims" to unprovenV1Claims
                ),
                "scenarios" to scenarios
            )
        }
    }
}

tasks.register("talosQualitySummaries") {
    description = "Generates all machine-readable Talos quality summary JSON artifacts."
    group = "reporting"
    dependsOn(writeVersionSummary, writeCoverageSummary, writeQodanaSummary, writeE2eSummary)
}

tasks.register("writeQualityMarkdownReports") {
    description = "Writes reviewer-friendly Markdown quality reports from Talos summary JSON artifacts."
    group = "reporting"
    dependsOn("talosQualitySummaries")

    val reportsDir = layout.projectDirectory.dir("reports")
    val coverageSummary = talosReportsDir.map { it.file("coverage-summary.json") }
    val e2eSummary = talosReportsDir.map { it.file("e2e-summary.json") }
    val qodanaSummary = talosReportsDir.map { it.file("qodana-summary.json") }
    val versionSummary = talosReportsDir.map { it.file("version-summary.json") }

    inputs.files(coverageSummary, e2eSummary, qodanaSummary, versionSummary)
    inputs.property("reportDate", providers.provider { reportDateStamp() })
    outputs.dir(reportsDir)
    outputs.upToDateWhen { false }

    doLast {
        val slurper = groovy.json.JsonSlurper()
        fun readSummary(file: java.io.File): Map<*, *> = slurper.parse(file) as Map<*, *>
        fun cleanupPreviousReports() {
            reportsDir.asFile.mkdirs()
            val generatedReportName = Regex("^(coverage|e2e|qodana|version)-\\d{8}-[A-Za-z0-9]+\\.md$")
            reportsDir.asFile.listFiles { file -> file.isFile && generatedReportName.matches(file.name) }
                ?.forEach { it.delete() }
        }
        fun writeReport(reportName: String, version: String, content: String) {
            val fileName = "$reportName-${reportDateStamp()}-${reportVersionStamp(version)}.md"
            reportsDir.asFile.mkdirs()
            reportsDir.file(fileName).asFile.writeText(content.trimIndent() + "\n", Charsets.UTF_8)
        }

        val coverage = readSummary(coverageSummary.get().asFile)
        val e2e = readSummary(e2eSummary.get().asFile)
        val qodana = readSummary(qodanaSummary.get().asFile)
        val version = readSummary(versionSummary.get().asFile)
        val talosVersion = mdSafe(version["version"])
        val reportDate = reportIsoDate()
        cleanupPreviousReports()

        val instructionCoverage = mdMap(coverage["instructionCoverage"])
        val branchCoverage = mdMap(coverage["branchCoverage"])
        val coverageTests = mdMap(coverage["tests"])
        val instructionPercent = (instructionCoverage["percent"] as? Number)?.toDouble()
        val branchPercent = (branchCoverage["percent"] as? Number)?.toDouble()
        val gate = 65.0
        val gateMargin = if (instructionPercent == null) null else instructionPercent - gate
        val coverageTotalTests = mdInt(coverageTests["total"])
        val coveragePassed = mdInt(coverageTests["passed"])
        val coverageSkipped = mdInt(coverageTests["skipped"])
        val coverageFailures = mdInt(coverageTests["failures"])
        val coverageErrors = mdInt(coverageTests["errors"])

        writeReport("coverage", talosVersion, """
            # Coverage Report - $reportDate - Talos $talosVersion

            This report is useful as a release gate snapshot: it tells us whether the candidate test lane passed and whether instruction coverage still clears the local gate. Its main limitation is that it does not identify which uncovered branches matter most, so it should be paired with code review or the JaCoCo HTML report when assessing risky changes.

            ```text
            +--------------------------------------------------------------+
            | QUALITY LANE: COVERAGE                                      |
            | Reviewer decision: did tests pass, and is coverage regressing?|
            ${mdBoxLine("Result: ${mdSafe(coverageTests["status"]).uppercase()}")}
            +--------------------------------------------------------------+
            ```

            ## Decision Summary

            | Question | Answer | Confidence |
            | --- | --- | --- |
            | Did the candidate test lane pass? | ${if (coverageFailures == 0 && coverageErrors == 0) "Yes, with `$coverageSkipped` skipped tests" else "No, failures or errors are present"} | High |
            | Is instruction coverage above the local gate? | ${if (instructionPercent != null && instructionPercent >= gate) "Yes, `${mdPercent(instructionPercent)}` vs `65.00%`" else "No or unknown"} | High |
            | Is branch coverage strong? | ${if (branchPercent != null && branchPercent >= 65.0) "Yes, `${mdPercent(branchPercent)}`" else "Mixed, `${mdPercent(branchPercent)}` leaves risk in conditional paths"} | Medium |
            | Is this report useful for release review? | Yes for regression gating, not enough for feature-risk assessment alone | Medium |

            ## Gate Margin

            Decision question: how much room do we have before the coverage gate fails?

            ```text
            Instruction coverage gate

            0%                 65.00% gate      ${mdPercent(instructionPercent)} actual             100%
            |----------------------|==============|--------------------------|
                                   |<-- ${if (gateMargin == null) "n/a" else "%+.2f pts".format(gateMargin)} -->|

            Interpretation:
              + ${if (gateMargin != null && gateMargin >= 5.0) "comfortable enough for this run" else "thin or unknown margin"}
              + not enough to ignore future drops
            ```

            ## Risk Concentration

            Decision question: where should reviewers focus if coverage must improve?

            ```text
            Coverage risk

            Instructions:  covered ${mdBar((instructionPercent ?: 0.0).toInt(), 100, 36)}  ${mdPercent(instructionPercent)}
                           missed  ${mdBar((100.0 - (instructionPercent ?: 0.0)).toInt(), 100, 36)}  ${mdPercent(if (instructionPercent == null) null else 100.0 - instructionPercent)}

            Branches:      covered ${mdBar((branchPercent ?: 0.0).toInt(), 100, 36)}  ${mdPercent(branchPercent)}
                           missed  ${mdBar((100.0 - (branchPercent ?: 0.0)).toInt(), 100, 36)}  ${mdPercent(if (branchPercent == null) null else 100.0 - branchPercent)}

            Reviewer signal:
              branch coverage is the weaker signal, so inspect decision-heavy code first.
            ```

            ## Test Outcome Triage

            Decision question: are failures blocking, or is the only test caveat skipped coverage?

            ```text
            candidateTest outcome

            $coverageTotalTests total
              |
              +-- $coveragePassed passed  -> release-positive signal
              +-- $coverageFailures failed  -> ${if (coverageFailures == 0) "no blocking test failures" else "blocking failures present"}
              +-- $coverageErrors errors  -> ${if (coverageErrors == 0) "no harness/runtime breakage" else "runtime or harness errors present"}
              +-- $coverageSkipped skipped -> verify skips are intentional
            ```

            ## Source Artifacts

            | Artifact | Path |
            | --- | --- |
            | Talos JSON summary | `build/reports/talos/coverage-summary.json` |
            | JaCoCo XML | `build/reports/jacoco/candidateTest/candidateJacocoTestReport.xml` |
            | JaCoCo HTML | `build/reports/jacoco/candidateTest/html/index.html` |
            | Test results | `build/test-results/candidateTest` |
        """)

        val e2eExecution = mdMap(e2e["testExecution"])
        val scenarioCoverage = mdMap(e2e["jsonScenarioCoverage"])
        val scenarioResources = mdMap(e2e["scenarioResources"])
        val v1ScenarioPack = mdMap(e2e["v1ScenarioPack"])
        val e2eTotal = mdInt(e2eExecution["total"])
        val e2ePassed = mdInt(e2eExecution["passed"])
        val e2eFailures = mdInt(e2eExecution["failures"])
        val e2eErrors = mdInt(e2eExecution["errors"])
        val e2eSkipped = mdInt(e2eExecution["skipped"])
        val resourceCount = mdInt(scenarioCoverage["resourceCount"])
        val executedResourceCount = mdInt(scenarioCoverage["executedResourceCount"])
        val passedResourceCount = mdInt(scenarioCoverage["passedResourceCount"])
        val jsonBacked = mdInt(scenarioCoverage["executedTestCaseCount"])
        val untagged = mdInt(scenarioCoverage["untaggedExecutedTestCaseCount"])
        val scenarioStatuses = mdList(scenarioCoverage["resourceStatuses"]).map { mdMap(it) }
        val v1Resources = mdList(v1ScenarioPack["resources"]).map { mdMap(it) }
        val v1PassedClaims = mdList(v1ScenarioPack["passedClaims"]).map { it.toString() }
        val v1UnprovenClaims = mdList(v1ScenarioPack["unprovenClaims"]).map { it.toString() }
        val scenarioLines = scenarioStatuses.joinToString("\n") { resourceStatus ->
            val file = mdSafe(resourceStatus["resource"]).removePrefix("scenarios/")
            val label = file.removeSuffix(".json").replace(Regex("^\\d+-"), "").replace("-", " ")
            val status = mdSafe(resourceStatus["status"]).uppercase()
            "  +-- ${label.padEnd(42, '.')} $status"
        }
        val indentedScenarioLines = (scenarioLines.ifBlank { "  +-- no JSON scenarios discovered" }).prependIndent("            ")
        val v1ScenarioLines = v1Resources.joinToString("\n") { resource ->
            val label = mdSafe(resource["name"])
            val claims = mdList(resource["claims"]).map { it.toString() }
            val claimSummary = if (claims.isEmpty()) "no claims tagged" else claims.joinToString(", ")
            val resourcePath = mdSafe(resource["resource"])
            val status = scenarioStatuses.firstOrNull { mdSafe(mdMap(it)["resource"]) == resourcePath }
                ?.let { mdSafe(it["status"]).uppercase() } ?: "NOT-EXECUTED"
            "  +-- ${label.padEnd(34, '.')} ${status.padEnd(11, ' ')} ${claimSummary}"
        }
        val indentedV1ScenarioLines = (v1ScenarioLines.ifBlank { "  +-- no V1 scenario pack metadata present" }).prependIndent("            ")
        val v1ClaimSummary = if (v1PassedClaims.isEmpty()) "none" else v1PassedClaims.joinToString(", ")
        val v1ClaimGapSummary = if (v1UnprovenClaims.isEmpty()) "none" else v1UnprovenClaims.joinToString(", ")

        writeReport("e2e", talosVersion, """
            # E2E Report - $reportDate - Talos $talosVersion

            This report is useful because it maps E2E success to recognizable behavior areas instead of only listing test counts. Its limitation is traceability: `$untagged` passing harness cases are not represented as named JSON scenario files, so the report is strongest for the scenario-backed workflows and weaker as a full behavioral inventory.

            ```text
            +--------------------------------------------------------------+
            | QUALITY LANE: E2E / SCENARIOS                               |
            | Reviewer decision: did user-facing workflows survive?        |
            ${mdBoxLine("Result: ${mdSafe(e2eExecution["status"]).uppercase()}")}
            +--------------------------------------------------------------+
            ```

            ## Decision Summary

            | Question | Answer | Confidence |
            | --- | --- | --- |
            | Did every E2E test pass? | ${if (e2eFailures == 0 && e2eErrors == 0 && e2eSkipped == 0) "Yes, `$e2ePassed / $e2eTotal` passed" else "No, review failures/errors/skips"} | High |
            | Did every JSON scenario resource pass? | ${if (passedResourceCount == resourceCount) "Yes, `$passedResourceCount / $resourceCount` passed" else "No, `$passedResourceCount / $resourceCount` passed"} | High |
            | Is traceability complete for all E2E cases? | ${if (untagged == 0) "Yes" else "No, `$untagged` harness cases are not JSON-resource-backed"} | Medium |
            | Is this report useful for release review? | Yes for workflow confidence, partial for scenario inventory governance | High |

            ## Workflow Coverage

            Decision question: which product behaviors are covered by named scenarios?

            ```text
            User workflow checks

${indentedScenarioLines}
            ```

            ## V1 Scenario Pack

            Decision question: which architecture claims are explicitly covered by the curated V1 pack?

            ```text
            Curated V1 pack resources

${indentedV1ScenarioLines}

            Proven V1 claims:
              $v1ClaimSummary

            Remaining V1 claim gaps:
              $v1ClaimGapSummary
            ```

            ## Traceability Gap

            Decision question: can every passing E2E test be traced back to a scenario file?

            ```text
            $e2eTotal E2E tests passed
              |
              +-- $jsonBacked JSON-backed scenarios -> traceable product workflows
              |
              +-- $untagged harness-only cases ----> useful checks, weaker report traceability

            Decision:
              ${if (untagged == 0) "Traceability is complete for this lane." else "Acceptable for now, but future scenario governance should move important harness-only workflows into named JSON scenarios."}
            ```

            ## Release Confidence Path

            Decision question: what does this lane prove before release?

            ```text
            scenario files -> harness execution -> all pass -> workflow confidence
                  |                 |                |              |
                  |                 |                |              +-- ${if (e2eFailures == 0 && e2eErrors == 0) "no known E2E blocker" else "blocking E2E evidence present"}
                  |                 |                +----------------- $e2ePassed/$e2eTotal green
                  |                 +---------------------------------- deterministic lane
                  +---------------------------------------------------- named behavior set
            ```

            ## Source Artifacts

            | Artifact | Path |
            | --- | --- |
            | Talos JSON summary | `build/reports/talos/e2e-summary.json` |
            | E2E test results | `build/test-results/candidateE2eTest` |
            | Scenario resources | `src/e2eTest/resources/scenarios` |
        """)

        val severityCounts = mdMap(qodana["severityCounts"])
        val sarifLevelCounts = mdMap(qodana["sarifLevelCounts"])
        val provenance = mdMap(qodana["provenance"])
        val requiredArtifacts = mdMap(qodana["requiredArtifacts"])
        val highIssues = mdInt(severityCounts["HIGH"])
        val moderateIssues = mdInt(severityCounts["MODERATE"])
        val criticalIssues = mdInt(severityCounts["CRITICAL"])
        val totalIssues = mdInt(qodana["totalIssues"])
        val maxSeverity = listOf(highIssues, moderateIssues, criticalIssues, 1).max()
        val qodanaBranch = mdSafe(provenance["qodanaSourceBranch"])
        val currentBranch = mdSafe(provenance["currentGitBranch"])
        val qodanaRevision = mdSafe(provenance["qodanaSourceRevision"]).take(7)
        val currentRevision = mdSafe(provenance["currentGitRevision"]).take(7)

        writeReport("qodana", talosVersion, """
            # Qodana Report - $reportDate - Talos $talosVersion

            This report is useful because it answers the two questions that caused previous ambiguity: whether the scan is current, and how much static-analysis triage remains. Its main limitation is that it summarizes severity, not root causes. For actual remediation, open the Qodana HTML or SARIF report and group issues by inspection type.

            ```text
            +--------------------------------------------------------------+
            | QUALITY LANE: QODANA                                        |
            | Reviewer decision: is static analysis current and actionable? |
            ${mdBoxLine("Result: ${mdSafe(qodana["summaryStatus"]).uppercase()}")}
            +--------------------------------------------------------------+
            ```

            ## Decision Summary

            | Question | Answer | Confidence |
            | --- | --- | --- |
            | Does this scan match the current workspace? | ${if (provenance["branchStatus"] == "matches-current-branch" && provenance["revisionStatus"] == "matches-current-revision") "Yes, branch and revision match" else "No or incomplete provenance"} | High |
            | Are there critical issues? | ${if (criticalIssues == 0) "No, `0` critical" else "Yes, `$criticalIssues` critical"} | High |
            | Are there high-priority issues to triage? | ${if (highIssues > 0) "Yes, `$highIssues` high" else "No high issues"} | High |
            | Is this report useful for release review? | Yes for triage pressure and provenance, not enough for root-cause details | High |

            ## Release Triage Funnel

            Decision question: what should happen before release confidence improves?

            ```text
            $totalIssues Qodana findings
              |
              +-- $criticalIssues CRITICAL -> ${if (criticalIssues == 0) "no immediate static-analysis blocker" else "block release until reviewed"}
              |
              +-- $highIssues HIGH ----> ${if (highIssues == 0) "no high-severity triage needed" else "triage required"}
              |       |
              |       +-- fix true positives
              |       +-- suppress accepted false positives with justification
              |       +-- backlog low-risk cleanup explicitly
              |
              +-- $moderateIssues MODERATE -> review after high-severity pass
            ```

            ## Provenance Gate

            Decision question: can reviewers trust that this report belongs to this candidate?

            ```text
            Qodana scan                         Current workspace
            +----------------------+           +----------------------+
            | branch: ${qodanaBranch.take(14).padEnd(14)} |  ${mdSafe(provenance["branchStatus"]).replace("matches-current-branch", "MATCH").take(5).padEnd(5)}    | branch: ${currentBranch.take(14).padEnd(14)} |
            | rev:    ${qodanaRevision.padEnd(7)}      |  ----->   | rev:    ${currentRevision.padEnd(7)}      |
            +----------------------+           +----------------------+

            Decision:
              ${if (provenance["branchStatus"] == "matches-current-branch" && provenance["revisionStatus"] == "matches-current-revision") "Trust the report as current. Do not treat it as stale evidence." else "Do not use this report as current release evidence until provenance is fixed."}
            ```

            ## Severity Pressure

            Decision question: is the issue set mostly cleanup, or does it demand active triage?

            ```text
            Severity pressure

            HIGH      ${highIssues.toString().padStart(3)}  ${mdBar(highIssues, maxSeverity, 40)}  ${if (highIssues > 0) "demands triage" else "clean"}
            MODERATE  ${moderateIssues.toString().padStart(3)}  ${mdBar(moderateIssues, maxSeverity, 40)}  review next
            CRITICAL  ${criticalIssues.toString().padStart(3)}  ${mdBar(criticalIssues, maxSeverity, 40)}  ${if (criticalIssues == 0) "no critical blocker" else "blocker"}

            Reviewer signal:
              the lane is current, but not clean.
            ```

            ## Status Details

            | Field | Value |
            | --- | --- |
            | Summary status | `${mdSafe(qodana["summaryStatus"])}` |
            | Required artifact status | `${mdSafe(requiredArtifacts["status"])}` |
            | Linter | `${mdSafe(qodana["linter"])}` |
            | Linter version | `${mdSafe(qodana["linterVersion"])}` |
            | Branch status | `${mdSafe(provenance["branchStatus"])}` |
            | Revision status | `${mdSafe(provenance["revisionStatus"])}` |
            | SARIF warnings | `${mdInt(sarifLevelCounts["warning"])}` |
            | SARIF notes | `${mdInt(sarifLevelCounts["note"])}` |
            | New issues | ${if (qodana["newIssues"] == null) "unknown, no baseline state" else "`" + qodana["newIssues"] + "`"} |

            ## Source Artifacts

            | Artifact | Path |
            | --- | --- |
            | Talos JSON summary | `build/reports/talos/qodana-summary.json` |
            | SARIF | `.qodana/report/results/qodana.sarif.json` |
            | HTML report | `.qodana/report/index.html` |
        """)

        val artifacts = mdList(version["artifacts"])
        val firstArtifact = mdMap(artifacts.firstOrNull())
        val taskState = mdMap(version["jarTaskStateInCurrentInvocation"])
        val jarStatus = mdSafe(taskState["status"])
        val jarExists = mdSafe(taskState["jarExists"])
        val jarModified = mdSafe(taskState["jarLastModifiedIso"])

        writeReport("version", talosVersion, """
            # Version Report - $reportDate - Talos $talosVersion

            This report is useful as a provenance check: it prevents reviewers from accidentally trusting stale jar output. It should remain short because artifact freshness is supporting evidence, not a standalone quality decision.

            ```text
            +--------------------------------------------------------------+
            | QUALITY LANE: VERSION / ARTIFACT                            |
            | Reviewer decision: was the candidate artifact freshly built? |
            ${mdBoxLine("Result: ${jarStatus.uppercase()}")}
            +--------------------------------------------------------------+
            ```

            ## Decision Summary

            | Question | Answer | Confidence |
            | --- | --- | --- |
            | Does the expected jar exist? | ${if (jarExists == "true") "Yes, `build/libs/talos.jar`" else "No or unknown"} | High |
            | Was it built in the current run? | ${if (jarStatus == "built-in-current-run") "Yes, `$jarStatus`" else "No, `$jarStatus`"} | High |
            | Does this prove runtime correctness? | No, it only proves artifact freshness | High |
            | Is this report useful for release review? | Yes as artifact provenance, not as a quality signal by itself | Medium |

            ## Artifact Freshness Gate

            Decision question: are we looking at a fresh candidate or stale build residue?

            ```text
            Gradle invocation
              |
              +-- jar task status: $jarStatus
                    |
                    +-- build/libs/talos.jar exists: $jarExists
                          |
                          +-- last modified $jarModified
                                |
                                +-- Decision: ${if (jarStatus == "built-in-current-run") "artifact is fresh for this packet" else "artifact was not rebuilt in this packet"}
            ```

            ## What This Lane Proves

            Decision question: how much release confidence should artifact freshness provide?

            ```text
            Artifact report confidence

            Fresh jar exists      [${if (jarExists == "true") "#".repeat(30) else ".".repeat(30)}] ${if (jarExists == "true") "strong evidence" else "missing evidence"}
            Correct version       [${"#".repeat(30)}] strong evidence
            Runtime correctness   [${".".repeat(30)}] not proven here
            Static quality        [${".".repeat(30)}] not proven here

            Reviewer signal:
              use this as provenance, not as a substitute for test/Qodana reports.
            ```

            ## Artifact State

            | Field | Value |
            | --- | --- |
            | Version | `${mdSafe(version["version"])}` |
            | Artifact | `${mdSafe(firstArtifact["name"])}` |
            | Artifact exists | `${mdSafe(firstArtifact["exists"])}` |
            | Jar task status | `$jarStatus` |
            | Built at | `${mdSafe(version["jarBuiltAt"])}` |
            | Last modified epoch ms | `${mdSafe(firstArtifact["lastModifiedEpochMs"])}` |

            ## Source Artifacts

            | Artifact | Path |
            | --- | --- |
            | Talos JSON summary | `build/reports/talos/version-summary.json` |
            | Jar artifact | `build/libs/talos.jar` |
        """)
    }
}

tasks.named("writeQodanaSummary") {
    mustRunAfter("qodanaNativeFreshLocal")
}

tasks.register("talosQualityLocal") {
    description = "Runs fresh native Qodana, then writes all machine-readable Talos quality summary JSON artifacts."
    group = "verification"
    dependsOn("qodanaNativeFreshLocal", "writeQualityMarkdownReports")
}
