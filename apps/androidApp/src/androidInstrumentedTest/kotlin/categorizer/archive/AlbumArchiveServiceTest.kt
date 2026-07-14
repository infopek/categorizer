package categorizer.archive

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import categorizer.data.AndroidAlbumRepository
import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumQuery
import categorizer.domain.AlbumResult
import categorizer.domain.CarIdentity
import categorizer.domain.ManagedImageRef
import categorizer.media.ManagedImageStore
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumArchiveServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "archive-service-test.db"
    private lateinit var repository: AndroidAlbumRepository
    private lateinit var service: AlbumArchiveService
    private val archive get() = File(context.cacheDir, "album-test.zip")
    private val image get() = File(context.filesDir, "images/image-001.png")

    @Before fun setUp() {
        context.deleteDatabase(databaseName)
        File(context.filesDir, "images").deleteRecursively()
        File(context.filesDir, "archive-import-journal.json").delete()
        archive.delete()
        repository = AndroidAlbumRepository(context, databaseName)
        service = AlbumArchiveService(context, repository, ManagedImageStore(context))
    }

    @After fun tearDown() {
        repository.close(); context.deleteDatabase(databaseName)
        File(context.filesDir, "images").deleteRecursively(); archive.delete()
    }

    @Test fun exportImportRoundTripPreservesAlbumAndImageBytes() {
        seed()
        val originalBytes = image.readBytes()
        assertIs<ArchiveResult.Success<ArchiveExportInfo>>(runSuspend { service.export(archive, 1_783_962_000_000) })
        runSuspend { repository.delete("entry-001") }; image.delete()

        val plan = assertIs<ArchiveResult.Success<ValidatedImportPlan>>(service.validate(archive)).value
        val imported = assertIs<ArchiveResult.Success<ArchiveImportInfo>>(service.commit(plan)).value
        assertEquals(listOf("entry-001"), imported.importedEntryIds)
        val restored = assertIs<AlbumResult.Success<List<AlbumEntry>>>(runSuspend { repository.query(AlbumQuery()) }).value.single()
        assertEquals(fixtureEntry(), restored.copy(managedImage = fixtureEntry().managedImage))
        assertContentEquals(originalBytes, File(context.filesDir, restored.managedImage.relativePath).readBytes())
    }

    @Test fun canonicalNegativeMutationsAreRejectedWithoutAlbumMutation() {
        seed(); runSuspend { service.export(archive, 1_783_962_000_000) }
        val mutations = listOf(
            "checksum" to ArchiveErrorCode.CHECKSUM_MISMATCH,
            "missing" to ArchiveErrorCode.MISSING_MEMBER,
            "duplicate-id" to ArchiveErrorCode.DUPLICATE_VALUE,
            "version" to ArchiveErrorCode.UNSUPPORTED_VERSION,
            "traversal" to ArchiveErrorCode.UNSAFE_PATH,
            "duplicate-member" to ArchiveErrorCode.UNREADABLE_ARCHIVE
        )
        mutations.forEach { (mutation, expected) ->
            val mutated = File(context.cacheDir, "$mutation.zip")
            mutateArchive(archive, mutated, mutation)
            val failure = assertIs<ArchiveResult.Failure>(service.validate(mutated))
            assertTrue(failure.errors.any { it.code == expected }, "$mutation produced ${failure.errors}")
            mutated.delete()
        }
        assertEquals(1, assertIs<AlbumResult.Success<List<AlbumEntry>>>(runSuspend { repository.query(AlbumQuery()) }).value.size)
    }

    @Test fun conflictsAbortByDefaultAndExplicitKeepProducesNoOp() {
        seed(); runSuspend { service.export(archive, 1_783_962_000_000) }
        assertTrue(assertIs<ArchiveResult.Failure>(service.validate(archive)).errors.all { it.code == ArchiveErrorCode.CONFLICT })
        val kept = assertIs<ArchiveResult.Success<ValidatedImportPlan>>(
            service.validate(archive, mapOf("entry-001" to ArchiveConflictDecision.KEEP_EXISTING))
        ).value
        assertEquals(0, kept.preview.importCount)
        assertTrue(assertIs<ArchiveResult.Success<ArchiveImportInfo>>(service.commit(kept)).value.importedEntryIds.isEmpty())
    }

    @Test fun injectedFailureRollsBackInstalledImageAndMetadata() {
        seed(); runSuspend { service.export(archive, 1_783_962_000_000) }
        runSuspend { repository.delete("entry-001") }; image.delete()
        val plan = assertIs<ArchiveResult.Success<ValidatedImportPlan>>(service.validate(archive)).value
        val failing = AlbumArchiveService(context, repository, ManagedImageStore(context), failurePoint = ImportFailurePoint.AFTER_FIRST_FILE)
        assertEquals(ArchiveErrorCode.COMMIT_FAILED, assertIs<ArchiveResult.Failure>(failing.commit(plan)).errors.single().code)
        assertTrue(assertIs<AlbumResult.Success<List<AlbumEntry>>>(runSuspend { repository.query(AlbumQuery()) }).value.isEmpty())
        assertFalse(image.exists())
        assertFalse(File(context.filesDir, "archive-import-journal.json").exists())
    }

    @Test fun localMutationAfterPreviewInvalidatesPlan() {
        seed(); runSuspend { service.export(archive, 1_783_962_000_000) }
        runSuspend { repository.delete("entry-001") }; image.delete()
        val plan = assertIs<ArchiveResult.Success<ValidatedImportPlan>>(service.validate(archive)).value
        val otherImage = File(context.filesDir, "images/other.png").apply { parentFile?.mkdirs(); writeBytes(PNG) }
        val other = fixtureEntry().copy(entryId = "other", managedImage = ManagedImageRef("other", "images/other.png"))
        assertIs<AlbumResult.Success<*>>(runSuspend { repository.create(other) })

        val failure = assertIs<ArchiveResult.Failure>(service.commit(plan))
        assertEquals(ArchiveErrorCode.STALE_PLAN, failure.errors.single().code)
        assertFalse(image.exists())
        otherImage.delete()
    }

    private fun seed() {
        image.parentFile?.mkdirs(); image.writeBytes(PNG)
        assertIs<AlbumResult.Success<*>>(runSuspend { repository.create(fixtureEntry()) })
    }
    private fun fixtureEntry() = AlbumEntry("entry-001", ManagedImageRef("image-001", "images/image-001.png"),
        CarIdentity("mercedes-benz-c-class-w204", "Mercedes-Benz", "C-Class", "W204", null, "Mercedes-Benz C-Class (W204)"),
        "2026-07-13", true, "Synthetic contract fixture", 1_783_962_000_000, 1_783_962_000_000, 1)

    private fun mutateArchive(source: File, output: File, mutation: String) {
        ZipFile(source).use { zip ->
            val manifestBytes = zip.getInputStream(zip.getEntry("manifest.json")).readBytes()
            val imageEntry = zip.entries().asSequence().first { it.name.startsWith("images/") }
            val imageBytes = zip.getInputStream(imageEntry).readBytes()
            val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
            var path = imageEntry.name
            when (mutation) {
                "checksum" -> manifest.getJSONArray("image_files").getJSONObject(0).put("sha256", "0".repeat(64))
                "duplicate-id" -> { manifest.getJSONArray("entries").put(JSONObject(manifest.getJSONArray("entries").getJSONObject(0).toString())); manifest.put("entry_count", 2) }
                "version" -> manifest.put("archive_schema_version", "2.0.0")
                "traversal" -> { path = "../escape.png"; manifest.getJSONArray("image_files").getJSONObject(0).put("relative_path", path) }
            }
            if (mutation == "duplicate-member") {
                rawZip(output, listOf("manifest.json" to manifest.toString().toByteArray(), path to imageBytes, path to imageBytes)); return
            }
            ZipOutputStream(FileOutputStream(output)).use { out ->
                out.putNextEntry(ZipEntry("manifest.json")); out.write(manifest.toString().toByteArray()); out.closeEntry()
                if (mutation != "missing") putStored(out, path, imageBytes)
            }
        }
    }

    private fun putStored(out: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        out.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; this.crc = crc.value })
        out.write(bytes); out.closeEntry()
    }

    private fun rawZip(output: File, values: List<Pair<String, ByteArray>>) {
        data class Central(val name: ByteArray, val bytes: ByteArray, val crc: Long, val offset: Int)
        val body = ByteArrayOutputStream(); val stream = DataOutputStream(body); val central = mutableListOf<Central>()
        values.forEach { (nameText, bytes) ->
            val name = nameText.toByteArray(); val crc = CRC32().apply { update(bytes) }.value; val offset = body.size()
            stream.leInt(0x04034b50); stream.leShort(20); stream.leShort(0); stream.leShort(0); stream.leShort(0); stream.leShort(0)
            stream.leInt(crc); stream.leInt(bytes.size); stream.leInt(bytes.size); stream.leShort(name.size); stream.leShort(0); stream.write(name); stream.write(bytes)
            central += Central(name, bytes, crc, offset)
        }
        val centralOffset = body.size()
        central.forEach { value ->
            stream.leInt(0x02014b50); stream.leShort(20); stream.leShort(20); stream.leShort(0); stream.leShort(0); stream.leShort(0); stream.leShort(0)
            stream.leInt(value.crc); stream.leInt(value.bytes.size); stream.leInt(value.bytes.size); stream.leShort(value.name.size); stream.leShort(0); stream.leShort(0)
            stream.leShort(0); stream.leShort(0); stream.leInt(0); stream.leInt(value.offset); stream.write(value.name)
        }
        val centralSize = body.size() - centralOffset
        stream.leInt(0x06054b50); stream.leShort(0); stream.leShort(0); stream.leShort(central.size); stream.leShort(central.size)
        stream.leInt(centralSize); stream.leInt(centralOffset); stream.leShort(0); stream.close(); output.writeBytes(body.toByteArray())
    }
    private fun DataOutputStream.leShort(value: Int) { writeByte(value); writeByte(value ushr 8) }
    private fun DataOutputStream.leInt(value: Int) = leInt(value.toLong())
    private fun DataOutputStream.leInt(value: Long) { writeByte(value.toInt()); writeByte((value ushr 8).toInt()); writeByte((value ushr 16).toInt()); writeByte((value ushr 24).toInt()) }

    private fun <T> runSuspend(block: suspend () -> T): T { var completed: Result<T>? = null; block.startCoroutine(object : Continuation<T> { override val context = EmptyCoroutineContext; override fun resumeWith(result: Result<T>) { completed = result } }); return checkNotNull(completed).getOrThrow() }

    companion object { private val PNG = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", Base64.DEFAULT) }
}
