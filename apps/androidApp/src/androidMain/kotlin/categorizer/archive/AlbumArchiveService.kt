package categorizer.archive

import android.content.Context
import categorizer.data.AndroidAlbumRepository
import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumQuery
import categorizer.domain.AlbumResult
import categorizer.domain.ManagedImageRef
import categorizer.media.ManagedImageStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

class AlbumArchiveService internal constructor(
    context: Context,
    private val repository: AndroidAlbumRepository,
    private val imageStore: ManagedImageStore = ManagedImageStore(context),
    private val applicationId: String = context.packageName,
    private val versionName: String = "0.1.0",
    private val versionCode: Int = 1,
    private val failurePoint: ImportFailurePoint = ImportFailurePoint.NONE
) {
    private val appContext = context.applicationContext
    private val journal = File(appContext.filesDir, "archive-import-journal.json")

    init { recoverInterruptedImport() }

    suspend fun export(output: File, createdAtEpochMs: Long): ArchiveResult<ArchiveExportInfo> = try {
        val entries = when (val result = repository.query(AlbumQuery())) {
            is AlbumResult.Success -> result.value.sortedBy { it.entryId }
            is AlbumResult.Failure -> return failure(ArchiveErrorCode.COMMIT_FAILED, result.error.message)
        }
        val archiveId = "archive-${UUID.randomUUID()}"
        val images = entries.map { entry ->
            val file = imageStore.resolve(entry.managedImage)
                ?: return failure(ArchiveErrorCode.MISSING_MEMBER, "Managed image ${entry.managedImage.imageId} is missing")
            val extension = file.extension.lowercase()
            if (extension !in setOf("jpg", "jpeg", "png", "webp")) return failure(ArchiveErrorCode.INVALID_MANIFEST, "Unsupported managed image extension")
            ArchiveImage(entry.managedImage.imageId, "images/${entry.managedImage.imageId}.$extension",
                if (extension in setOf("jpg", "jpeg")) "image/jpeg" else "image/$extension", file.length(), sha256(file))
        }
        val manifest = ArchiveCodec.manifest(archiveId, createdAtEpochMs, applicationId, versionName, versionCode, entries, images).toString(2).toByteArray()
        val temporary = File(output.parentFile ?: appContext.cacheDir, ".${output.name}-${UUID.randomUUID()}.tmp")
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                zip.putNextEntry(ZipEntry(ArchiveCodec.MANIFEST)); zip.write(manifest); zip.closeEntry()
                entries.zip(images).forEach { (entry, image) ->
                    val file = checkNotNull(imageStore.resolve(entry.managedImage))
                    val crc = CRC32().apply { file.inputStream().use { input -> updateStream(input) } }
                    zip.putNextEntry(ZipEntry(image.archivePath).apply { method = ZipEntry.STORED; size = file.length(); compressedSize = size; this.crc = crc.value })
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            if (!temporary.renameTo(output)) throw IOException("Could not install completed archive")
        } finally { temporary.delete() }
        ArchiveResult.Success(ArchiveExportInfo(archiveId, entries.size, images.size, sha256(output), output))
    } catch (error: Exception) {
        failure(ArchiveErrorCode.STORAGE_UNAVAILABLE, error.message ?: "Export failed")
    }

    fun validate(archive: File, decisions: Map<String, ArchiveConflictDecision> = emptyMap()): ArchiveResult<ValidatedImportPlan> {
        val (parsed, errors) = ArchiveCodec.validate(archive)
        if (parsed == null) return ArchiveResult.Failure(errors)
        val local = repository.entryIdsByImageId()
        val entryIds = local.values.toSet()
        val conflicts = parsed.entries.mapNotNull { entry ->
            val entryExists = entry.entryId in entryIds
            val imageExists = entry.managedImage.imageId in local
            if (entryExists || imageExists) ArchiveConflict(entry.entryId, entry.managedImage.imageId, entryExists, imageExists) else null
        }
        val blocking = conflicts.filter { decisions[it.entryId] != ArchiveConflictDecision.KEEP_EXISTING }
        if (blocking.isNotEmpty()) return ArchiveResult.Failure(blocking.map {
            ArchiveError(ArchiveErrorCode.CONFLICT, "Conflict defaults to ABORT_ARCHIVE: ${it.entryId}")
        })
        val skipped = conflicts.map { it.entryId }.toSet()
        val plannedEntries = parsed.entries.filterNot { it.entryId in skipped }
        val imageIds = plannedEntries.map { it.managedImage.imageId }.toSet()
        val plannedImages = parsed.images.filter { it.imageId in imageIds }
        val preview = ArchivePreview(parsed.archiveId, parsed.entries.size, plannedEntries.size, plannedImages.sumOf { it.sizeBytes }, conflicts)
        return ArchiveResult.Success(ValidatedImportPlan(sha256(archive), revision(local), preview, archive, plannedEntries, plannedImages))
    }

    fun commit(plan: ValidatedImportPlan): ArchiveResult<ArchiveImportInfo> {
        if (sha256(plan.archive) != plan.archiveHash) return failure(ArchiveErrorCode.STALE_PLAN, "Archive changed after validation")
        if (revision(repository.entryIdsByImageId()) != plan.localRevision) return failure(ArchiveErrorCode.STALE_PLAN, "Local album changed after validation")
        if (plan.entries.isEmpty()) return ArchiveResult.Success(ArchiveImportInfo(emptyList(), 0))
        val stage = File(appContext.cacheDir, "archive-stage-${UUID.randomUUID()}")
        val installed = mutableListOf<File>()
        try {
            stage.mkdirs()
            ZipFile(plan.archive).use { zip ->
                plan.images.forEach { image ->
                    val staged = File(stage, stagedName(image))
                    zip.getInputStream(checkNotNull(zip.getEntry(image.archivePath))).use { input ->
                        FileOutputStream(staged).use { output -> input.copyTo(output); output.flush(); output.fd.sync() }
                    }
                }
            }
            val targets = plan.images.associateWith { File(appContext.filesDir, "images/${stagedName(it)}") }
            writeJournal(plan.entries.map { it.entryId }, targets.values.map { it.absolutePath })
            targets.entries.forEachIndexed { index, (image, target) ->
                target.parentFile?.mkdirs()
                if (target.exists() || !File(stage, stagedName(image)).renameTo(target)) throw IOException("Could not install image ${image.imageId}")
                installed += target
                if (failurePoint == ImportFailurePoint.AFTER_FIRST_FILE && index == 0) error("Injected file failure")
            }
            if (failurePoint == ImportFailurePoint.BEFORE_METADATA_COMMIT) error("Injected metadata failure")
            val targetById = targets.mapKeys { it.key.imageId }
            val imported = plan.entries.map { entry ->
                val target = checkNotNull(targetById[entry.managedImage.imageId])
                entry.copy(managedImage = ManagedImageRef(entry.managedImage.imageId, target.relativeTo(appContext.filesDir).path))
            }
            repository.importAtomically(imported)
            journal.delete(); stage.deleteRecursively()
            return ArchiveResult.Success(ArchiveImportInfo(imported.map { it.entryId }, plan.preview.requiredBytes))
        } catch (error: Exception) {
            installed.forEach(File::delete); journal.delete(); stage.deleteRecursively()
            return failure(ArchiveErrorCode.COMMIT_FAILED, error.message ?: "Atomic import failed")
        }
    }

    private fun recoverInterruptedImport() {
        if (!journal.isFile) return
        try {
            val json = JSONObject(journal.readText())
            val ids = json.getJSONArray("entry_ids").strings()
            if (!repository.entryIdsByImageId().values.toSet().containsAll(ids)) {
                json.getJSONArray("installed_paths").strings().forEach { File(it).delete() }
            }
        } finally { journal.delete() }
    }
    private fun writeJournal(ids: List<String>, paths: List<String>) {
        val temp = File(journal.parentFile, journal.name + ".tmp")
        temp.writeText(JSONObject().put("entry_ids", JSONArray(ids)).put("installed_paths", JSONArray(paths)).toString())
        if (!temp.renameTo(journal)) throw IOException("Could not write recovery journal")
    }
    private fun stagedName(image: ArchiveImage) = image.imageId + "." + image.archivePath.substringAfterLast('.')
    private fun revision(values: Map<String, String>) = values.toSortedMap().entries.joinToString("|") { "${it.key}:${it.value}" }.toByteArray().sha256()
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
    private fun CRC32.updateStream(input: java.io.InputStream) { val b = ByteArray(DEFAULT_BUFFER_SIZE); while (true) { val n = input.read(b); if (n < 0) break; update(b, 0, n) } }
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun <T> failure(code: ArchiveErrorCode, message: String): ArchiveResult<T> = ArchiveResult.Failure(listOf(ArchiveError(code, message)))
}
