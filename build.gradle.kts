plugins {
    application
    jacoco
}

val talosReportsDir = layout.buildDirectory.dir("reports/talos")

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

/* ---------- Repositories ---------- */

repositories {
    mavenCentral()
}

/* ---------- Dependencies ---------- */

val javafxVersion = project.property("javafxVersion")
val javafxPlatform = project.property("javafxPlatform")

dependencies {
    implementation("info.picocli:picocli:${project.property("picocliVersion")}")
    annotationProcessor("info.picocli:picocli-codegen:${project.property("picocliVersion")}")

    // JavaFX (Windows artifacts)
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")

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

    // REPL
    implementation("org.jline:jline:3.26.3")

    // JUnit 5 (explicit engine to avoid Gradle 9 deprecation)
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
        "--add-modules", "jdk.incubator.vector",
        "-Dfile.encoding=UTF-8",
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

/* ---------- jpackage (MSI) ---------- */

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
        val args = mutableListOf(
            jpackageExe.get(),
            "--type", "msi",
            "--name", "Talos",
            "--app-version", appVer.get(),
            "--vendor", "Talos Project",
            "--dest", destDir.get().asFile.absolutePath,
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", "talos.jar",
            "--main-class", "dev.talos.app.Main",
            // class-path wildcard so the launcher sees all libs in /lib
            "--class-path", "*",
            // Include the incubator Vector module in the runtime image...
            "--add-modules", "jdk.incubator.vector",
            // ...and pass it at launch time too
            "--java-options", "--add-modules=jdk.incubator.vector"
        )

        // Optional extras if present
        val resDir = file("src/main/jpackage")
        if (resDir.exists()) {
            args.addAll(listOf("--resource-dir", resDir.absolutePath))
        }
        val iconFile = file("src/main/jpackage/icon.ico")
        if (iconFile.exists()) {
            args.addAll(listOf("--icon", iconFile.absolutePath))
        }

        commandLine(args)
    }
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
        rule {
            limit {
                // Floor: fail the build if instruction coverage drops below 40%
                minimum = "0.40".toBigDecimal()
            }
        }
    }
}

// Wire: `gradle check` now runs coverage verification
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
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
            val qodanaAttributes = meta["attributes"] as? Map<*, *>
            val qodanaVcs = qodanaAttributes?.get("vcs") as? Map<*, *>
            val qodanaSarifIdea = qodanaVcs?.get("sarifIdea") as? Map<*, *>
            val qodanaRevision = qodanaSarifIdea?.get("revisionId")?.toString()?.ifBlank { null }
            val qodanaBranch = qodanaSarifIdea?.get("branch")?.toString()?.ifBlank { null }

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
            var newIssues: Int? = 0
            sarifRuns.forEach { run ->
                if (run is Map<*, *>) {
                    val results = run["results"] as? List<*> ?: emptyList<Any>()
                    results.forEach { raw ->
                        if (raw is Map<*, *>) {
                            when (raw["level"]?.toString()?.lowercase()) {
                                "error" -> sarifError++
                                "warning" -> sarifWarning++
                                "note" -> sarifNote++
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

            val qodanaAvailable = qodanaRoot.exists()
            val metaPresent = metaFile.exists()
            val problemsPresent = problemsFile.exists()
            val sarifPresent = sarifFile.exists()
            val missingRequiredArtifacts = listOfNotNull(
                if (metaPresent) null else "metaInformation.json",
                if (problemsPresent) null else "result-allProblems.json",
                if (sarifPresent) null else "qodana.sarif.json"
            )
            val requiredArtifactStatus = when {
                !qodanaAvailable -> "qodana-results-missing"
                missingRequiredArtifacts.isEmpty() -> "all-required-artifacts-present"
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
                "linter" to meta["linter"],
                "linterVersion" to meta["linterVersion"],
                "totalIssues" to ((meta["total"] as? Number)?.toInt() ?: problems.size),
                "severityCounts" to severityCounts,
                "sarifLevelCounts" to mapOf(
                    "error" to sarifError,
                    "warning" to sarifWarning,
                    "note" to sarifNote
                ),
                "criticalIssues" to if (!qodanaRoot.exists()) null else (severityCounts["CRITICAL"] ?: 0),
                "criticalIssuesStatus" to when {
                    !qodanaRoot.exists() -> "qodana-results-missing"
                    problemsFile.exists() -> "derived-from-problem-severities"
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
                    "jsonScenarioResourcePaths" to allJsonScenarioResources
                ),
                "jsonScenarioCoverage" to mapOf(
                    "executedTestCaseCount" to jsonScenarioBackedExecutedCases,
                    "untaggedExecutedTestCaseCount" to untaggedExecutedTestCases,
                    "executedResourceCount" to executedJsonScenarioResources.size,
                    "resourceCount" to allJsonScenarioResources.size,
                    "resourceTraceabilityStatus" to resourceTraceabilityStatus,
                    "traceabilityScopeStatus" to traceabilityScopeStatus,
                    "executedResources" to executedJsonScenarioResources,
                    "unexecutedResources" to unexecutedJsonScenarioResources,
                    "executions" to jsonScenarioExecutions
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
