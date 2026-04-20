plugins {
    application
    jacoco
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

version = "0.9.0-beta"

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
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
            "Implementation-Vendor" to System.currentTimeMillis().toString(), // Build timestamp
            "Main-Class" to "dev.talos.app.Main"
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
