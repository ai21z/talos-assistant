import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import org.gradle.api.tasks.bundling.Compression

plugins {
    application
    jacoco
}

val talosReportsDir = layout.buildDirectory.dir("reports/talos")
val qodanaCommunityImage = "jetbrains/qodana-jvm-community:2026.1"
val qodanaExpectedLinter = "QDJVM"
val qodanaExpectedLinterVersion = Regex("261(?:\\.[0-9]+)+")
val gitleaksImage = "ghcr.io/gitleaks/gitleaks:v8.30.1"
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
 * etc.) must not wipe the whole packet - it must produce an explicit
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

fun qualityDouble(value: Any?): Double? {
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}

fun qualityBoolean(value: Any?): Boolean {
    return when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }
}

fun readQualitySummary(file: java.io.File, summaryName: String): Map<*, *> {
    if (!file.isFile) {
        throw org.gradle.api.GradleException("Missing quality summary: $summaryName at ${file.path}")
    }
    val parsed = groovy.json.JsonSlurper().parse(file)
    if (parsed !is Map<*, *>) {
        throw org.gradle.api.GradleException("Malformed quality summary: $summaryName must be a JSON object")
    }
    return parsed
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

fun sha256Hex(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

data class QodanaSourceSnapshot(
    val algorithm: String,
    val gitHead: String,
    val trackedFiles: Int,
    val trackedBytes: Long,
    val changedTrackedFiles: Int,
    val diffBytes: Int,
    val diffSha256: String,
    val snapshotSha256: String
) {
    fun asMap(): Map<String, Any> = linkedMapOf(
        "algorithm" to algorithm,
        "gitHead" to gitHead,
        "trackedFiles" to trackedFiles,
        "trackedBytes" to trackedBytes,
        "changedTrackedFiles" to changedTrackedFiles,
        "diffBytes" to diffBytes,
        "diffSha256" to diffSha256,
        "snapshotSha256" to snapshotSha256
    )
}

fun requiredGitBytes(vararg args: String): ByteArray {
    return providers.exec {
        workingDir(projectDir)
        commandLine("git", *args)
    }.standardOutput.asBytes.get()
}

fun splitNulTerminatedGitPaths(raw: ByteArray, label: String): List<ByteArray> {
    if (raw.isEmpty()) return emptyList()
    if (raw.last() != 0.toByte()) {
        throw GradleException("$label is not NUL-terminated")
    }
    val paths = mutableListOf<ByteArray>()
    var start = 0
    raw.indices.forEach { index ->
        if (raw[index] == 0.toByte()) {
            if (index == start) throw GradleException("$label contains an empty path")
            paths += raw.copyOfRange(start, index)
            start = index + 1
        }
    }
    return paths
}

fun decodeStrictUtf8Path(raw: ByteArray, label: String): String {
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw))
            .toString()
    } catch (_: Throwable) {
        throw GradleException("$label contains a path that is not strict UTF-8")
    }
}

fun compareUnsignedBytes(left: ByteArray, right: ByteArray): Int {
    val common = minOf(left.size, right.size)
    for (index in 0 until common) {
        val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}

fun qodanaSourceSnapshotOnce(): QodanaSourceSnapshot {
    val head = String(
        requiredGitBytes("rev-parse", "--verify", "HEAD"),
        StandardCharsets.US_ASCII
    ).trim()
    if (!head.matches(Regex("[0-9a-fA-F]{40}"))) {
        throw GradleException("Qodana source snapshot requires an exact Git HEAD")
    }

    val stagedDiff = requiredGitBytes(
        "diff", "--cached", "--binary", "--full-index", "--no-ext-diff",
        "--no-textconv", "--no-renames", "HEAD", "--"
    )
    if (stagedDiff.isNotEmpty()) {
        throw GradleException("Qodana source snapshot rejects staged changes")
    }
    val untracked = requiredGitBytes("ls-files", "--others", "--exclude-standard", "-z")
    if (untracked.isNotEmpty()) {
        throw GradleException("Qodana source snapshot rejects untracked files")
    }

    val diff = requiredGitBytes(
        "diff", "--binary", "--full-index", "--no-ext-diff",
        "--no-textconv", "--no-renames", "HEAD", "--"
    )
    val changedTrackedNameBytes = requiredGitBytes(
        "diff", "--name-only", "-z", "--no-ext-diff", "--no-renames", "HEAD", "--"
    )
    val changedTrackedFiles = splitNulTerminatedGitPaths(
        changedTrackedNameBytes,
        "Qodana changed-file list"
    ).size
    val trackedPathBytes = splitNulTerminatedGitPaths(
        requiredGitBytes("ls-files", "-z", "--"),
        "Qodana tracked-file list"
    ).sortedWith(Comparator { left, right -> compareUnsignedBytes(left, right) })
    trackedPathBytes.zipWithNext().forEach { (previous, current) ->
        if (compareUnsignedBytes(previous, current) == 0) {
            throw GradleException("Qodana tracked-file list contains a duplicate path")
        }
    }
    val algorithm = "talos-qodana-current-source-v2"
    val digest = MessageDigest.getInstance("SHA-256")
    fun updateLength(value: Long) {
        digest.update(ByteBuffer.allocate(8).putLong(value).array())
    }
    fun updateField(bytes: ByteArray) {
        updateLength(bytes.size.toLong())
        digest.update(bytes)
    }
    updateField(algorithm.toByteArray(StandardCharsets.US_ASCII))
    updateField(head.lowercase().toByteArray(StandardCharsets.US_ASCII))
    updateLength(trackedPathBytes.size.toLong())

    val canonicalProjectPath = projectDir.canonicalFile.toPath().toAbsolutePath().normalize()
    var trackedBytes = 0L
    trackedPathBytes.forEach { rawPath ->
        val gitPath = decodeStrictUtf8Path(rawPath, "Qodana tracked-file list")
        if (gitPath.startsWith('/') || gitPath.startsWith('\\') ||
            gitPath.contains('\\') ||
            gitPath.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw GradleException("Qodana tracked-file path is not a canonical repository-relative path")
        }
        if (!gitPath.toByteArray(StandardCharsets.UTF_8).contentEquals(rawPath)) {
            throw GradleException("Qodana tracked-file path does not round-trip through strict UTF-8")
        }
        val path = canonicalProjectPath.resolve(gitPath).normalize()
        requireNoFollowOrdinaryComponents(canonicalProjectPath, path, "Qodana tracked source")
        val attributes = readNoFollowAttributesOrNull(path, "Qodana tracked source")
            ?: throw GradleException("Qodana tracked source is missing: $gitPath")
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.isOther) {
            throw GradleException("Qodana tracked source is not an ordinary regular file: $gitPath")
        }
        updateField(rawPath)
        updateLength(attributes.size())
        var fileBytesRead = 0L
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                fileBytesRead = Math.addExact(fileBytesRead, read.toLong())
            }
        }
        if (fileBytesRead != attributes.size()) {
            throw GradleException("Qodana tracked source changed while it was read: $gitPath")
        }
        trackedBytes = Math.addExact(trackedBytes, fileBytesRead)
    }
    val snapshotHash = digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
    return QodanaSourceSnapshot(
        algorithm,
        head.lowercase(),
        trackedPathBytes.size,
        trackedBytes,
        changedTrackedFiles,
        diff.size,
        sha256Hex(diff),
        snapshotHash
    )
}

fun qodanaSourceSnapshot(): QodanaSourceSnapshot {
    val first = qodanaSourceSnapshotOnce()
    val second = qodanaSourceSnapshotOnce()
    if (first != second) {
        throw GradleException("Qodana source bytes changed during snapshot authentication")
    }
    return first
}

fun readNoFollowAttributesOrNull(path: Path, label: String): BasicFileAttributes? {
    return try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    } catch (failure: Exception) {
        throw GradleException("$label state could not be authenticated: $path", failure)
    }
}

fun requireNoFollowOrdinaryComponents(
    trustedBase: Path,
    requestedTarget: Path,
    label: String
) {
    val base = trustedBase.toAbsolutePath().normalize()
    val target = requestedTarget.toAbsolutePath().normalize()
    if (!target.startsWith(base)) {
        throw GradleException("$label must remain below $base")
    }
    var current = base
    val relative = base.relativize(target)
    val components = mutableListOf<Path>()
    components.add(base)
    relative.forEach { component ->
        current = current.resolve(component)
        components.add(current)
    }
    components.forEachIndexed { index, component ->
        val attributes = readNoFollowAttributesOrNull(component, label)
        if (attributes != null) {
            if (attributes.isSymbolicLink || attributes.isOther) {
                throw GradleException("$label must not traverse a symbolic link, junction, or reparse point: $component")
            }
            if (index < components.lastIndex && !attributes.isDirectory) {
                throw GradleException("$label has a non-directory ancestor: $component")
            }
        }
    }
}

fun requireNoFollowOrdinaryTree(requestedRoot: Path, label: String) {
    val root = requestedRoot.toAbsolutePath().normalize()
    if (readNoFollowAttributesOrNull(root, label) == null) return
    Files.walk(root).use { paths ->
        paths.forEach { path ->
            val attributes = readNoFollowAttributesOrNull(path, label)
                ?: throw GradleException("$label changed while it was inspected: $path")
            if (attributes.isSymbolicLink || attributes.isOther) {
                throw GradleException("$label contains a symbolic link, junction, or reparse point: $path")
            }
            if (!attributes.isDirectory && !attributes.isRegularFile) {
                throw GradleException("$label contains a non-ordinary filesystem object: $path")
            }
        }
    }
}

fun deleteNoFollowOrdinaryTree(
    trustedBase: Path,
    requestedRoot: Path,
    label: String,
    maxEntries: Int = 1_000_000
) {
    val base = trustedBase.toAbsolutePath().normalize()
    val root = requestedRoot.toAbsolutePath().normalize()
    requireNoFollowOrdinaryComponents(base, root, label)
    if (readNoFollowAttributesOrNull(root, label) == null) return

    val entries = mutableListOf<Path>()
    try {
        Files.walk(root).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                if (entries.size >= maxEntries) {
                    throw GradleException("$label exceeds the bounded cleanup entry limit")
                }
                val entry = iterator.next().toAbsolutePath().normalize()
                requireNoFollowOrdinaryComponents(base, entry, label)
                val attributes = readNoFollowAttributesOrNull(entry, label)
                    ?: throw GradleException("$label changed while it was enumerated: $entry")
                if (attributes.isSymbolicLink || attributes.isOther ||
                    (!attributes.isDirectory && !attributes.isRegularFile)) {
                    throw GradleException("$label contains a non-ordinary or reparse entry: $entry")
                }
                entries.add(entry)
            }
        }
    } catch (failure: GradleException) {
        throw failure
    } catch (failure: Exception) {
        throw GradleException("$label could not be enumerated safely", failure)
    }

    entries.sortedWith(
        compareByDescending<Path> { it.nameCount }.thenByDescending { it.toString() }
    ).forEach { entry ->
        requireNoFollowOrdinaryComponents(base, entry, label)
        val attributes = readNoFollowAttributesOrNull(entry, label)
            ?: throw GradleException("$label changed before deletion: $entry")
        if (attributes.isSymbolicLink || attributes.isOther ||
            (!attributes.isDirectory && !attributes.isRegularFile)) {
            throw GradleException("$label contains a non-ordinary or reparse entry before deletion: $entry")
        }
        try {
            Files.delete(entry)
        } catch (failure: Exception) {
            throw GradleException("$label could not be deleted safely: $entry", failure)
        }
    }
}

fun requireNoTrackedGitPathsIntersect(cleanupTargets: List<java.io.File>) {
    val base = projectDir.canonicalFile.toPath().toAbsolutePath().normalize()
    val normalizedTargets = cleanupTargets.map { target ->
        val path = target.toPath().toAbsolutePath().normalize()
        if (!path.startsWith(base)) {
            throw GradleException("Qodana cleanup target must remain below the project: $path")
        }
        path
    }
    splitNulTerminatedGitPaths(
        requiredGitBytes("ls-files", "-z", "--"),
        "Qodana tracked-file list"
    ).forEach { rawPath ->
        val gitPath = decodeStrictUtf8Path(rawPath, "Qodana tracked-file list")
        if (gitPath.startsWith('/') || gitPath.startsWith('\\') ||
            gitPath.contains('\\') ||
            gitPath.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            !gitPath.toByteArray(StandardCharsets.UTF_8).contentEquals(rawPath)) {
            throw GradleException("Qodana tracked-file path is not a canonical repository-relative path")
        }
        val trackedPath = base.resolve(gitPath).normalize()
        val intersectingTarget = normalizedTargets.firstOrNull { target ->
            trackedPath == target || trackedPath.startsWith(target) || target.startsWith(trackedPath)
        }
        if (intersectingTarget != null) {
            throw GradleException(
                "Qodana cleanup target intersects tracked Git path `$gitPath`: $intersectingTarget"
            )
        }
    }
}

data class QodanaBoundArtifactSpec(val relativePath: String)

