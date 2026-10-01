package com.istech.buscourse.distkit

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CourseBundleStreamingTest {
    @Test fun writesManifestFirstAndStreamsFramesFromSourceFiles() {
        val root = createTempDir(prefix = "bundle-stream-test-")
        try {
            val frame = File(root, "frame.jpg").apply { writeBytes(ByteArray(256 * 1024) { (it % 251).toByte() }) }
            val output = ByteArrayOutputStream()
            val progress = mutableListOf<Pair<Int, Int>>()
            CourseBundle.write(output, CourseBundle.Payload(1, "[]".toByteArray(), listOf(
                CourseBundle.FrameFile("1", 10L, "frames/1/0.jpg", frame),
            )), 1, "2031-01-01T00:00:00Z") { done, total -> progress += done to total }

            val entries = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; entries += entry.name; zip.closeEntry() }
            }
            assertThat(entries).containsExactly("manifest.json", "courses.json", "frames.json", "frames/1/0.jpg").inOrder()
            assertThat(progress).containsExactly(1 to 1)
            assertThat(root.listFiles()?.map { it.name }).containsExactly("frame.jpg")
        } finally { root.deleteRecursively() }
    }

    @Test fun insufficientSpaceDoesNotCreateExtractionDirectory() {
        val root = createTempDir(prefix = "bundle-space-test-")
        try {
            val payload = CourseBundle.Payload(1, "[]".toByteArray(), emptyList())
            val archive = ByteArrayOutputStream().also { CourseBundle.write(it, payload, 1, "2031-01-01T00:00:00Z") }.toByteArray()
            val destination = File(root, "not-created")
            val error = org.junit.Assert.assertThrows(CourseBundle.ValidationException::class.java) {
                CourseBundle.verifyAndExtract(ByteArrayInputStream(archive), destination, availableBytes = 0)
            }
            assertThat(error.reason).isEqualTo(CourseBundle.ValidationException.Reason.INSUFFICIENT_SPACE)
            assertThat(destination.exists()).isFalse()
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsNonManifestFirstAndUnexpectedEntries() {
        val entries = linkedMapOf(
            "manifest.json" to JSONObject().put("kind", "course").put("schema_version", CourseBundle.SCHEMA_VERSION)
                .put("expanded_bytes", 0).put("files", JSONArray()).toString().toByteArray(),
        )
        val root = createTempDir(prefix = "bundle-corrupt-test-")
        try {
            val reordered = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("unexpected")); zip.write(1); zip.closeEntry()
                entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            } }.toByteArray()
            val error = org.junit.Assert.assertThrows(CourseBundle.ValidationException::class.java) {
                CourseBundle.verifyAndExtract(ByteArrayInputStream(reordered), File(root, "stage"))
            }
            assertThat(error.reason).isEqualTo(CourseBundle.ValidationException.Reason.CORRUPT)
            assertThat(File(root, "stage").exists()).isFalse()
        } finally { root.deleteRecursively() }
    }
}
