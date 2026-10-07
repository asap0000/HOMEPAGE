package com.istech.buscourse.distkit

import com.istech.buscourse.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Safe, versioned container primitives shared by course-bundle export and import. */
object CourseBundle {
    const val EXTENSION = ".isnavikit"
    const val SCHEMA_VERSION = "1.0"
    private const val MANIFEST = "manifest.json"
    private const val BUFFER_SIZE = 64 * 1024

    class ValidationException(val reason: Reason, val additionalBytes: Long = 0, message: String) : Exception(message) {
        enum class Reason { CORRUPT, UNSUPPORTED_SCHEMA, INSUFFICIENT_SPACE }
    }
    class NaviOnlyGateException(message: String) : Exception(message)

    data class VerifiedBundle(val root: File, val manifest: JSONObject, val expandedBytes: Long)
    data class FrameFile(val sessionKey: String, val capturedAt: Long, val relativePath: String, val source: File)
    data class Payload(val courseCount: Int, val coursesJson: ByteArray, val frames: List<FrameFile>)

    /** Writes directly to the caller's stream without creating an archive or frame copy. */
    fun write(output: OutputStream, payload: Payload, sourceVersionCode: Int,
              createdAt: String = Instant.now().toString(), onProgress: (Int, Int) -> Unit = { _, _ -> },
              /** 書き出す前の準備（全映像の SHA-256 を先に測る＝manifest を先頭に置くため）の進み。何千枚あると数十秒かかる。 */
              onPrepare: (Int, Int) -> Unit = { _, _ -> }) {
        require(payload.courseCount > 0) { "コースがありません" }
        val frameIndex = JSONArray()
        val names = mutableSetOf<String>()
        payload.frames.forEach { frame ->
            require(frame.sessionKey.matches(Regex("[0-9]+")))
            require(frame.relativePath.startsWith("frames/${frame.sessionKey}/") && safePath(frame.relativePath))
            require(frame.source.isFile)
            require(names.add(frame.relativePath)) { "重複したファイルです" }
            frameIndex.put(JSONObject().put("sessionKey", frame.sessionKey)
                .put("captured_at", frame.capturedAt).put("path", frame.relativePath))
        }
        val metadata = linkedMapOf(
            "courses.json" to payload.coursesJson,
            "frames.json" to frameIndex.toString().toByteArray(Charsets.UTF_8),
        )
        val files = mutableListOf<WriteRecord>()
        metadata.forEach { (path, bytes) -> files += WriteRecord(path, bytes.size.toLong(), sha256(bytes), bytes, null) }
        payload.frames.forEachIndexed { index, frame ->
            files += inspect(frame.relativePath, frame.source)
            onPrepare(index + 1, payload.frames.size)
        }
        val records = JSONArray()
        var expanded = 0L
        files.forEach { record ->
            expanded = Math.addExact(expanded, record.size)
            records.put(JSONObject().put("path", record.path).put("sha256", record.sha256).put("size_bytes", record.size))
        }
        val manifest = JSONObject().put("kind", "course").put("schema_version", SCHEMA_VERSION)
            .put("created_at", createdAt).put("source_version_code", sourceVersionCode)
            .put("course_count", payload.courseCount).put("frame_count", payload.frames.size)
            .put("expanded_bytes", expanded).put("files", records)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            var completedFrames = 0
            files.forEach { record ->
                zip.putNextEntry(ZipEntry(record.path))
                record.bytes?.let { zip.write(it) } ?: streamFile(record.file!!, zip)
                zip.closeEntry()
                if (record.file != null) onProgress(++completedFrames, payload.frames.size)
            }
        }
    }

    /** Compatibility entry point for local callers and JVM tests. */
    fun write(destination: File, payload: Payload, sourceVersionCode: Int, createdAt: String = Instant.now().toString()) {
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { write(it, payload, sourceVersionCode, createdAt) }
    }

    fun verifyAndExtract(input: InputStream, stagingParent: File, availableBytes: Long = stagingParent.usableSpace,
                         onProgress: (Long, Long) -> Unit = { _, _ -> }): VerifiedBundle {
        var stage: File? = null
        var createdParent = false
        try {
            ZipInputStream(input.buffered()).use { zip ->
                val first = zip.nextEntry ?: corrupt()
                if (first.isDirectory || first.name != MANIFEST) corrupt()
                val manifestBytes = zip.readBytes()
                zip.closeEntry()
                val manifest = try { JSONObject(manifestBytes.toString(Charsets.UTF_8)) } catch (_: Exception) { corrupt() }
                if (manifest.optString("kind") != "course") corrupt()
                if (manifest.optString("schema_version") != SCHEMA_VERSION) {
                    throw ValidationException(ValidationException.Reason.UNSUPPORTED_SCHEMA, message = "未対応の束の版です")
                }
                val declared = manifest.optLong("expanded_bytes", -1)
                if (declared < 0) corrupt()
                val expected = linkedMapOf<String, FileRecord>()
                val files = manifest.optJSONArray("files") ?: corrupt()
                try {
                    for (i in 0 until files.length()) {
                        val item = files.getJSONObject(i)
                        val path = item.getString("path"); val size = item.getLong("size_bytes"); val hash = item.getString("sha256")
                        if (!safePath(path) || path == MANIFEST || size < 0 || !hash.matches(Regex("[0-9a-fA-F]{64}")) ||
                            expected.put(path, FileRecord(hash, size)) != null) corrupt()
                    }
                } catch (e: ValidationException) { throw e } catch (_: Exception) { corrupt() }
                if (expected.isEmpty() || expected.values.fold(0L) { a, r -> Math.addExact(a, r.size) } != declared) corrupt()
                val needed = if (declared > Long.MAX_VALUE / 2) Long.MAX_VALUE else declared * 2
                if (availableBytes < needed) throw ValidationException(ValidationException.Reason.INSUFFICIENT_SPACE,
                    additionalBytes = needed - availableBytes, message = "空き容量が足りません")

                val parentExisted = stagingParent.exists()
                if (!stagingParent.exists() && !stagingParent.mkdirs()) corrupt()
                createdParent = !parentExisted
                val root = File(stagingParent, UUID.randomUUID().toString())
                if (!root.mkdir()) { if (!parentExisted) stagingParent.delete(); corrupt() }
                stage = root
                val seen = mutableSetOf<String>()
                var actualTotal = 0L
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.isDirectory || !safePath(entry.name) || entry.name !in expected || !seen.add(entry.name)) corrupt()
                    val target = File(root, entry.name)
                    if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) corrupt()
                    target.parentFile?.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    val record = expected.getValue(entry.name)
                    target.outputStream().buffered().use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            if (size > record.size - n) corrupt()
                            out.write(buffer, 0, n); digest.update(buffer, 0, n); size = Math.addExact(size, n.toLong())
                        }
                    }
                    zip.closeEntry()
                    val hash = digest.digest().hex()
                    if (size != record.size || !hash.equals(record.sha256, true)) corrupt()
                    actualTotal = Math.addExact(actualTotal, size)
                    onProgress(actualTotal, declared)
                    entry = zip.nextEntry
                }
                if (seen != expected.keys || actualTotal != declared) corrupt()
                return VerifiedBundle(root, manifest, actualTotal)
            }
        } catch (e: ValidationException) {
            stage?.deleteRecursively()
            if (createdParent && stagingParent.listFiles().isNullOrEmpty()) stagingParent.delete()
            throw e
        } catch (e: Exception) {
            stage?.deleteRecursively()
            if (createdParent && stagingParent.listFiles().isNullOrEmpty()) stagingParent.delete()
            corrupt()
        }
    }

    fun verifyAndExtract(zipFile: File, stagingParent: File, availableBytes: Long = stagingParent.usableSpace): VerifiedBundle =
        FileInputStream(zipFile).use { verifyAndExtract(it, stagingParent, availableBytes) }

    private fun inspect(path: String, file: File): WriteRecord {
        val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); size = Math.addExact(size, n.toLong()) }
        }
        return WriteRecord(path, size, digest.digest().hex(), null, file)
    }

    private fun streamFile(file: File, output: OutputStream) {
        FileInputStream(file).use { input -> val buffer = ByteArray(BUFFER_SIZE); while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) } }
    }
    private fun corrupt(): Nothing = throw ValidationException(ValidationException.Reason.CORRUPT, message = "束が壊れています")
    fun requireNaviOnly(naviOnly: Boolean = BuildConfig.NAVI_ONLY) {
        if (!naviOnly) throw NaviOnlyGateException("コース束の取り込みはナビ専科でのみ利用できます")
    }
    fun cleanupUnreferenced(root: File, referencedRelativePaths: Set<String>) {
        if (!root.exists()) return
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir -> if (referencedRelativePaths.none { it.startsWith("${dir.name}/") }) dir.deleteRecursively() }
    }
    private fun safePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') && '\\' !in path &&
        path.split('/').none { it.isBlank() || it == "." || it == ".." }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private data class FileRecord(val sha256: String, val size: Long)
    private data class WriteRecord(val path: String, val size: Long, val sha256: String, val bytes: ByteArray?, val file: File?)
}