data class QodanaBoundArtifact(
    val spec: QodanaBoundArtifactSpec,
    val bytes: ByteArray
) {
    fun asMap(): Map<String, Any> = linkedMapOf(
        "path" to spec.relativePath,
        "bytes" to bytes.size.toLong(),
        "sha256" to sha256Hex(bytes)
    )
}

val qodanaBoundArtifactSpecs = listOf(
    QodanaBoundArtifactSpec("report/results/metaInformation.json"),
    QodanaBoundArtifactSpec("report/results/result-allProblems.json"),
    QodanaBoundArtifactSpec("report/results/qodana.sarif.json"),
    QodanaBoundArtifactSpec("results/log/qodana_directories_inspection_groups.csv")
)

fun readQodanaBoundArtifacts(
    qodanaRoot: java.io.File,
    canonicalProjectDir: java.io.File
): List<QodanaBoundArtifact> {
    val projectPath = canonicalProjectDir.toPath().toAbsolutePath().normalize()
    val rootPath = qodanaRoot.toPath().toAbsolutePath().normalize()
    requireNoFollowOrdinaryComponents(projectPath, rootPath, "Qodana artifact root")
    return qodanaBoundArtifactSpecs.map { spec ->
        val artifactPath = rootPath.resolve(spec.relativePath).normalize()
        requireNoFollowOrdinaryComponents(projectPath, artifactPath, "Qodana bound artifact")
        val attributes = readNoFollowAttributesOrNull(artifactPath, "Qodana bound artifact")
            ?: throw GradleException("Qodana bound artifact is missing: ${spec.relativePath}")
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.isOther) {
            throw GradleException("Qodana bound artifact is not an ordinary regular file: ${spec.relativePath}")
        }
        val bytes = Files.readAllBytes(artifactPath)
        if (bytes.size.toLong() != attributes.size()) {
            throw GradleException("Qodana bound artifact changed while it was read: ${spec.relativePath}")
        }
        QodanaBoundArtifact(spec, bytes)
    }
}

class StrictJsonSyntaxValidator(
    private val text: String,
    private val label: String,
    private val maxDepth: Int = 256,
    private val maxValues: Int = 5_000_000
) {
    private var index = 0
    private var values = 0

    fun validate() {
        skipWhitespace()
        parseValue(0)
        skipWhitespace()
        failIf(index != text.length, "has trailing content")
    }

    private fun parseValue(depth: Int) {
        failIf(depth > maxDepth, "exceeds the maximum nesting depth")
        values = Math.addExact(values, 1)
        failIf(values > maxValues, "exceeds the maximum value count")
        failIf(index >= text.length, "ends before a value")
        when (text[index]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> parseString()
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> fail("contains an invalid value")
        }
    }

    private fun parseObject(depth: Int) {
        index++
        skipWhitespace()
        if (take('}')) return
        val names = mutableSetOf<String>()
        while (true) {
            failIf(index >= text.length || text[index] != '"', "contains a non-string object key")
            val name = parseString()
            failIf(!names.add(name), "contains duplicate object key `$name`")
            skipWhitespace()
            requireChar(':')
            skipWhitespace()
            parseValue(depth)
            skipWhitespace()
            if (take('}')) return
            requireChar(',')
            skipWhitespace()
        }
    }

    private fun parseArray(depth: Int) {
        index++
        skipWhitespace()
        if (take(']')) return
        while (true) {
            parseValue(depth)
            skipWhitespace()
            if (take(']')) return
            requireChar(',')
            skipWhitespace()
        }
    }

    private fun parseString(): String {
        requireChar('"')
        val result = StringBuilder()
        while (index < text.length) {
            val value = text[index++]
            when {
                value == '"' -> return result.toString()
                value == '\\' -> {
                    failIf(index >= text.length, "ends inside a string escape")
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> appendEscapedCodePoint(result)
                        else -> fail("contains an invalid string escape")
                    }
                }
                value.code < 0x20 -> fail("contains an unescaped control character")
                Character.isHighSurrogate(value) -> {
                    failIf(index >= text.length || !Character.isLowSurrogate(text[index]), "contains an unpaired surrogate")
                    result.append(value).append(text[index++])
                }
                Character.isLowSurrogate(value) -> fail("contains an unpaired surrogate")
                else -> result.append(value)
            }
        }
        fail("ends inside a string")
    }

    private fun appendEscapedCodePoint(result: StringBuilder) {
        val first = parseHexCodeUnit()
        when {
            Character.isHighSurrogate(first) -> {
                failIf(index + 1 >= text.length || text[index] != '\\' || text[index + 1] != 'u', "contains an unpaired escaped surrogate")
                index += 2
                val second = parseHexCodeUnit()
                failIf(!Character.isLowSurrogate(second), "contains an unpaired escaped surrogate")
                result.append(first).append(second)
            }
            Character.isLowSurrogate(first) -> fail("contains an unpaired escaped surrogate")
            else -> result.append(first)
        }
    }

    private fun parseHexCodeUnit(): Char {
        failIf(index + 4 > text.length, "ends inside a Unicode escape")
        var value = 0
        repeat(4) {
            val character = text[index++]
            val digit = when (character) {
                in '0'..'9' -> character - '0'
                in 'a'..'f' -> character - 'a' + 10
                in 'A'..'F' -> character - 'A' + 10
                else -> -1
            }
            failIf(digit < 0, "contains an invalid Unicode escape")
            value = value * 16 + digit
        }
        return value.toChar()
    }

    private fun parseNumber() {
        take('-')
        failIf(index >= text.length, "ends inside a number")
        if (take('0')) {
            failIf(index < text.length && text[index] in '0'..'9', "contains a number with a leading zero")
        } else {
            failIf(index >= text.length || text[index] !in '1'..'9', "contains an invalid number")
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (take('.')) {
            failIf(index >= text.length || text[index] !in '0'..'9', "contains an invalid fraction")
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            failIf(index >= text.length || text[index] !in '0'..'9', "contains an invalid exponent")
            while (index < text.length && text[index] in '0'..'9') index++
        }
    }

    private fun parseLiteral(expected: String) {
        failIf(!text.startsWith(expected, index), "contains an invalid literal")
        index += expected.length
    }

    private fun skipWhitespace() {
        while (index < text.length) {
            when (text[index]) {
                ' ', '\t', '\r', '\n' -> index++
                else -> return
            }
        }
    }

    private fun take(expected: Char): Boolean {
        if (index < text.length && text[index] == expected) {
            index++
            return true
        }
        return false
    }

    private fun requireChar(expected: Char) {
        failIf(!take(expected), "expected `$expected`")
    }

    private fun failIf(condition: Boolean, message: String) {
        if (condition) fail(message)
    }

    private fun fail(message: String): Nothing {
        throw GradleException("$label $message at character $index")
    }
}

fun parseStrictJson(bytes: ByteArray, label: String, maxBytes: Int): Any? {
    if (bytes.size > maxBytes) {
        throw GradleException("$label exceeds the bounded JSON byte limit")
    }
    val text = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw GradleException("$label is not strict UTF-8")
    }
    StrictJsonSyntaxValidator(text, label).validate()
    return groovy.json.JsonSlurper().parseText(text)
}

fun exactJsonNonNegativeInt(raw: Any?): Int? {
    val text = (raw as? Number)?.toString() ?: return null
    if (!text.matches(Regex("0|[1-9][0-9]*"))) return null
    return text.toIntOrNull()
}

fun exactJsonNonNegativeLong(raw: Any?): Long? {
    val text = (raw as? Number)?.toString() ?: return null
    if (!text.matches(Regex("0|[1-9][0-9]*"))) return null
    return text.toLongOrNull()
}

fun qodanaSourceSnapshotFromMap(raw: Any?): QodanaSourceSnapshot? {
    val candidate = raw as? Map<*, *> ?: return null
    val algorithm = candidate["algorithm"] as? String ?: return null
    val gitHead = candidate["gitHead"] as? String ?: return null
    val trackedFiles = exactJsonNonNegativeInt(candidate["trackedFiles"]) ?: return null
    val trackedBytes = exactJsonNonNegativeLong(candidate["trackedBytes"]) ?: return null
    val changedTrackedFiles = exactJsonNonNegativeInt(candidate["changedTrackedFiles"]) ?: return null
    val diffBytes = exactJsonNonNegativeInt(candidate["diffBytes"]) ?: return null
    val diffSha256 = candidate["diffSha256"] as? String ?: return null
    val snapshotSha256 = candidate["snapshotSha256"] as? String ?: return null
    if (candidate.keys != setOf(
            "algorithm", "gitHead", "trackedFiles", "trackedBytes", "changedTrackedFiles",
            "diffBytes", "diffSha256", "snapshotSha256"
        ) ||
        algorithm != "talos-qodana-current-source-v2" ||
        !gitHead.matches(Regex("[0-9a-f]{40}")) ||
        trackedFiles <= 0 || changedTrackedFiles > trackedFiles ||
        !diffSha256.matches(Regex("[0-9a-f]{64}")) ||
        !snapshotSha256.matches(Regex("[0-9a-f]{64}"))) {
        return null
    }
    return QodanaSourceSnapshot(
        algorithm, gitHead, trackedFiles, trackedBytes, changedTrackedFiles,
        diffBytes, diffSha256, snapshotSha256
    )
}

fun qodanaObservedScope(
    telemetryBytes: ByteArray,
    canonicalProjectDir: java.io.File
): Map<String, Any?> {
    val statusObserved = "inspection-activity-observed"
    val statusNotObserved = "no-inspection-activity-observed"
    val telemetryText = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(telemetryBytes))
            .toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw GradleException("Qodana scope telemetry is not strict UTF-8")
    }
    val header = "Directory;Absolute Time spent on Inspection Group 'all', s;Percent of Time spent on Inspection Group 'all';Inspections Problem Count in Group 'all';Times Inspection from Group 'all' was Performed"
    val rawLines = telemetryText.split('\n').toMutableList()
    if (rawLines.lastOrNull().isNullOrEmpty()) rawLines.removeAt(rawLines.lastIndex)
    val rows = rawLines.map { line ->
        val withoutCr = line.removeSuffix("\r")
        if (withoutCr.contains('\r')) throw GradleException("Qodana scope telemetry contains an unexpected CR")
        withoutCr
    }
    if (rows.firstOrNull() != header) {
        throw GradleException("Qodana scope telemetry header does not match the five-column schema")
    }
    data class ScopeRow(val directory: Path, val performed: Long)
    val seenDirectories = mutableSetOf<String>()
    val parsedRows = rows.drop(1).mapIndexed { index, row ->
        if (row.isEmpty()) throw GradleException("Qodana scope telemetry contains an empty data row")
        val fields = row.split(';')
        if (fields.size != 5 || fields[0].isEmpty() ||
            !fields[1].matches(Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) ||
            !fields[2].matches(Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?%")) ||
            !fields[3].matches(Regex("0|[1-9][0-9]*")) ||
            !fields[4].matches(Regex("0|[1-9][0-9]*"))) {
            throw GradleException("Qodana scope telemetry row ${index + 2} does not match the five-column schema")
        }
        val directoryText = fields[0].replace('\\', '/')
        val directoryPath = try {
            Path.of(directoryText)
        } catch (_: Throwable) {
            throw GradleException("Qodana scope telemetry contains an invalid directory path")
        }
        val normalizedDirectory = directoryPath.normalize()
        val normalizedText = normalizedDirectory.toString().replace('\\', '/')
        if (!directoryPath.isAbsolute || directoryText != normalizedText) {
            throw GradleException("Qodana scope telemetry directory must be a canonical absolute path")
        }
        if (!seenDirectories.add(normalizedText)) {
            throw GradleException("Qodana scope telemetry contains a duplicate directory row")
        }
        ScopeRow(normalizedDirectory, fields[4].toLongOrNull()
            ?: throw GradleException("Qodana scope telemetry performed count exceeds the supported range"))
    }
    fun observed(relativeRoot: String): Boolean {
        val expectedRoot = canonicalProjectDir.toPath().toAbsolutePath().normalize()
            .resolve(relativeRoot).normalize()
        return parsedRows.any { row ->
            row.performed > 0L &&
                (row.directory == expectedRoot || row.directory.startsWith(expectedRoot))
        }
    }
    return linkedMapOf(
        "telemetryStatus" to "telemetry-present",
        "telemetryBytes" to telemetryBytes.size.toLong(),
        "telemetrySha256" to sha256Hex(telemetryBytes),
        "main" to if (observed("src/main")) statusObserved else statusNotObserved,
        "unitTest" to if (observed("src/test")) statusObserved else statusNotObserved,
        "e2eTest" to if (observed("src/e2eTest")) statusObserved else statusNotObserved
    )
}

