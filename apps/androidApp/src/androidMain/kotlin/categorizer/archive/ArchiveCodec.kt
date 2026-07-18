package categorizer.archive

import categorizer.domain.AlbumEntry
import categorizer.domain.CategoryIdentity
import categorizer.domain.IdentitySource
import categorizer.domain.ManagedImageRef
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

internal data class ParsedArchive(val archiveId: String, val entries: List<AlbumEntry>, val images: List<ArchiveImage>)

internal object ArchiveCodec {
    const val FORMAT = "categorizer-album-archive"
    const val VERSION = "2.0.0"
    const val LEGACY_VERSION = "1.0.0"
    const val MANIFEST = "manifest.json"
    const val MAX_COUNT = 10_000
    const val MAX_MANIFEST = 10L * 1024 * 1024
    const val MAX_IMAGE = 50L * 1024 * 1024
    const val MAX_TOTAL = 10L * 1024 * 1024 * 1024
    private val imagePath = Regex("^images/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(jpg|jpeg|png|webp)$")
    private val safeId = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val classId = Regex("^[a-z0-9][a-z0-9-]*$")
    private val sha = Regex("^[a-f0-9]{64}$")

    fun validate(file: File): Pair<ParsedArchive?, List<ArchiveError>> {
        val errors = mutableListOf<ArchiveError>()
        try {
            ZipFile(file).use { zip ->
                val members = zip.entries().asSequence().toList()
                val names = members.map { it.name }
                if (names.size != names.distinct().size) errors += duplicate("duplicate ZIP member names")
                names.filterNot { it == MANIFEST || safeImagePath(it) }.forEach { errors += unsafe("unsafe archive member path: $it") }
                if (members.any { it.isDirectory }) errors += unsafe("directory members are forbidden")
                val manifestMembers = members.filter { it.name == MANIFEST }
                if (manifestMembers.size != 1) errors += invalid("archive must contain exactly one manifest.json")
                if (errors.isNotEmpty()) return null to errors
                val manifestMember = manifestMembers.single()
                if (manifestMember.size !in 0..MAX_MANIFEST) return null to listOf(limit("manifest.json exceeds 10 MiB"))
                val manifestBytes = zip.getInputStream(manifestMember).use { it.readLimited(MAX_MANIFEST) }
                val parsed = parse(JSONObject(manifestBytes.toString(Charsets.UTF_8)), errors)
                    ?: return null to errors
                if (errors.isNotEmpty()) return null to errors
                val declared = parsed.images.associateBy { it.archivePath }
                val actual = names.filter { it != MANIFEST }.toSet()
                (declared.keys - actual).forEach { errors += ArchiveError(ArchiveErrorCode.MISSING_MEMBER, "missing declared image: $it") }
                (actual - declared.keys).forEach { errors += ArchiveError(ArchiveErrorCode.EXTRA_MEMBER, "undeclared archive member: $it") }
                var observedTotal = 0L
                parsed.images.forEach { image ->
                    val member = zip.getEntry(image.archivePath) ?: return@forEach
                    if (member.method != ZipEntry.STORED) errors += invalid("image member must use ZIP_STORED: ${image.archivePath}")
                    if (member.size > MAX_IMAGE) errors += limit("observed image exceeds 50 MiB: ${image.archivePath}")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    zip.getInputStream(member).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > MAX_IMAGE) { errors += limit("observed image exceeds 50 MiB"); break }
                            digest.update(buffer, 0, read)
                        }
                    }
                    observedTotal += count
                    if (count != image.sizeBytes) errors += invalid("image size mismatch: ${image.archivePath}")
                    if (digest.finishHex() != image.sha256) errors += ArchiveError(ArchiveErrorCode.CHECKSUM_MISMATCH, "image SHA-256 mismatch: ${image.archivePath}")
                }
                if (observedTotal > MAX_TOTAL) errors += limit("total observed image bytes exceed 10 GiB")
                return if (errors.isEmpty()) parsed to emptyList() else null to errors
            }
        } catch (error: Exception) {
            return null to listOf(ArchiveError(ArchiveErrorCode.UNREADABLE_ARCHIVE, error.message ?: "Archive is unreadable"))
        }
    }

    fun manifest(id: String, created: Long, applicationId: String, versionName: String, versionCode: Int, entries: List<AlbumEntry>, images: List<ArchiveImage>) = JSONObject()
        .put("format", FORMAT).put("archive_schema_version", VERSION).put("archive_id", id).put("created_at_epoch_ms", created)
        .put("exporting_app", JSONObject().put("application_id", applicationId).put("version_name", versionName).put("version_code", versionCode))
        .put("entry_count", entries.size).put("image_count", images.size)
        .put("entries", JSONArray(entries.map(::entryJson))).put("image_files", JSONArray(images.map(::imageJson)))

    private fun parse(json: JSONObject, errors: MutableList<ArchiveError>): ParsedArchive? = try {
        requireKeys(json, setOf("format", "archive_schema_version", "archive_id", "created_at_epoch_ms", "exporting_app", "entry_count", "image_count", "entries", "image_files"), errors)
        if (json.getString("format") != FORMAT) errors += invalid("format is invalid")
        val version = json.getString("archive_schema_version")
        if (version !in setOf(LEGACY_VERSION, VERSION)) errors += ArchiveError(ArchiveErrorCode.UNSUPPORTED_VERSION, "archive_schema_version must be $LEGACY_VERSION or $VERSION")
        val archiveId = json.getString("archive_id")
        if (!safeId.matches(archiveId)) errors += invalid("archive_id is invalid")
        val entryArray = json.getJSONArray("entries")
        val imageArray = json.getJSONArray("image_files")
        if (entryArray.length() > MAX_COUNT || imageArray.length() > MAX_COUNT) errors += limit("archive count exceeds 10000")
        if (json.getInt("entry_count") != entryArray.length() || json.getInt("image_count") != imageArray.length()) errors += invalid("declared counts do not match arrays")
        val images = (0 until imageArray.length()).map { i ->
            val value = imageArray.getJSONObject(i)
            val image = ArchiveImage(value.getString("image_id"), value.getString("relative_path"), value.getString("media_type"), value.getLong("size_bytes"), value.getString("sha256"))
            if (!safeId.matches(image.imageId)) errors += invalid("image_id is invalid")
            if (!safeImagePath(image.archivePath)) errors += unsafe("unsafe declared image path: ${image.archivePath}")
            if (!mediaMatches(image.mediaType, image.archivePath)) errors += invalid("media type does not match extension: ${image.archivePath}")
            if (image.sizeBytes !in 1..MAX_IMAGE) errors += limit("declared image exceeds 50 MiB")
            if (!sha.matches(image.sha256)) errors += invalid("invalid SHA-256")
            image
        }
        val imageById = images.associateBy { it.imageId }
        val entries = (0 until entryArray.length()).map { i ->
            val value = entryArray.getJSONObject(i)
            val identity = value.getJSONObject("confirmed_identity")
            val imageId = value.getString("image_id")
            val image = imageById[imageId] ?: throw IllegalArgumentException("entry references missing image $imageId")
            val parsedIdentity = if (version == LEGACY_VERSION) legacyIdentity(identity) else categoryIdentity(identity)
            if (!classId.matches(parsedIdentity.classId)) errors += invalid("class_id is invalid")
            AlbumEntry(value.getString("entry_id"), ManagedImageRef(imageId, image.archivePath), parsedIdentity,
                value.getString("album_date"), value.getBoolean("is_favorite"), value.getString("notes"),
                value.getLong("created_at_epoch_ms"), value.getLong("updated_at_epoch_ms"), value.getInt("entry_schema_version"))
        }
        if (entries.map { it.entryId }.distinct().size != entries.size) errors += duplicate("entry_id values must be unique")
        if (entries.any { !safeId.matches(it.entryId) || it.schemaVersion <= 0 }) errors += invalid("entry ID or schema version is invalid")
        if (entries.map { it.managedImage.imageId }.distinct().size != entries.size) errors += duplicate("entry image_id references must be unique")
        if (images.map { it.imageId }.distinct().size != images.size) errors += duplicate("image_id values must be unique")
        if (images.map { it.archivePath }.distinct().size != images.size) errors += duplicate("image relative paths must be unique")
        if (entries.map { it.managedImage.imageId }.toSet() != images.map { it.imageId }.toSet()) errors += invalid("entries and images must form a one-to-one mapping")
        if (images.sumOf { it.sizeBytes } > MAX_TOTAL) errors += limit("total declared image bytes exceed 10 GiB")
        ParsedArchive(archiveId, entries, images)
    } catch (error: Exception) {
        errors += invalid("manifest.json is invalid: ${error.message}")
        null
    }

    private fun requireKeys(json: JSONObject, allowed: Set<String>, errors: MutableList<ArchiveError>) {
        val keys = json.keys().asSequence().toSet()
        if (keys != allowed) errors += invalid("manifest root fields do not match archive schema")
    }
    private fun entryJson(entry: AlbumEntry) = JSONObject().put("entry_id", entry.entryId).put("entry_schema_version", entry.schemaVersion).put("image_id", entry.managedImage.imageId)
        .put("confirmed_identity", identityJson(entry.confirmedIdentity))
        .put("album_date", entry.albumDate).put("is_favorite", entry.isFavorite).put("notes", entry.notes)
        .put("created_at_epoch_ms", entry.createdAtEpochMs).put("updated_at_epoch_ms", entry.updatedAtEpochMs)
    private fun identityJson(identity: CategoryIdentity) = JSONObject()
        .put("category_id", identity.categoryId).put("class_id", identity.classId)
        .put("scientific_name", identity.scientificName ?: JSONObject.NULL)
        .put("display_name", identity.displayName).put("alternate_names", JSONArray(identity.alternateNames))
        .put("attributes", JSONObject(identity.attributes)).put("source", identity.source.name)
    private fun categoryIdentity(value: JSONObject): CategoryIdentity {
        require(value.keys().asSequence().toSet() == setOf("category_id", "class_id", "scientific_name", "display_name", "alternate_names", "attributes", "source")) {
            "confirmed_identity fields do not match version 2 schema"
        }
        val names = value.getJSONArray("alternate_names")
        val attributes = value.getJSONObject("attributes")
        return CategoryIdentity(
            value.getString("category_id"), value.getString("class_id"),
            value.optString("scientific_name").takeUnless { value.isNull("scientific_name") || it.isBlank() },
            value.getString("display_name"), (0 until names.length()).map(names::getString),
            attributes.keys().asSequence().associateWith(attributes::getString),
            IdentitySource.valueOf(value.getString("source"))
        )
    }
    private fun legacyIdentity(value: JSONObject) = CategoryIdentity(
        categoryId = "cars",
        classId = value.getString("class_id"),
        scientificName = "${value.getString("make")} ${value.getString("model")}",
        displayName = value.getString("display_name"),
        attributes = listOfNotNull(
            value.optString("generation_label").takeIf(String::isNotEmpty)?.let { "generation_label" to it },
            value.optString("approximate_year_range").takeIf(String::isNotEmpty)?.let { "approximate_year_range" to it }
        ).toMap(),
        source = IdentitySource.valueOf(value.getString("source"))
    )
    private fun imageJson(image: ArchiveImage) = JSONObject().put("image_id", image.imageId).put("relative_path", image.archivePath).put("media_type", image.mediaType).put("size_bytes", image.sizeBytes).put("sha256", image.sha256)
    private fun safeImagePath(value: String) = value.length <= 240 && imagePath.matches(value) && ".." !in value && '\\' !in value
    private fun mediaMatches(type: String, path: String) = when (type) { "image/jpeg" -> path.endsWith(".jpg") || path.endsWith(".jpeg"); "image/png" -> path.endsWith(".png"); "image/webp" -> path.endsWith(".webp"); else -> false }
    private fun java.io.InputStream.readLimited(limit: Long): ByteArray { val output = ByteArrayOutputStream(); val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0L; while (true) { val read = read(buffer); if (read < 0) break; total += read; if (total > limit) throw IOException("member exceeds limit"); output.write(buffer, 0, read) }; return output.toByteArray() }
    private fun MessageDigest.finishHex() = digest().joinToString("") { "%02x".format(it) }
    private fun invalid(message: String) = ArchiveError(ArchiveErrorCode.INVALID_MANIFEST, message)
    private fun duplicate(message: String) = ArchiveError(ArchiveErrorCode.DUPLICATE_VALUE, message)
    private fun unsafe(message: String) = ArchiveError(ArchiveErrorCode.UNSAFE_PATH, message)
    private fun limit(message: String) = ArchiveError(ArchiveErrorCode.RESOURCE_LIMIT, message)
}