fun qodanaObservedScope(
    telemetryFile: java.io.File,
    canonicalProjectDir: java.io.File
): Map<String, Any?> {
    if (!telemetryFile.isFile) {
        return linkedMapOf(
            "telemetryStatus" to "telemetry-missing",
            "telemetryBytes" to null,
            "telemetrySha256" to null,
            "main" to "no-inspection-activity-observed",
            "unitTest" to "no-inspection-activity-observed",
            "e2eTest" to "no-inspection-activity-observed"
        )
    }
    return qodanaObservedScope(Files.readAllBytes(telemetryFile.toPath()), canonicalProjectDir)
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
    if (normalized.contains("pending release notes", ignoreCase = true)) {
        throw GradleException("CHANGELOG.md contains placeholder text: pending release notes")
    }

    val bracketedHeadings = Regex("(?m)^[ ]{0,3}##[ \\t]+\\[[^\\r\\n]*$")
        .findAll(normalized)
        .toList()
    val headings = Regex(
        "(?m)^## \\[([^\\]\\r\\n]+)\\](?: - (\\d{4}-\\d{2}-\\d{2}))?[ \\t]*$"
    )
        .findAll(normalized)
        .toList()
    if (headings.size != bracketedHeadings.size) {
        throw GradleException("CHANGELOG.md contains a malformed bracketed top-level release heading")
    }

    var unreleasedCount = 0
    var unreleasedStart = -1
    val releasedVersions = mutableSetOf<String>()
    headings.forEach { heading ->
        val name = heading.groupValues[1]
        val date = heading.groupValues[2]
        if (name == "Unreleased") {
            if (date.isNotEmpty()) {
                throw GradleException("CHANGELOG.md must not attach a release date to ## [Unreleased]")
            }
            unreleasedCount++
            if (unreleasedStart < 0) unreleasedStart = heading.range.first
        } else {
            if (!Regex("^(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)$")
                    .matches(name)) {
                throw GradleException(
                    "CHANGELOG.md released-version headings must use numeric MAJOR.MINOR.PATCH identities; found '$name'"
                )
            }
            if (date.isEmpty()) {
                throw GradleException("Released CHANGELOG.md version $name must include an ISO release date")
            }
            val parsedDate = try {
                LocalDate.parse(date)
            } catch (_: DateTimeParseException) {
                throw GradleException(
                    "Released CHANGELOG.md version $name must include a valid ISO release date; found '$date'"
                )
            }
            if (parsedDate.year !in 1..9999) {
                throw GradleException(
                    "Released CHANGELOG.md version $name must include a valid ISO release date; found '$date'"
                )
            }
            if (!releasedVersions.add(name)) {
                throw GradleException("CHANGELOG.md contains a duplicate released-version heading: '$name'")
            }
        }
    }

    if (unreleasedCount == 0) {
        throw GradleException("CHANGELOG.md must contain a top-level ## [Unreleased] section before released versions")
    }
    if (unreleasedCount != 1) {
        throw GradleException(
            "CHANGELOG.md must contain exactly one top-level ## [Unreleased] section; found $unreleasedCount"
        )
    }
    val beforeUnreleased = normalized.substring(0, unreleasedStart)
    if (!Regex("(?s)\\A# Changelog[ \\t]*\\n[\\s]*\\z").matches(beforeUnreleased)) {
        throw GradleException("CHANGELOG.md must keep ## [Unreleased] as the first section after # Changelog")
    }

    val topReleased = headings.firstOrNull { it.groupValues[1] != "Unreleased" }
        ?: throw GradleException("CHANGELOG.md must contain at least one released version section")
    val topReleasedVersion = topReleased.groupValues[1]
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

tasks.register<JavaExec>("modelQualificationBankHash") {
    description = "Prints the frozen Talos model-qualification scenario and fixture bank SHA-256."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    args("bank-hash")
}

tasks.register<JavaExec>("printModelQualificationLaunchEnvironmentIdentity") {
    description = "Prints the provider environment and endpoint-observer identities for protocol v6."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    args("launch-environment-hash")
}

tasks.register<JavaExec>("validateModelQualificationEvidence") {
    description = "Validates a local model-qualification packet before scoring."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        listOf("validate", "--root", root)
    })
}

tasks.register<JavaExec>("validateModelQualificationCalibration") {
    description = "Validates frozen model calibration metadata before the first trial and requires zero run results."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        listOf("preflight", "--root", root)
    })
}

tasks.register<JavaExec>("assertWritableModelQualificationCampaign") {
    description = "Rejects any noncanonical or non-v6 qualification campaign before packet mutation."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        listOf("assert-writable-campaign", "--root", root)
    })
}

tasks.register<JavaExec>("finalizeModelQualificationRequirements") {
    description = "Writes immutable mechanical requirement results using the canonical qualification evaluator."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        val scenario = providers.gradleProperty("qualificationScenario").orElse("").get()
        val runRoot = providers.gradleProperty("qualificationRunRoot").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        if (scenario.isBlank()) {
            throw GradleException("-PqualificationScenario=<scenario id> is required")
        }
        if (runRoot.isBlank()) {
            throw GradleException("-PqualificationRunRoot=<local run directory> is required")
        }
        listOf(
            "requirements",
            "--root", root,
            "--scenario", scenario,
            "--run", runRoot,
        )
    })
}

tasks.register<JavaExec>("finalizeModelQualificationApprovals") {
    description = "Writes immutable ordered approval evidence from the qualification PTY capture."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        val scenario = providers.gradleProperty("qualificationScenario").orElse("").get()
        val runRoot = providers.gradleProperty("qualificationRunRoot").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        if (scenario.isBlank()) {
            throw GradleException("-PqualificationScenario=<scenario id> is required")
        }
        if (runRoot.isBlank()) {
            throw GradleException("-PqualificationRunRoot=<local run directory> is required")
        }
        listOf(
            "approvals",
            "--root", root,
            "--scenario", scenario,
            "--run", runRoot,
        )
    })
}

tasks.register<JavaExec>("finalizeModelQualificationProviderEvidence") {
    description = "Writes immutable provider prompt and model runtime evidence for one qualification trial."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        val scenario = providers.gradleProperty("qualificationScenario").orElse("").get()
        val runRoot = providers.gradleProperty("qualificationRunRoot").orElse("").get()
        val serverPort = providers.gradleProperty("qualificationServerPort").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        if (scenario.isBlank()) {
            throw GradleException("-PqualificationScenario=<scenario id> is required")
        }
        if (runRoot.isBlank()) {
            throw GradleException("-PqualificationRunRoot=<local run directory> is required")
        }
        if (serverPort.toIntOrNull() !in 1..65535) {
            throw GradleException("-PqualificationServerPort=<1..65535> is required")
        }
        listOf(
            "provider-evidence",
            "--root", root,
            "--scenario", scenario,
            "--run", runRoot,
            "--server-port", serverPort,
        )
    })
}

tasks.register<JavaExec>("finalizeModelQualificationTraceEvidence") {
    description = "Publishes the committed run's exact /last trace artifact as immutable qualification evidence."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        val scenario = providers.gradleProperty("qualificationScenario").orElse("").get()
        val runRoot = providers.gradleProperty("qualificationRunRoot").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        if (scenario.isBlank()) {
            throw GradleException("-PqualificationScenario=<scenario id> is required")
        }
        if (runRoot.isBlank()) {
            throw GradleException("-PqualificationRunRoot=<local run directory> is required")
        }
        listOf(
            "trace-evidence",
            "--root", root,
            "--scenario", scenario,
            "--run", runRoot,
        )
    })
}

tasks.register<JavaExec>("completeModelQualificationCampaign") {
    description = "Commits immutable protocol-v6 campaign completion authority."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        val bank = providers.gradleProperty("qualificationBank").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        if (bank.lowercase() !in setOf("admission", "full")) {
            throw GradleException("-PqualificationBank=admission|full is required")
        }
        listOf("complete-campaign", "--root", root, "--bank", bank)
    })
}

tasks.register<JavaExec>("reconcileModelQualificationRunPublication") {
    description = "Verifies or recovers the mechanically selected protocol-v6 run transaction."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        val scenario = providers.gradleProperty("qualificationScenario").orElse("").get()
        val runRoot = providers.gradleProperty("qualificationRunRoot").orElse("").get()
        val serverPort = providers.gradleProperty("qualificationServerPort").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        if (scenario.isBlank()) {
            throw GradleException("-PqualificationScenario=<scenario id> is required")
        }
        if (runRoot.isBlank()) {
            throw GradleException("-PqualificationRunRoot=<local run directory> is required")
        }
        val base = listOf(
            "reconcile-run-publication",
            "--root", root,
            "--scenario", scenario,
            "--run", runRoot,
        )
        if (serverPort.isBlank()) {
            base
        } else {
            if (serverPort.toIntOrNull() !in 1..65535) {
                throw GradleException("-PqualificationServerPort must be 1..65535 when supplied")
            }
            base + listOf("--server-port", serverPort)
        }
    })
}

tasks.register<JavaExec>("scoreModelQualificationEvidence") {
    description = "Validates and mechanically scores a local admission or full qualification packet."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.ModelQualificationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        val bank = providers.gradleProperty("qualificationBank").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        if (bank.lowercase() !in setOf("admission", "full")) {
            throw GradleException("-PqualificationBank=admission|full is required")
        }
        listOf("score", "--root", root, "--bank", bank)
    })
}

tasks.register<JavaExec>("captureModelQualificationPtyTrial") {
    description = "Captures one frozen Windows true-PTY model-qualification trial."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.QualificationPtyCaptureMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        val scenario = providers.gradleProperty("qualificationScenario").orElse("").get()
        val repetition = providers.gradleProperty("qualificationRepetition").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        if (scenario.isBlank()) {
            throw GradleException("-PqualificationScenario=<scenario id> is required")
        }
        if (repetition.toIntOrNull() == null || repetition.toInt() < 1) {
            throw GradleException("-PqualificationRepetition=<positive integer> is required")
        }
        listOf(root, scenario, repetition)
    })
}

tasks.register<JavaExec>("checkModelQualificationCampaignContinuation") {
    description = "Stops a model-qualification batch after invalid evidence or a hard trust failure."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.QualificationCampaignContinuationMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        val scenario = providers.gradleProperty("qualificationScenario").orElse("").get()
        val repetition = providers.gradleProperty("qualificationRepetition").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        if (scenario.isBlank()) {
            throw GradleException("-PqualificationScenario=<scenario id> is required")
        }
        if (repetition.toIntOrNull() == null || repetition.toInt() < 1) {
            throw GradleException("-PqualificationRepetition=<positive integer> is required")
        }
        listOf(root, scenario, repetition)
    })
}

val finalizeModelQualificationResult by tasks.registering(JavaExec::class) {
    description = "Publishes Java-derived protocol-v6 causal, canary, and result artifacts."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.QualificationCausalFinalizerMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        val root = providers.gradleProperty("qualificationRoot").orElse("").get()
        val run = providers.gradleProperty("qualificationRunRoot").orElse("").get()
        val scenario = providers.gradleProperty("qualificationScenario").orElse("").get()
        if (root.isBlank()) {
            throw GradleException("-PqualificationRoot=<local packet directory> is required")
        }
        if (run.isBlank()) {
            throw GradleException("-PqualificationRunRoot=<local run directory> is required")
        }
        if (scenario.isBlank()) {
            throw GradleException("-PqualificationScenario=<scenario id> is required")
        }
        listOf(root, run, scenario)
    })
}

tasks.register<JavaExec>("printModelQualificationFinalizerIdentity") {
    description = "Prints the exact compiled protocol-v6 Java finalizer SHA-256."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.QualificationExecutionIdentityMain")
    classpath = e2eTestSourceSet.runtimeClasspath
}

tasks.register<JavaExec>("printModelQualificationExecutionInputIdentity") {
    description = "Prints path-free finalizer and classpath values for the frozen protocol-v6 harness input specification."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.QualificationExecutionInputIdentityMain")
    classpath = e2eTestSourceSet.runtimeClasspath
}

tasks.register<JavaExec>("writeModelQualificationExecutionIdentity") {
    description = "Creates exact protocol-v6 harness and runtime identity manifests for a pre-trial packet."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.QualificationExecutionIdentityWriterMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        fun requiredProperty(name: String): String {
            val value = providers.gradleProperty(name).orElse("").get()
            if (value.isBlank()) throw GradleException("-P$name=<absolute path> is required")
            val requested = File(value)
            if (!requested.isAbsolute) {
                throw GradleException("-P$name must be an explicit absolute path")
            }
            return requested.absolutePath
        }
        val currentCommit = packagedGitHead.get()
        if (!currentCommit.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException("current Git commit is unavailable; execution identity was not written")
        }
        if (packagedGitDirty.get()) {
            throw GradleException("tracked worktree is dirty; execution identity was not written")
        }
        listOf(
            "--root", requiredProperty("qualificationRoot"),
            "--repository-root", rootProject.projectDir.absolutePath,
            "--built-talos", requiredProperty("qualificationBuiltTalosArtifact"),
            "--installed-talos", requiredProperty("qualificationInstalledTalosArtifact"),
            "--model-artifact", requiredProperty("qualificationModelArtifact"),
            "--engine-executable", requiredProperty("qualificationEngineExecutable"),
            "--harness-inputs", requiredProperty("qualificationHarnessInputSpecification"),
            "--current-commit", currentCommit,
            "--current-version", project.version.toString(),
        )
    })
}

tasks.register<JavaExec>("verifyModelQualificationExecutionIdentity") {
    description = "Read-only verification of an existing protocol-v6 execution identity chain and its live frozen inputs."
    group = "verification"
    dependsOn("e2eTestClasses")
    mainClass.set("dev.talos.qualification.QualificationExecutionIdentityVerifierMain")
    classpath = e2eTestSourceSet.runtimeClasspath
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        fun requiredProperty(name: String): String {
            val value = providers.gradleProperty(name).orElse("").get()
            if (value.isBlank()) throw GradleException("-P$name=<absolute path> is required")
            val requested = File(value)
            if (!requested.isAbsolute) {
                throw GradleException("-P$name must be an explicit absolute path")
            }
            return requested.absolutePath
        }
        val currentCommit = packagedGitHead.get()
        if (!currentCommit.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException("current Git commit is unavailable; execution identity was not verified")
        }
        if (packagedGitDirty.get()) {
            throw GradleException("tracked worktree is dirty; execution identity was not verified")
        }
        listOf(
            "--root", requiredProperty("qualificationRoot"),
            "--repository-root", rootProject.projectDir.absolutePath,
            "--built-talos", requiredProperty("qualificationBuiltTalosArtifact"),
            "--installed-talos", requiredProperty("qualificationInstalledTalosArtifact"),
            "--model-artifact", requiredProperty("qualificationModelArtifact"),
            "--engine-executable", requiredProperty("qualificationEngineExecutable"),
            "--current-commit", currentCommit,
            "--current-version", project.version.toString(),
        )
    })
}

tasks.register("finalizeModelQualificationCausality") {
    description = "Deprecated safe alias for finalizeModelQualificationResult."
    group = "verification"
    dependsOn(finalizeModelQualificationResult)
}

val candidateTest by tasks.registering(Test::class) {
    description = "Runs the candidate unit-test lane and preserves results even when tests fail."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    ignoreFailures = true
    filter {
        excludeTestsMatching("dev.talos.architecture.intelligence.*")
    }
    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/candidateTest/binary"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/candidateTest"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/candidateTest"))
    shouldRunAfter(tasks.test)
}

tasks.test {
    dependsOn(tasks.jar)
    filter {
        excludeTestsMatching("dev.talos.architecture.intelligence.*")
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

val talosRuntimeJvmOptions = listOf(
    "-Dfile.encoding=UTF-8",
    // T880: on Java 18+ System.out/err use stdout.encoding/stderr.encoding, which
    // default to the Windows console code page (e.g. cp1252) and replace the
    // interactive lane glyphs (bullet, arrow) with '?'. Pin them to UTF-8 so a
    // Unicode-capable terminal renders them; the ASCII fallback still covers
    // non-Unicode/redirected output.
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
    // T928: JLine 3.30+ and Lucene 10 use Java FFM/Panama internals on
    // Java 21+. Allow unnamed-module native access so normal Linux/WSL
    // status and REPL startup output is not prefixed by JVM warnings.
    "--enable-native-access=ALL-UNNAMED",
    "-XX:+UseZGC"
)

application {
    mainClass.set("dev.talos.app.Main")
    applicationDefaultJvmArgs = talosRuntimeJvmOptions
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

val packagedGitHead = providers.provider {
    gitOutput("rev-parse", "HEAD")
        ?.lowercase()
        ?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
        ?: "unknown"
}
val packagedGitBranch = providers.provider {
    gitOutput("rev-parse", "--abbrev-ref", "HEAD") ?: "unknown"
}
val packagedGitDirty = providers.provider {
    gitOutput("status", "--porcelain", "--untracked-files=no") != null
}
val generatePackagedBuildIdentityResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/packagedBuildIdentity")
    outputs.dir(outputDir)
    inputs.property("gitHead", packagedGitHead)
    inputs.property("gitBranch", packagedGitBranch)
    inputs.property("gitDirty", packagedGitDirty)

    doLast {
        val dirty = packagedGitDirty.get()
        val commit = if (dirty) "unknown" else packagedGitHead.get()
        val metaInfDir = outputDir.get().file("META-INF").asFile
        metaInfDir.mkdirs()
        metaInfDir.resolve("talos-build.properties").writeText(
            "git.commit=$commit\n" +
                    "git.branch=${packagedGitBranch.get()}\n" +
                    "git.dirty=$dirty\n",
            Charsets.UTF_8
        )
    }
}

tasks.processResources {
    from(generateBuildVersionResource)
}

/* ---------- Jar naming ---------- */

tasks.jar {
    from(generatePackagedBuildIdentityResource)
    archiveBaseName.set("talos")
    archiveVersion.set("") // stable name: talos.jar (referenced by installDist + jpackage)
}

/* ---------- Windows public beta release packaging ---------- */

val windowsReleaseDir = layout.buildDirectory.dir("release/windows")
val publicMsiArtifactName = "Talos-${version}-windows-x64.msi"
val publicAppZipArtifactName = "talos-${version}-windows-x64-app.zip"
val publicReleaseSbomArtifactName = "talos-${version}-sbom.cdx.json"
val releaseSbomDir = layout.buildDirectory.dir("release/sbom")
val releaseSbomFile = releaseSbomDir.map { it.file(publicReleaseSbomArtifactName) }

fun mavenPurl(group: String, name: String, version: String): String {
    val namespace = group.replace('.', '/')
    return if (namespace.isBlank()) {
        "pkg:maven/$name@$version"
    } else {
        "pkg:maven/$namespace/$name@$version"
    }
}

val maxAttestedSbomBytes = 16L * 1024L * 1024L
val releaseSbomSerialPattern =
    Regex("^urn:uuid:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

fun releaseSbomSerialNumber(rootRef: String, components: List<Map<String, Any>>): String {
    val seed = buildString {
        append("talos.releaseSbom.v1\n")
        append(rootRef).append('\n')
        components.forEach { component ->
            append(component["bom-ref"]).append('|')
            append(component["version"]).append('|')
            val sha256 = (component["hashes"] as? Iterable<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull { it["alg"] == "SHA-256" }
                ?.get("content")
                ?.toString()
                .orEmpty()
            append(sha256).append('\n')
        }
    }
    return "urn:uuid:${UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8))}"
}

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

fun appendTalosRuntimeJavaOptions(args: MutableList<String>) {
    talosRuntimeJvmOptions.forEach { option ->
        args.addAll(listOf("--java-options", option))
    }
}

tasks.register("releaseSbom") {
    group = "distribution"
    description = "Writes the CycloneDX runtime dependency inventory for staged release artifacts."

    outputs.file(releaseSbomFile)

    doLast {
        val rootRef = "pkg:maven/dev/talos/talos@${version}"
        val artifacts = configurations.runtimeClasspath.get()
            .resolvedConfiguration
            .resolvedArtifacts
            .sortedWith(compareBy(
                { it.moduleVersion.id.group },
                { it.name },
                { it.moduleVersion.id.version },
                { it.classifier ?: "" },
                { it.extension }
            ))

        val components = artifacts.map { artifact ->
            val id = artifact.moduleVersion.id
            val componentRef = mavenPurl(id.group, artifact.name, id.version)
            val component = linkedMapOf<String, Any>(
                "type" to "library",
                "bom-ref" to componentRef,
                "group" to id.group,
                "name" to artifact.name,
                "version" to id.version,
                "purl" to componentRef
            )
            if (artifact.file.isFile) {
                component["hashes"] = listOf(
                    mapOf(
                        "alg" to "SHA-256",
                        "content" to sha256Hex(artifact.file)
                    )
                )
            }
            component
        }

        writeJson(
            releaseSbomFile.get().asFile,
            linkedMapOf(
                "\$schema" to "https://cyclonedx.org/schema/bom-1.6.schema.json",
                "bomFormat" to "CycloneDX",
                "specVersion" to "1.6",
                "serialNumber" to releaseSbomSerialNumber(rootRef, components),
                "version" to 1,
                "metadata" to linkedMapOf(
                    "tools" to listOf(
                        linkedMapOf(
                            "vendor" to "Talos",
                            "name" to "Gradle releaseSbom task",
                            "version" to version.toString()
                        )
                    ),
                    "component" to linkedMapOf(
                        "type" to "application",
                        "bom-ref" to rootRef,
                        "name" to "talos",
                        "version" to version.toString(),
                        "purl" to rootRef
                    )
                ),
                "components" to components,
                "dependencies" to listOf(
                    linkedMapOf(
                        "ref" to rootRef,
                        "dependsOn" to components.map { it["bom-ref"].toString() }
                    )
                )
            )
        )
    }
}

tasks.register("validateReleaseSbom") {
    group = "verification"
    description = "Validates the generated CycloneDX SBOM against the GitHub SBOM attestation contract."

    dependsOn("releaseSbom")
    inputs.file(releaseSbomFile)

    doLast {
        val sbom = releaseSbomFile.get().asFile
        if (!sbom.isFile) {
            throw GradleException("Release SBOM was not generated: ${sbom.absolutePath}")
        }
        val size = sbom.length()
        if (size <= 0L || size >= maxAttestedSbomBytes) {
            throw GradleException("Release SBOM must be non-empty and smaller than 16 MiB for GitHub SBOM attestation: ${sbom.absolutePath} ($size bytes)")
        }

        val parsed = groovy.json.JsonSlurper().parse(sbom) as? Map<*, *>
            ?: throw GradleException("Release SBOM must be a JSON object: ${sbom.absolutePath}")
        val bomFormat = parsed["bomFormat"]?.toString()
        val specVersion = parsed["specVersion"]?.toString()
        val serialNumber = parsed["serialNumber"]?.toString()

        if (bomFormat != "CycloneDX") {
            throw GradleException("Release SBOM must set bomFormat=CycloneDX for GitHub SBOM attestation")
        }
        if (specVersion.isNullOrBlank()) {
            throw GradleException("Release SBOM must set specVersion for GitHub SBOM attestation")
        }
        if (serialNumber.isNullOrBlank() || !releaseSbomSerialPattern.matches(serialNumber)) {
            throw GradleException("Release SBOM must set an RFC-4122 urn:uuid serialNumber for GitHub SBOM attestation")
        }
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
            "--vendor", "Aris Zounarakis",
            "--dest", destDir.get().asFile.absolutePath,
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", "talos.jar",
            "--main-class", "dev.talos.app.Main",
            "--win-console",
            "--win-per-user-install",
            "--install-dir", "Talos"
        )
        appendTalosRuntimeJavaOptions(args)
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
            "--vendor", "Aris Zounarakis",
            "--dest", destDir.get().asFile.absolutePath,
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", "talos.jar",
            "--main-class", "dev.talos.app.Main",
            "--win-console"
        )
        appendTalosRuntimeJavaOptions(args)
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

tasks.register<Copy>("copyWindowsReleaseUninstall") {
    from("tools/uninstall-talos.ps1")
    into(windowsReleaseDir)
}

tasks.register<Copy>("copyWindowsReleaseSbom") {
    dependsOn("validateReleaseSbom")
    from(releaseSbomFile)
    into(windowsReleaseDir)
}

tasks.register("windowsReleaseChecksums") {
    dependsOn("windowsReleaseMsi", "windowsReleaseAppZip", "copyWindowsReleaseBootstrap", "copyWindowsReleaseUninstall", "copyWindowsReleaseSbom")

    val checksumFile = windowsReleaseDir.map { it.file("checksums.txt") }
    outputs.file(checksumFile)

    doLast {
        val releaseDir = windowsReleaseDir.get().asFile
        releaseDir.mkdirs()

        val artifactNames = listOf(
            publicMsiArtifactName,
            publicAppZipArtifactName,
            "install-talos.ps1",
            "uninstall-talos.ps1",
            publicReleaseSbomArtifactName
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

/* ---------- Linux public beta release packaging ---------- */

// Outputs under build/release/linux.
val linuxReleaseDir = layout.buildDirectory.dir("release/linux")
val publicLinuxAppTarArtifactName = "talos-${version}-linux-x64-app.tar.gz"

fun isLinuxX64Host(): Boolean {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    return osName.contains("linux") && (osArch == "amd64" || osArch == "x86_64")
}

tasks.register<Exec>("jpackageLinuxAppImage") {
    dependsOn(tasks.installDist)

    val jpackageExe = providers.environmentVariable("JAVA_HOME")
        .map { file("$it/bin/jpackage").absolutePath }
        .orElse("jpackage")

    val appDir = layout.buildDirectory.dir("install/talos")
    val inputDir = appDir.map { it.dir("lib") }
    val destDir = layout.buildDirectory.dir("dist/linux-app-image")
    val appVer = providers.provider { version.toString() }

    doFirst {
        if (!isLinuxX64Host()) {
            throw GradleException("Linux release artifacts must be built on Linux x64.")
        }
        project.delete(destDir.get().dir("talos"))
        val args = mutableListOf(
            jpackageExe.get(),
            "--type", "app-image",
            "--name", "talos",
            "--app-version", appVer.get(),
            "--vendor", "Aris Zounarakis",
            "--dest", destDir.get().asFile.absolutePath,
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", "talos.jar",
            "--main-class", "dev.talos.app.Main"
        )
        appendTalosRuntimeJavaOptions(args)

        commandLine(args)
    }
}

tasks.register<Tar>("linuxReleaseAppTar") {
    dependsOn("jpackageLinuxAppImage")
    from(layout.buildDirectory.dir("dist/linux-app-image"))
    destinationDirectory.set(linuxReleaseDir)
    archiveFileName.set(publicLinuxAppTarArtifactName)
    compression = Compression.GZIP
}

tasks.register<Copy>("copyLinuxReleaseBootstrap") {
    from("tools/install-talos.sh")
    into(linuxReleaseDir)
}

tasks.register<Copy>("copyLinuxReleaseUninstall") {
    from("tools/uninstall-talos.sh")
    into(linuxReleaseDir)
}

tasks.register<Copy>("copyLinuxReleaseSbom") {
    dependsOn("validateReleaseSbom")
    from(releaseSbomFile)
    into(linuxReleaseDir)
}

tasks.register("linuxReleaseChecksums") {
    dependsOn("linuxReleaseAppTar", "copyLinuxReleaseBootstrap", "copyLinuxReleaseUninstall", "copyLinuxReleaseSbom")

    val checksumFile = linuxReleaseDir.map { it.file("checksums.txt") }
    outputs.file(checksumFile)

    doLast {
        val releaseDir = linuxReleaseDir.get().asFile
        releaseDir.mkdirs()

        val artifactNames = listOf(
            publicLinuxAppTarArtifactName,
            "install-talos.sh",
            "uninstall-talos.sh",
            publicReleaseSbomArtifactName
        )
        val lines = artifactNames.map { name ->
            val artifact = releaseDir.resolve(name)
            if (!artifact.isFile) {
                throw GradleException("Missing Linux release artifact: ${artifact.absolutePath}")
            }
            "${sha256Hex(artifact)}  $name"
        }

        checksumFile.get().asFile.writeText(
            lines.joinToString(System.lineSeparator()) + System.lineSeparator(),
            Charsets.UTF_8
        )
    }
}

tasks.register("linuxReleaseArtifacts") {
    dependsOn("linuxReleaseChecksums")
    group = "distribution"
    description = "Builds Linux x64 runtime-bundled public beta artifacts and checksums."
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
    val qodanaProjectDir = projectDir.canonicalFile
    val qodanaProjectPath = qodanaProjectDir.toPath().toAbsolutePath().normalize()
    val qodanaRoot = qodanaProjectDir.resolve(".qodana")
    val qodanaReportDir = qodanaRoot.resolve("report")
    val qodanaReportResultsDir = qodanaReportDir.resolve("results")
    val qodanaRawResultsDir = qodanaRoot.resolve("results")
    val qodanaRunBindingFile = qodanaReportResultsDir.resolve("talos-run-binding.json")
    val qodanaBaselineFile = qodanaProjectDir.resolve("qodana-baseline.sarif.json")
    val qodanaProjectKey = UUID.nameUUIDFromBytes(
        qodanaProjectDir.path.toByteArray(StandardCharsets.UTF_8)
    )
    val canonicalGradleHome = gradle.gradleUserHomeDir.canonicalFile
    val canonicalGradleHomePath = canonicalGradleHome.toPath().toAbsolutePath().normalize()
    val qodanaStateRoot = canonicalGradleHome.resolve(
        "caches/talos-qodana/qodana-jvm-community-2026.1-v1"
    )
    val qodanaProjectStateDir = qodanaStateRoot.resolve(qodanaProjectKey.toString())
    val qodanaWorkDir = qodanaProjectStateDir.resolve("work")
    val qodanaCacheDir = qodanaProjectStateDir.resolve("cache")
    val qodanaCleanupTargets = listOf(
        qodanaReportDir,
        qodanaRawResultsDir,
        qodanaRoot.resolve("qodana.sarif.json"),
        qodanaRoot.resolve("qodana-short.sarif.json"),
        qodanaRoot.resolve("log")
    )
    var sourceSnapshotBefore: QodanaSourceSnapshot? = null
    doFirst {
        requireNoFollowOrdinaryComponents(qodanaProjectPath, qodanaRoot.toPath(), "Qodana output root")
        requireNoFollowOrdinaryTree(qodanaRoot.toPath(), "Qodana output root")
        qodanaCleanupTargets.forEach { target ->
            requireNoFollowOrdinaryComponents(qodanaProjectPath, target.toPath(), "Qodana cleanup target")
            requireNoFollowOrdinaryTree(target.toPath(), "Qodana cleanup target")
        }
        requireNoTrackedGitPathsIntersect(qodanaCleanupTargets)
        requireNoFollowOrdinaryComponents(
            qodanaProjectPath,
            qodanaBaselineFile.toPath(),
            "Qodana baseline"
        )
        if (qodanaStateRoot.toPath().toAbsolutePath().normalize().startsWith(qodanaProjectPath)) {
            throw GradleException("Native Qodana state must remain outside the project")
        }
        listOf(qodanaStateRoot, qodanaProjectStateDir, qodanaWorkDir, qodanaCacheDir).forEach { target ->
            requireNoFollowOrdinaryComponents(
                canonicalGradleHomePath,
                target.toPath(),
                "Qodana reusable state"
            )
        }
        requireNoFollowOrdinaryTree(qodanaWorkDir.toPath(), "Qodana work state")
        requireNoFollowOrdinaryTree(qodanaCacheDir.toPath(), "Qodana cache state")

        sourceSnapshotBefore = qodanaSourceSnapshot()

        requireNoTrackedGitPathsIntersect(qodanaCleanupTargets)
        qodanaCleanupTargets.forEach { target ->
            deleteNoFollowOrdinaryTree(
                qodanaProjectPath,
                target.toPath(),
                "Qodana cleanup target"
            )
        }
        requireNoFollowOrdinaryComponents(
            qodanaProjectPath,
            qodanaRawResultsDir.toPath(),
            "Qodana results directory"
        )
        Files.createDirectories(qodanaRawResultsDir.toPath())
        requireNoFollowOrdinaryComponents(
            canonicalGradleHomePath,
            qodanaWorkDir.toPath(),
            "Qodana work directory"
        )
        requireNoFollowOrdinaryTree(qodanaWorkDir.toPath(), "Qodana work directory")
        Files.createDirectories(qodanaWorkDir.toPath())
        requireNoFollowOrdinaryComponents(
            canonicalGradleHomePath,
            qodanaCacheDir.toPath(),
            "Qodana cache directory"
        )
        requireNoFollowOrdinaryTree(qodanaCacheDir.toPath(), "Qodana cache directory")
        Files.createDirectories(qodanaCacheDir.toPath())
        requireNoFollowOrdinaryComponents(qodanaProjectPath, qodanaRawResultsDir.toPath(), "Qodana results directory")
        requireNoFollowOrdinaryComponents(canonicalGradleHomePath, qodanaWorkDir.toPath(), "Qodana work directory")
        requireNoFollowOrdinaryComponents(canonicalGradleHomePath, qodanaCacheDir.toPath(), "Qodana cache directory")
        requireNoFollowOrdinaryTree(qodanaRawResultsDir.toPath(), "Qodana results directory")
        requireNoFollowOrdinaryTree(qodanaWorkDir.toPath(), "Qodana work directory")
        requireNoFollowOrdinaryTree(qodanaCacheDir.toPath(), "Qodana cache directory")
    }
    workingDir(qodanaWorkDir)
    commandLine(
        "qodana",
        "scan",
        "--linter",
        "qodana-jvm-community",
        "--within-docker",
        "false",
        "--cache-dir",
        qodanaCacheDir.absolutePath,
        "--project-dir",
        qodanaProjectDir.absolutePath,
        "--repository-root",
        qodanaProjectDir.absolutePath,
        "--baseline",
        qodanaBaselineFile.absolutePath,
        "--results-dir",
        qodanaRawResultsDir.absolutePath,
        "--report-dir",
        qodanaReportDir.absolutePath
    )
    doLast {
        val before = sourceSnapshotBefore
            ?: throw GradleException("Qodana source snapshot was not captured before the scan")
        val after = qodanaSourceSnapshot()
        if (before != after) {
            throw GradleException("Qodana source snapshot changed while the scan was running")
        }
        requireNoFollowOrdinaryComponents(
            qodanaProjectPath,
            qodanaReportResultsDir.toPath(),
            "Qodana report results"
        )
        requireNoFollowOrdinaryTree(qodanaReportDir.toPath(), "Qodana report output")
        requireNoFollowOrdinaryTree(qodanaRawResultsDir.toPath(), "Qodana raw output")
        if (readNoFollowAttributesOrNull(qodanaRunBindingFile.toPath(), "Qodana run binding") != null) {
            throw GradleException("Qodana scan unexpectedly created the Talos run-binding path")
        }
        val boundArtifacts = readQodanaBoundArtifacts(qodanaRoot, qodanaProjectDir)
        val scopeArtifact = boundArtifacts.single {
            it.spec.relativePath == "results/log/qodana_directories_inspection_groups.csv"
        }
        val observedScope = qodanaObservedScope(scopeArtifact.bytes, qodanaProjectDir)
        writeJson(
            qodanaRunBindingFile,
            linkedMapOf(
                "schemaVersion" to 1,
                "sourceSnapshotStatus" to "stable",
                "sourceSnapshotBefore" to before.asMap(),
                "sourceSnapshotAfter" to after.asMap(),
                "artifactManifest" to boundArtifacts.map { it.asMap() },
                "observedScope" to observedScope
            )
        )
        requireNoFollowOrdinaryTree(qodanaRunBindingFile.toPath(), "Qodana run binding")
    }
}

tasks.register<Exec>("gitleaksLocal") {
    description = "Runs optional local secret scanning with the Gitleaks Docker image."
    group = "verification"
    doFirst {
        talosReportsDir.get().asFile.mkdirs()
    }
    commandLine(
        "docker",
        "run",
        "--rm",
        "-v",
        "${projectDir.absolutePath}:/repo",
        gitleaksImage,
        "git",
        "/repo",
        "--no-banner",
        "--no-color",
        "--redact=100",
        "--gitleaks-ignore-path",
        "/repo/.gitleaksignore",
        "--report-format",
        "json",
        "--report-path",
        "/repo/build/reports/talos/gitleaks-history.json",
        "-v",
        "--platform",
        "github"
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
    val qodanaRunBindingFile = qodanaResultsDir.resolve("talos-run-binding.json")
    val qodanaScopeTelemetryFile = file(".qodana/results/log/qodana_directories_inspection_groups.csv")
    inputs.files(
        qodanaMetaFile,
        qodanaProblemsFile,
        qodanaSarifFile,
        qodanaRunBindingFile,
        qodanaScopeTelemetryFile
    )
    inputs.property("projectVersion", project.version.toString())
    inputs.property("gitHead", providers.provider { gitOutput("rev-parse", "HEAD") ?: "unknown" })
    inputs.property("gitBranch", providers.provider { gitOutput("rev-parse", "--abbrev-ref", "HEAD") ?: "unknown" })
    outputs.upToDateWhen { false }

    doLast {
        val qodanaRoot = qodanaRootDir
        val resultsDir = qodanaResultsDir
        val metaFile = qodanaMetaFile
        val problemsFile = qodanaProblemsFile
        val sarifFile = qodanaSarifFile
        val runBindingFile = qodanaRunBindingFile
        val scopeTelemetryFile = qodanaScopeTelemetryFile
        val canonicalProjectPath = projectDir.canonicalFile.toPath().toAbsolutePath().normalize()
        listOf(
            qodanaRoot,
            resultsDir,
            metaFile,
            problemsFile,
            sarifFile,
            runBindingFile,
            scopeTelemetryFile
        ).forEach { source ->
            requireNoFollowOrdinaryComponents(
                canonicalProjectPath,
                source.toPath(),
                "Qodana summary input"
            )
        }
        requireNoFollowOrdinaryTree(qodanaRoot.toPath(), "Qodana summary input tree")
        writeSummarySoft(outputFile.get().asFile, "qodana-summary", project.version.toString()) {
            val currentGitRevision = gitOutput("rev-parse", "HEAD")
            val currentGitBranch = gitOutput("rev-parse", "--abbrev-ref", "HEAD")
            val qodanaAvailable = qodanaRoot.exists()
            val metaPresent = metaFile.isFile
            val problemsPresent = problemsFile.isFile
            val sarifPresent = sarifFile.isFile
            val scopeTelemetryPresent = scopeTelemetryFile.isFile

            var runBindingParseFailure: String? = null
            val runBinding = if (!runBindingFile.isFile) {
                null
            } else {
                try {
                    parseStrictJson(
                        Files.readAllBytes(runBindingFile.toPath()),
                        "Qodana run binding",
                        1_048_576
                    ) as? Map<*, *>
                        ?: run {
                            runBindingParseFailure = "run-binding-root-not-object"
                            null
                        }
                } catch (_: Throwable) {
                    runBindingParseFailure = "run-binding-json-invalid"
                    null
                }
            }

            fun parseArtifactManifest(raw: Any?): List<Map<String, Any>>? {
                val entries = raw as? List<*> ?: return null
                if (entries.size != qodanaBoundArtifactSpecs.size) return null
                return entries.mapIndexed { index, rawEntry ->
                    val entry = rawEntry as? Map<*, *> ?: return null
                    val path = entry["path"] as? String ?: return null
                    val bytes = exactJsonNonNegativeLong(entry["bytes"]) ?: return null
                    val sha256 = entry["sha256"] as? String ?: return null
                    if (entry.keys != setOf("path", "bytes", "sha256") ||
                        path != qodanaBoundArtifactSpecs[index].relativePath ||
                        !sha256.matches(Regex("[0-9a-f]{64}"))) {
                        return null
                    }
                    linkedMapOf("path" to path, "bytes" to bytes, "sha256" to sha256)
                }
            }

            fun parseObservedScope(raw: Any?): Map<String, Any?>? {
                val candidate = raw as? Map<*, *> ?: return null
                val telemetryBytes = exactJsonNonNegativeLong(candidate["telemetryBytes"]) ?: return null
                val telemetrySha256 = candidate["telemetrySha256"] as? String ?: return null
                val observed = "inspection-activity-observed"
                val notObserved = "no-inspection-activity-observed"
                if (candidate.keys != setOf(
                        "telemetryStatus", "telemetryBytes", "telemetrySha256",
                        "main", "unitTest", "e2eTest"
                    ) ||
                    candidate["telemetryStatus"] != "telemetry-present" ||
                    telemetryBytes <= 0L ||
                    !telemetrySha256.matches(Regex("[0-9a-f]{64}")) ||
                    candidate["main"] !in setOf(observed, notObserved) ||
                    candidate["unitTest"] !in setOf(observed, notObserved) ||
                    candidate["e2eTest"] !in setOf(observed, notObserved)) {
                    return null
                }
                return linkedMapOf(
                    "telemetryStatus" to "telemetry-present",
                    "telemetryBytes" to telemetryBytes,
                    "telemetrySha256" to telemetrySha256,
                    "main" to candidate["main"],
                    "unitTest" to candidate["unitTest"],
                    "e2eTest" to candidate["e2eTest"]
                )
            }

            val bindingBefore = qodanaSourceSnapshotFromMap(runBinding?.get("sourceSnapshotBefore"))
            val bindingAfter = qodanaSourceSnapshotFromMap(runBinding?.get("sourceSnapshotAfter"))
            val bindingManifest = parseArtifactManifest(runBinding?.get("artifactManifest"))
            val bindingObservedScope = parseObservedScope(runBinding?.get("observedScope"))
            var artifactReadFailure: String? = null
            val currentArtifacts = if (metaPresent && problemsPresent && sarifPresent && scopeTelemetryPresent) {
                try {
                    readQodanaBoundArtifacts(qodanaRoot, projectDir.canonicalFile)
                } catch (failure: Throwable) {
                    artifactReadFailure = failure.message ?: "qodana-artifact-read-failed"
                    null
                }
            } else null
            val currentManifest = currentArtifacts?.map { it.asMap() }
            val runBindingStructureValid = runBinding != null &&
                runBinding.keys == setOf(
                    "schemaVersion", "sourceSnapshotStatus", "sourceSnapshotBefore",
                    "sourceSnapshotAfter", "artifactManifest", "observedScope"
                ) &&
                exactJsonNonNegativeInt(runBinding["schemaVersion"]) == 1 &&
                runBinding["sourceSnapshotStatus"] == "stable" &&
                bindingBefore != null && bindingAfter != null && bindingBefore == bindingAfter &&
                bindingManifest != null && bindingObservedScope != null
            val artifactBindingStatus = when {
                !runBindingFile.isFile -> "qodana-run-binding-missing"
                runBindingParseFailure != null || !runBindingStructureValid -> "qodana-run-binding-invalid"
                currentArtifacts == null -> "bound-artifacts-unavailable"
                bindingManifest != currentManifest -> "bound-artifact-mismatch"
                else -> "matches-run-binding"
            }

            var scopeParseFailure: String? = null
            val currentObservedScope = if (artifactBindingStatus == "matches-run-binding") {
                try {
                    val telemetry = currentArtifacts!!.single {
                        it.spec.relativePath == "results/log/qodana_directories_inspection_groups.csv"
                    }
                    qodanaObservedScope(telemetry.bytes, projectDir.canonicalFile)
                } catch (failure: Throwable) {
                    scopeParseFailure = failure.message ?: "scope-telemetry-invalid"
                    emptyMap()
                }
            } else emptyMap()
            var currentSnapshotFailure: String? = null
            val currentSnapshot = if (runBindingStructureValid) {
                try {
                    qodanaSourceSnapshot()
                } catch (_: Throwable) {
                    currentSnapshotFailure = "current-source-snapshot-unavailable"
                    null
                }
            } else {
                null
            }
            val sourceSnapshotStatus = when {
                !runBindingFile.isFile -> "qodana-run-binding-missing"
                runBindingParseFailure != null || !runBindingStructureValid -> "qodana-run-binding-invalid"
                currentSnapshot == null -> "current-source-snapshot-unavailable"
                bindingAfter != currentSnapshot -> "source-snapshot-mismatch"
                else -> "matches-current-source-snapshot"
            }
            val observedStatus = "inspection-activity-observed"
            val noActivityStatus = "no-inspection-activity-observed"
            val scopeTelemetryValid = bindingObservedScope != null &&
                artifactBindingStatus == "matches-run-binding" &&
                scopeParseFailure == null &&
                bindingObservedScope == currentObservedScope
            val scanScopeStatus = when {
                !runBindingFile.isFile -> "qodana-run-binding-missing"
                !scopeTelemetryValid -> "scope-telemetry-incomplete"
                currentObservedScope["main"] != observedStatus -> "required-source-roots-not-observed"
                currentObservedScope["e2eTest"] != observedStatus -> "required-source-roots-not-observed"
                currentObservedScope["unitTest"] !in setOf(observedStatus, noActivityStatus) ->
                    "scope-telemetry-incomplete"
                else -> "required-source-roots-observed"
            }

            var meta = emptyMap<Any?, Any?>()
            var problems = emptyList<Any?>()
            var sarifRuns = emptyList<Any?>()
            var resultSchemaFailure: String? = null
            var metaTotal: Int? = null
            val severityCounts = linkedMapOf<String, Int>()
            var sarifError = 0
            var sarifWarning = 0
            var sarifNote = 0
            var sarifIssueCount = 0
            var newIssues: Int? = 0
            val sarifSeverityCounts = linkedMapOf<String, Int>()
            var authenticatedLinter: String? = null
            var authenticatedLinterVersion: String? = null
            val explicitQodanaRevisions = mutableListOf<String>()
            val explicitQodanaBranches = mutableListOf<String>()
            if (artifactBindingStatus == "matches-run-binding") {
                try {
                    fun requireResult(condition: Boolean, message: String) {
                        if (!condition) throw GradleException(message)
                    }
                    fun explicitNativeString(container: Map<*, *>, key: String, label: String): String? {
                        if (!container.containsKey(key)) return null
                        val value = container[key]
                        requireResult(value is String && value.isNotBlank(), "$label must be a non-empty string")
                        return value as String
                    }
                    val artifactsByPath = currentArtifacts!!.associateBy { it.spec.relativePath }
                    val parsedMeta = parseStrictJson(
                        artifactsByPath.getValue("report/results/metaInformation.json").bytes,
                        "Qodana metaInformation.json",
                        16 * 1024 * 1024
                    )
                    val parsedProblems = parseStrictJson(
                        artifactsByPath.getValue("report/results/result-allProblems.json").bytes,
                        "Qodana result-allProblems.json",
                        128 * 1024 * 1024
                    )
                    val parsedSarif = parseStrictJson(
                        artifactsByPath.getValue("report/results/qodana.sarif.json").bytes,
                        "Qodana qodana.sarif.json",
                        256 * 1024 * 1024
                    )
                    requireResult(parsedMeta is Map<*, *>, "Qodana metadata root must be an object")
                    requireResult(parsedProblems is Map<*, *>, "Qodana problems root must be an object")
                    requireResult(parsedSarif is Map<*, *>, "Qodana SARIF root must be an object")
                    meta = parsedMeta as Map<Any?, Any?>
                    val metaLinter = meta["linter"] as? String
                    val metaLinterVersion = meta["linterVersion"] as? String
                    requireResult(metaLinter == qodanaExpectedLinter, "Qodana metadata linter identity is not the configured engine")
                    requireResult(
                        metaLinterVersion != null && qodanaExpectedLinterVersion.matches(metaLinterVersion),
                        "Qodana metadata linter version is not in the configured 2026.1 engine family"
                    )
                    authenticatedLinter = metaLinter
                    authenticatedLinterVersion = metaLinterVersion
                    val rawAttributes = meta["attributes"]
                    if (rawAttributes != null) {
                        requireResult(rawAttributes is Map<*, *>, "Qodana metadata attributes must be an object")
                        val rawVcs = (rawAttributes as Map<*, *>)["vcs"]
                        if (rawVcs != null) {
                            requireResult(rawVcs is Map<*, *>, "Qodana metadata VCS must be an object")
                            val rawSarifIdea = (rawVcs as Map<*, *>)["sarifIdea"]
                            if (rawSarifIdea != null) {
                                requireResult(rawSarifIdea is Map<*, *>, "Qodana metadata sarifIdea must be an object")
                                val sarifIdea = rawSarifIdea as Map<*, *>
                                val revision = explicitNativeString(
                                    sarifIdea,
                                    "revisionId",
                                    "Qodana metadata revisionId"
                                )
                                val branch = explicitNativeString(
                                    sarifIdea,
                                    "branch",
                                    "Qodana metadata branch"
                                )
                                if (revision != null) {
                                    requireResult(
                                        revision.matches(Regex("[0-9a-fA-F]{40}")),
                                        "Qodana metadata revisionId must be a 40-digit Git object ID"
                                    )
                                    explicitQodanaRevisions += revision.lowercase()
                                }
                                if (branch != null) explicitQodanaBranches += branch
                            }
                        }
                    }
                    val rawProblems = (parsedProblems as Map<*, *>)["listProblem"]
                    val rawRuns = (parsedSarif as Map<*, *>)["runs"]
                    requireResult(rawProblems is List<*>, "Qodana problems must contain a listProblem list")
                    requireResult(rawRuns is List<*> && rawRuns.isNotEmpty(), "Qodana SARIF must contain a non-empty runs list")
                    problems = rawProblems as List<Any?>
                    sarifRuns = rawRuns as List<Any?>
                    metaTotal = exactJsonNonNegativeInt(meta["total"])
                    requireResult(metaTotal != null, "Qodana metadata total must be an exact nonnegative integer")
                    val knownSeverities = setOf("CRITICAL", "HIGH", "MODERATE", "LOW", "INFO")
                    problems.forEach { raw ->
                        requireResult(raw is Map<*, *>, "Every Qodana problem must be an object")
                        val severity = (raw as Map<*, *>)["severity"] as? String
                        val normalized = severity?.trim()?.uppercase()
                        requireResult(normalized in knownSeverities, "Qodana problem contains an unknown severity")
                        severityCounts[normalized!!] = (severityCounts[normalized] ?: 0) + 1
                    }
                    sarifRuns.forEach { rawRun ->
                        requireResult(rawRun is Map<*, *>, "Every Qodana SARIF run must be an object")
                        val run = rawRun as Map<*, *>
                        val driver = ((run["tool"] as? Map<*, *>)?.get("driver") as? Map<*, *>)
                            ?: throw GradleException("Every Qodana SARIF run must identify its analysis driver")
                        requireResult(
                            driver["name"] == qodanaExpectedLinter,
                            "Qodana SARIF driver identity is not the configured engine"
                        )
                        requireResult(
                            driver["version"] == metaLinterVersion,
                            "Qodana SARIF driver version disagrees with authenticated metadata"
                        )
                        val rawProvenance = run["versionControlProvenance"]
                        if (rawProvenance != null) {
                            requireResult(
                                rawProvenance is List<*>,
                                "Qodana SARIF versionControlProvenance must be a list"
                            )
                            (rawProvenance as List<*>).forEach { rawEntry ->
                                requireResult(
                                    rawEntry is Map<*, *>,
                                    "Every Qodana SARIF version-control provenance entry must be an object"
                                )
                                val entry = rawEntry as Map<*, *>
                                val revision = explicitNativeString(
                                    entry,
                                    "revisionId",
                                    "Qodana SARIF revisionId"
                                )
                                val branch = explicitNativeString(entry, "branch", "Qodana SARIF branch")
                                if (revision != null) {
                                    requireResult(
                                        revision.matches(Regex("[0-9a-fA-F]{40}")),
                                        "Qodana SARIF revisionId must be a 40-digit Git object ID"
                                    )
                                    explicitQodanaRevisions += revision.lowercase()
                                }
                                if (branch != null) explicitQodanaBranches += branch
                            }
                        }
                        val results = run["results"]
                        requireResult(results is List<*>, "Every Qodana SARIF run must contain a results list")
                        (results as List<*>).forEach { rawResult ->
                            requireResult(rawResult is Map<*, *>, "Every Qodana SARIF result must be an object")
                            val result = rawResult as Map<*, *>
                            val level = result["level"] as? String
                            requireResult(level in setOf("error", "warning", "note", "none"), "Qodana SARIF result contains an unknown level")
                            when (level) {
                                "error" -> sarifError++
                                "warning" -> sarifWarning++
                                "note" -> sarifNote++
                            }
                            val properties = result["properties"] as? Map<*, *>
                            val severity = properties?.get("qodanaSeverity") as? String
                            val normalized = severity?.trim()?.uppercase()
                            requireResult(normalized in knownSeverities, "Qodana SARIF result contains an unknown Qodana severity")
                            sarifSeverityCounts[normalized!!] = (sarifSeverityCounts[normalized] ?: 0) + 1
                            sarifIssueCount++
                            val rawBaselineState = result["baselineState"]
                            requireResult(
                                rawBaselineState == null ||
                                    rawBaselineState is String &&
                                    rawBaselineState in setOf("new", "unchanged", "updated", "absent"),
                                "Qodana SARIF result contains an unknown baseline state"
                            )
                            val baselineState = rawBaselineState as? String
                            if (baselineState == null) {
                                newIssues = null
                            } else if (baselineState == "new") {
                                newIssues = newIssues?.plus(1)
                            }
                        }
                    }
                    requireResult(metaTotal == problems.size, "Qodana metadata and problem counts disagree")
                    requireResult(metaTotal == sarifIssueCount, "Qodana metadata and SARIF counts disagree")
                    requireResult(severityCounts == sarifSeverityCounts, "Qodana problem and SARIF severity counts disagree")
                } catch (failure: Throwable) {
                    resultSchemaFailure = failure.message ?: "qodana-result-schema-invalid"
                    meta = emptyMap()
                    problems = emptyList()
                    sarifRuns = emptyList()
                    metaTotal = null
                    severityCounts.clear()
                    sarifSeverityCounts.clear()
                    authenticatedLinter = null
                    authenticatedLinterVersion = null
                    explicitQodanaRevisions.clear()
                    explicitQodanaBranches.clear()
                    sarifError = 0
                    sarifWarning = 0
                    sarifNote = 0
                    sarifIssueCount = 0
                    newIssues = null
                }
            }
            val resultSchemaStatus = if (
                artifactBindingStatus == "matches-run-binding" && resultSchemaFailure == null
            ) "validated-consistent-results" else "invalid-or-unauthenticated-results"

            val qodanaRevisions = explicitQodanaRevisions.distinct()
            val qodanaBranches = explicitQodanaBranches.distinct()
            val qodanaRevision = qodanaRevisions.singleOrNull()
            val qodanaBranch = qodanaBranches.singleOrNull()

            val missingRequiredArtifacts = if (!qodanaAvailable) {
                listOf(
                    "metaInformation.json", "result-allProblems.json", "qodana.sarif.json",
                    "qodana_directories_inspection_groups.csv"
                )
            } else {
                listOfNotNull(
                    if (metaPresent) null else "metaInformation.json",
                    if (problemsPresent) null else "result-allProblems.json",
                    if (sarifPresent) null else "qodana.sarif.json",
                    if (scopeTelemetryPresent) null else "qodana_directories_inspection_groups.csv"
                )
            }
            val requiredArtifactStatus = when {
                !qodanaAvailable -> "qodana-results-missing"
                missingRequiredArtifacts.isEmpty() -> "all-required-artifacts-present"
                else -> "required-artifacts-missing"
            }
            val revisionStatus = when {
                !qodanaAvailable -> "qodana-results-missing"
                qodanaRevisions.isEmpty() -> "qodana-revision-unavailable"
                qodanaRevisions.size != 1 -> "qodana-revision-conflict"
                currentGitRevision == null -> "current-git-revision-unavailable"
                qodanaRevision == currentGitRevision -> "matches-current-revision"
                else -> "revision-mismatch"
            }
            val branchStatus = when {
                !qodanaAvailable -> "qodana-results-missing"
                qodanaBranches.isEmpty() -> "qodana-branch-unavailable"
                qodanaBranches.size != 1 -> "qodana-branch-conflict"
                currentGitBranch == null -> "current-git-branch-unavailable"
                qodanaBranch == currentGitBranch -> "matches-current-branch"
                else -> "branch-mismatch"
            }
            val summaryStatus = when {
                !qodanaAvailable -> "qodana-results-missing"
                missingRequiredArtifacts.isNotEmpty() -> "qodana-results-incomplete"
                sourceSnapshotStatus == "qodana-run-binding-missing" -> "qodana-run-binding-missing"
                sourceSnapshotStatus == "qodana-run-binding-invalid" -> "qodana-run-binding-invalid"
                artifactBindingStatus != "matches-run-binding" -> "stale-qodana-artifacts"
                resultSchemaStatus != "validated-consistent-results" -> "qodana-results-invalid"
                sourceSnapshotStatus != "matches-current-source-snapshot" -> "stale-qodana-source-snapshot"
                scanScopeStatus != "required-source-roots-observed" -> "qodana-scope-incomplete"
                revisionStatus !in setOf("matches-current-revision", "qodana-revision-unavailable") ||
                    branchStatus !in setOf("matches-current-branch", "qodana-branch-unavailable") ->
                    "stale-qodana-provenance"
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
                    "sarifFile" to sarifFile.absolutePath,
                    "runBindingFile" to runBindingFile.absolutePath,
                    "scopeTelemetryFile" to scopeTelemetryFile.absolutePath
                ),
                "requiredArtifacts" to mapOf(
                    "status" to requiredArtifactStatus,
                    "missing" to missingRequiredArtifacts,
                    "auxiliaryMissing" to emptyList<String>(),
                    "files" to mapOf(
                        "metaInformation" to metaPresent,
                        "allProblems" to problemsPresent,
                        "sarif" to sarifPresent,
                        "scopeTelemetry" to scopeTelemetryPresent
                    )
                ),
                "artifactBinding" to mapOf(
                    "status" to artifactBindingStatus,
                    "binding" to bindingManifest,
                    "current" to currentManifest,
                    "diagnostic" to artifactReadFailure
                ),
                "resultSchema" to mapOf(
                    "status" to resultSchemaStatus,
                    "diagnostic" to resultSchemaFailure
                ),
                "provenance" to mapOf(
                    "qodanaSourceBranch" to qodanaBranch,
                    "qodanaSourceRevision" to qodanaRevision,
                    "qodanaSourceBranches" to qodanaBranches,
                    "qodanaSourceRevisions" to qodanaRevisions,
                    "currentGitBranch" to currentGitBranch,
                    "currentGitRevision" to currentGitRevision,
                    "revisionStatus" to revisionStatus,
                    "branchStatus" to branchStatus
                ),
                "sourceSnapshot" to mapOf(
                    "status" to sourceSnapshotStatus,
                    "bindingBefore" to bindingBefore?.asMap(),
                    "bindingAfter" to bindingAfter?.asMap(),
                    "current" to currentSnapshot?.asMap(),
                    "diagnostic" to (runBindingParseFailure ?: currentSnapshotFailure)
                ),
                "scanScope" to mapOf(
                    "status" to scanScopeStatus,
                    "telemetryStatus" to currentObservedScope["telemetryStatus"],
                    "telemetryBytes" to currentObservedScope["telemetryBytes"],
                    "telemetrySha256" to currentObservedScope["telemetrySha256"],
                    "main" to currentObservedScope["main"],
                    "unitTest" to currentObservedScope["unitTest"],
                    "e2eTest" to currentObservedScope["e2eTest"],
                    "diagnostic" to scopeParseFailure,
                    "unitTestCorrectnessOwner" to "test-and-candidateTest",
                    "unitTestCoverageClaim" to "inspection-activity-only"
                ),
                "linter" to authenticatedLinter,
                "linterVersion" to authenticatedLinterVersion,
                "totalIssues" to metaTotal,
                "severityCounts" to severityCounts,
                "sarifLevelCounts" to mapOf(
                    "error" to sarifError,
                    "warning" to sarifWarning,
                    "note" to sarifNote
                ),
                "criticalIssues" to if (resultSchemaStatus == "validated-consistent-results") {
                    severityCounts["CRITICAL"] ?: 0
                } else null,
                "criticalIssuesStatus" to when {
                    !qodanaRoot.exists() -> "qodana-results-missing"
                    resultSchemaStatus == "validated-consistent-results" ->
                        "derived-from-validated-consistent-results"
                    else -> "unknown-invalid-or-unauthenticated-results"
                },
                "highIssues" to if (resultSchemaStatus == "validated-consistent-results") {
                    severityCounts["HIGH"] ?: 0
                } else null,
                "newIssues" to newIssues,
                "newIssuesStatus" to when {
                    !qodanaRoot.exists() -> "qodana-results-missing"
                    resultSchemaStatus != "validated-consistent-results" ->
                        "unknown-invalid-or-unauthenticated-results"
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
        val gate = 82.0
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
            | Is instruction coverage above the local gate? | ${if (instructionPercent != null && instructionPercent >= gate) "Yes, `${mdPercent(instructionPercent)}` vs `${mdPercent(gate)}`" else "No or unknown"} | High |
            | Is branch coverage strong? | ${if (branchPercent != null && branchPercent >= 65.0) "Yes, `${mdPercent(branchPercent)}`" else "Mixed, `${mdPercent(branchPercent)}` leaves risk in conditional paths"} | Medium |
            | Is this report useful for release review? | Yes for regression gating, not enough for feature-risk assessment alone | Medium |

            ## Gate Margin

            Decision question: how much room do we have before the coverage gate fails?

            ```text
            Instruction coverage gate

            0%                 ${mdPercent(gate)} gate      ${mdPercent(instructionPercent)} actual             100%
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
        val v1ScenarioPack = mdMap(e2e["v1ScenarioPack"])
        val e2eTotal = mdInt(e2eExecution["total"])
        val e2ePassed = mdInt(e2eExecution["passed"])
        val e2eFailures = mdInt(e2eExecution["failures"])
        val e2eErrors = mdInt(e2eExecution["errors"])
        val e2eSkipped = mdInt(e2eExecution["skipped"])
        val resourceCount = mdInt(scenarioCoverage["resourceCount"])
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
        val sourceSnapshot = mdMap(qodana["sourceSnapshot"])
        val scanScope = mdMap(qodana["scanScope"])
        val requiredArtifacts = mdMap(qodana["requiredArtifacts"])
        val artifactBinding = mdMap(qodana["artifactBinding"])
        val resultSchema = mdMap(qodana["resultSchema"])
        val criticalIssuesStatus = mdSafe(qodana["criticalIssuesStatus"])
        val validatedResults = criticalIssuesStatus == "derived-from-validated-consistent-results"
        val highIssues = mdInt(severityCounts["HIGH"])
        val moderateIssues = mdInt(severityCounts["MODERATE"])
        val criticalIssues = mdInt(severityCounts["CRITICAL"])
        val totalIssues = mdInt(qodana["totalIssues"])
        val maxSeverity = listOf(highIssues, moderateIssues, criticalIssues, 1).max()
        val qodanaBranch = mdSafe(provenance["qodanaSourceBranch"])
        val currentBranch = mdSafe(provenance["currentGitBranch"])
        val qodanaRevision = mdSafe(provenance["qodanaSourceRevision"]).take(7)
        val currentRevision = mdSafe(provenance["currentGitRevision"]).take(7)
        val sourceSnapshotStatus = mdSafe(sourceSnapshot["status"])
        val sourceSnapshotMatches = sourceSnapshotStatus == "matches-current-source-snapshot"
        val currentSourceSnapshot = mdMap(sourceSnapshot["current"])
        val currentSnapshotHash = mdSafe(currentSourceSnapshot["snapshotSha256"])
        val currentTrackedFiles = mdInt(currentSourceSnapshot["trackedFiles"])
        val currentTrackedBytes = mdSafe(currentSourceSnapshot["trackedBytes"])
        val currentChangedFiles = mdInt(currentSourceSnapshot["changedTrackedFiles"])
        val scanScopeStatus = mdSafe(scanScope["status"])
        val mainScopeStatus = mdSafe(scanScope["main"])
        val unitScopeStatus = mdSafe(scanScope["unitTest"])
        val e2eScopeStatus = mdSafe(scanScope["e2eTest"])
        val acceptableCurrentEvidence = mdSafe(qodana["summaryStatus"]) == "qodana-results-match-current-candidate" &&
            sourceSnapshotMatches && scanScopeStatus == "required-source-roots-observed" &&
            validatedResults && artifactBinding["status"] == "matches-run-binding"

        writeReport("qodana", talosVersion, """
            # Qodana Report - $reportDate - Talos $talosVersion

            This report describes whether the scan is bound to the exact Git HEAD, ordered tracked paths, and every tracked file's current raw bytes; Git diff facts are diagnostics only. It reports only scan activity actually observed in Qodana telemetry. It summarizes severity rather than root causes; use the HTML or SARIF report for remediation details. Unit-test correctness is owned by the `test` and `candidateTest` lanes because this Qodana run may report no inspection activity under `src/test`.

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
            | Does this scan match the current source snapshot? | ${if (sourceSnapshotMatches) "Yes, exact HEAD + tracked paths + raw file bytes match" else "No, `$sourceSnapshotStatus`"} | High |
            | Are there critical issues? | ${if (!validatedResults) "Unknown, `$criticalIssuesStatus`" else if (criticalIssues == 0) "No, `0` critical" else "Yes, `$criticalIssues` critical"} | High |
            | Are there high-priority issues to triage? | ${if (!validatedResults) "Unknown until result schemas authenticate" else if (highIssues > 0) "Yes, `$highIssues` high" else "No high issues"} | High |
            | Is this report useful for release review? | Yes for triage pressure and provenance, not enough for root-cause details | High |

            ## Release Triage Funnel

            Decision question: what should happen before release confidence improves?

            ```text
            $totalIssues Qodana findings
              |
              +-- $criticalIssues CRITICAL -> ${if (!validatedResults) "UNKNOWN: results are not authenticated" else if (criticalIssues == 0) "no immediate static-analysis blocker" else "block release until reviewed"}
              |
              +-- $highIssues HIGH ----> ${if (!validatedResults) "UNKNOWN" else if (highIssues == 0) "no high-severity triage needed" else "triage required"}
              |       |
              |       +-- fix true positives
              |       +-- suppress accepted false positives with justification
              |       +-- backlog low-risk cleanup explicitly
              |
              +-- $moderateIssues MODERATE -> review after high-severity pass
            ```

            ## Current-Source Binding

            Decision question: can reviewers trust that this report belongs to these exact source bytes?

            ```text
            domain: talos-qodana-current-source-v2
            HEAD + paths + raw bytes -> ${currentSnapshotHash.take(32)}...
            selected tracked files   -> $currentTrackedFiles ($currentTrackedBytes bytes)
            changed-file diagnostic  -> $currentChangedFiles
            comparison               -> $sourceSnapshotStatus

            Decision:
              ${if (acceptableCurrentEvidence) "Trust the report as bound to the current source snapshot." else "Do not use this report as current release evidence."}
            ```

            Native Qodana VCS metadata is an independent cross-check. Missing native branch/revision fields are informational when the exact source snapshot matches; an explicit mismatch is still rejected.

            | Native field | Qodana | Current | Status |
            | --- | --- | --- | --- |
            | Branch | `$qodanaBranch` | `$currentBranch` | `${mdSafe(provenance["branchStatus"])}` |
            | Revision | `$qodanaRevision` | `$currentRevision` | `${mdSafe(provenance["revisionStatus"])}` |

            ## Observed Scan Scope

            These values mean inspection activity was or was not observed in the authenticated Qodana telemetry. They do not claim that every file was visited, and “no activity observed” is not proof that a directory was excluded.

            | Source root | Observed status | Correctness owner |
            | --- | --- | --- |
            | `src/main` | `$mainScopeStatus` | Qodana + tests |
            | `src/test` | `$unitScopeStatus` | `test` and `candidateTest` |
            | `src/e2eTest` | `$e2eScopeStatus` | Qodana + `candidateE2eTest` |

            Scope gate: `$scanScopeStatus`.

            ## Severity Pressure

            Decision question: is the issue set mostly cleanup, or does it demand active triage?

            ```text
            Severity pressure

            HIGH      ${highIssues.toString().padStart(3)}  ${mdBar(highIssues, maxSeverity, 40)}  ${if (highIssues > 0) "demands triage" else "clean"}
            MODERATE  ${moderateIssues.toString().padStart(3)}  ${mdBar(moderateIssues, maxSeverity, 40)}  review next
            CRITICAL  ${criticalIssues.toString().padStart(3)}  ${mdBar(criticalIssues, maxSeverity, 40)}  ${if (!validatedResults) "unknown" else if (criticalIssues == 0) "no critical blocker" else "blocker"}

            Reviewer signal:
              ${if (acceptableCurrentEvidence) "the lane is current, its exact result bytes are authenticated, and its observed scope is explicit; severity triage remains." else "the lane is not acceptable as current release evidence."}
            ```

            ## Status Details

            | Field | Value |
            | --- | --- |
            | Summary status | `${mdSafe(qodana["summaryStatus"])}` |
            | Required artifact status | `${mdSafe(requiredArtifacts["status"])}` |
            | Exact artifact-byte binding | `${mdSafe(artifactBinding["status"])}` |
            | Result schema and count consistency | `${mdSafe(resultSchema["status"])}` |
            | Source snapshot status | `$sourceSnapshotStatus` |
            | Observed scan-scope status | `$scanScopeStatus` |
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
            | Talos run binding | `.qodana/report/results/talos-run-binding.json` |
            | Scope telemetry | `.qodana/results/log/qodana_directories_inspection_groups.csv` |
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

tasks.register("qualityReportGate") {
    description = "Validates generated Talos quality summaries for release-candidate review."
    group = "verification"
    dependsOn("talosQualitySummaries")

    val coverageSummary = talosReportsDir.map { it.file("coverage-summary.json") }
    val e2eSummary = talosReportsDir.map { it.file("e2e-summary.json") }
    val qodanaSummary = talosReportsDir.map { it.file("qodana-summary.json") }
    val versionSummary = talosReportsDir.map { it.file("version-summary.json") }
    inputs.files(coverageSummary, e2eSummary, qodanaSummary, versionSummary)

    doLast {
        val failures = mutableListOf<String>()
        fun requireQuality(condition: Boolean, message: String) {
            if (!condition) failures += message
        }

        val coverage = readQualitySummary(coverageSummary.get().asFile, "coverage-summary")
        val coverageTests = mdMap(coverage["tests"])
        val instructionCoverage = mdMap(coverage["instructionCoverage"])
        val coverageStatus = mdSafe(coverageTests["status"])
        val coverageFailures = mdInt(coverageTests["failures"])
        val coverageErrors = mdInt(coverageTests["errors"])
        val coverageTotal = mdInt(coverageTests["total"])
        val instructionPercent = qualityDouble(instructionCoverage["percent"])

        requireQuality(
            coverage["coverageDataStatus"] == "jacoco-xml-present",
            "coverage-summary must be backed by JaCoCo XML"
        )
        requireQuality(
            coverageStatus in setOf("passed", "passed-with-skips"),
            "coverage-summary tests must be passed or passed-with-skips, was `$coverageStatus`"
        )
        requireQuality(
            coverageFailures == 0 && coverageErrors == 0,
            "coverage-summary tests must have zero failures and errors"
        )
        requireQuality(coverageTotal > 0, "coverage-summary must contain candidate test results")
        requireQuality(
            instructionPercent != null && instructionPercent >= 82.0,
            "coverage-summary instruction coverage must be at least 82.00%, was `${mdPercent(instructionPercent)}`"
        )

        val e2e = readQualitySummary(e2eSummary.get().asFile, "e2e-summary")
        val e2eExecution = mdMap(e2e["testExecution"])
        val scenarioCoverage = mdMap(e2e["jsonScenarioCoverage"])
        val e2eStatus = mdSafe(e2eExecution["status"])
        val e2eFailures = mdInt(e2eExecution["failures"])
        val e2eErrors = mdInt(e2eExecution["errors"])
        val e2eSkipped = mdInt(e2eExecution["skipped"])
        val e2eTotal = mdInt(e2eExecution["total"])
        val resourceCount = mdInt(scenarioCoverage["resourceCount"])
        val passedResourceCount = mdInt(scenarioCoverage["passedResourceCount"])

        requireQuality(e2eStatus == "passed", "e2e-summary status must be passed, was `$e2eStatus`")
        requireQuality(e2eFailures == 0 && e2eErrors == 0, "e2e-summary tests must have zero failures and errors")
        requireQuality(e2eSkipped == 0, "e2e-summary tests must have zero skipped tests")
        requireQuality(e2eTotal > 0, "e2e-summary must contain candidate E2E results")
        requireQuality(resourceCount > 0, "e2e-summary must include JSON scenario resource coverage")
        requireQuality(
            passedResourceCount == resourceCount,
            "e2e-summary JSON scenario resources must all pass, was `$passedResourceCount / $resourceCount`"
        )

        val qodana = readQualitySummary(qodanaSummary.get().asFile, "qodana-summary")
        val qodanaStatus = mdSafe(qodana["summaryStatus"])
        val qodanaLinter = qodana["linter"] as? String
        val qodanaLinterVersion = qodana["linterVersion"] as? String
        val qodanaCriticalStatus = mdSafe(qodana["criticalIssuesStatus"])
        val qodanaCriticalIssues = mdInt(qodana["criticalIssues"])
        val qodanaSourceSnapshot = mdMap(qodana["sourceSnapshot"])
        val qodanaScanScope = mdMap(qodana["scanScope"])
        val qodanaArtifactBinding = mdMap(qodana["artifactBinding"])
        val qodanaResultSchema = mdMap(qodana["resultSchema"])
        val qodanaSummaryCurrentSnapshot = qodanaSourceSnapshotFromMap(qodanaSourceSnapshot["current"])
        val qodanaGateCurrentSnapshot = try {
            qodanaSourceSnapshot()
        } catch (_: Throwable) {
            null
        }

        requireQuality(
            qodanaStatus == "qodana-results-match-current-candidate",
            "qodana-summary status must be exactly `qodana-results-match-current-candidate`, was `$qodanaStatus`"
        )
        requireQuality(
            qodanaSourceSnapshot["status"] == "matches-current-source-snapshot",
            "qodana-summary must match the current source snapshot"
        )
        requireQuality(
            qodanaSummaryCurrentSnapshot != null &&
                qodanaGateCurrentSnapshot != null &&
                qodanaSummaryCurrentSnapshot == qodanaGateCurrentSnapshot,
            "qodana-summary current source snapshot must still match exact tracked bytes at gate time"
        )
        requireQuality(
            qodanaArtifactBinding["status"] == "matches-run-binding",
            "qodana-summary must authenticate the exact bound Qodana artifact bytes"
        )
        requireQuality(
            qodanaResultSchema["status"] == "validated-consistent-results",
            "qodana-summary must validate consistent Qodana result schemas and counts"
        )
        requireQuality(
            qodanaLinter == qodanaExpectedLinter &&
                qodanaLinterVersion != null &&
                qodanaExpectedLinterVersion.matches(qodanaLinterVersion),
            "qodana-summary must authenticate the configured QDJVM 2026.1 analysis engine"
        )
        requireQuality(
            qodanaScanScope["status"] == "required-source-roots-observed",
            "qodana-summary must have authenticated required-source-root telemetry"
        )
        requireQuality(
            qodanaScanScope["main"] == "inspection-activity-observed" &&
                qodanaScanScope["e2eTest"] == "inspection-activity-observed",
            "qodana-summary must observe inspection activity for src/main and src/e2eTest"
        )
        requireQuality(
            qodanaScanScope["unitTest"] in setOf(
                "inspection-activity-observed",
                "no-inspection-activity-observed"
            ),
            "qodana-summary must explicitly report src/test inspection activity"
        )
        requireQuality(
            qodanaCriticalStatus == "derived-from-validated-consistent-results",
            "qodana-summary critical-issue status must be in the explicit derived-status allowlist"
        )
        requireQuality(qodanaCriticalIssues == 0, "qodana-summary must have zero critical issues")

        val version = readQualitySummary(versionSummary.get().asFile, "version-summary")
        val jarTaskState = mdMap(version["jarTaskStateInCurrentInvocation"])
        val artifacts = mdList(version["artifacts"]).map { mdMap(it) }
        val jarExists = qualityBoolean(jarTaskState["jarExists"])
        val hasExistingArtifact = artifacts.any { qualityBoolean(it["exists"]) }

        requireQuality(jarExists || hasExistingArtifact, "version-summary must point at an existing Talos artifact")
        requireQuality(
            mdSafe(jarTaskState["status"]) != "jar-missing",
            "version-summary jar task status must not be jar-missing"
        )

        if (failures.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "Talos quality report gate failed:\n" + failures.joinToString("\n") { " - $it" }
            )
        }

        logger.lifecycle("Talos quality report gate passed: coverage, e2e, qodana, and version summaries are release-acceptable.")
    }
}

tasks.register("releaseQualityPacket") {
    description = "Runs the full automated gate, fresh local quality lane, Markdown reports, and release-quality summary validation."
    group = "verification"
    dependsOn("check", "talosQualityLocal", "qualityReportGate")
}

tasks.named("qualityReportGate") {
    mustRunAfter("talosQualityLocal")
}

if (providers.gradleProperty("withQualityReports").map { it.toBoolean() }.orElse(false).get()) {
    tasks.named("check") {
        finalizedBy("writeQualityMarkdownReports")
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
