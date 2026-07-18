package categorizer.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import categorizer.domain.AlbumEntry
import categorizer.domain.CategoryIdentity
import categorizer.domain.IdentitySource
import categorizer.domain.ManagedImageRef
import org.json.JSONArray
import org.json.JSONObject

internal const val ALBUM_DATABASE_VERSION = 2
internal const val ALBUM_TABLE = "album_entries"

internal class AlbumDatabase(
    context: Context,
    databaseName: String = "categorizer-album.db"
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, ALBUM_DATABASE_VERSION) {
    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(CREATE_ALBUM_TABLE)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion >= 2) {
            database.execSQL("ALTER TABLE $ALBUM_TABLE ADD COLUMN category_id TEXT NOT NULL DEFAULT 'cars'")
            database.execSQL("ALTER TABLE $ALBUM_TABLE ADD COLUMN scientific_name TEXT")
            database.execSQL("ALTER TABLE $ALBUM_TABLE ADD COLUMN alternate_names_json TEXT NOT NULL DEFAULT '[]'")
            database.execSQL("ALTER TABLE $ALBUM_TABLE ADD COLUMN identity_attributes_json TEXT NOT NULL DEFAULT '{}'")
            database.execSQL("UPDATE $ALBUM_TABLE SET scientific_name = make || ' ' || model")
        } else check(oldVersion == newVersion) {
            "No album database migration from version $oldVersion to $newVersion"
        }
    }

    companion object {
        internal val CREATE_ALBUM_TABLE = """
            CREATE TABLE $ALBUM_TABLE (
                entry_id TEXT NOT NULL PRIMARY KEY,
                image_id TEXT NOT NULL,
                image_relative_path TEXT NOT NULL,
                class_id TEXT NOT NULL,
                make TEXT NOT NULL,
                model TEXT NOT NULL,
                generation_label TEXT,
                approximate_year_range TEXT,
                display_name TEXT NOT NULL,
                identity_source TEXT NOT NULL,
                category_id TEXT NOT NULL,
                scientific_name TEXT,
                alternate_names_json TEXT NOT NULL,
                identity_attributes_json TEXT NOT NULL,
                album_date TEXT NOT NULL,
                is_favorite INTEGER NOT NULL CHECK (is_favorite IN (0, 1)),
                notes TEXT NOT NULL,
                created_at_epoch_ms INTEGER NOT NULL,
                updated_at_epoch_ms INTEGER NOT NULL,
                entry_schema_version INTEGER NOT NULL
            )
        """.trimIndent()
    }
}

internal fun AlbumEntry.toValues(): ContentValues = ContentValues(16).apply {
    put("entry_id", entryId)
    put("image_id", managedImage.imageId)
    put("image_relative_path", managedImage.relativePath)
    put("class_id", confirmedIdentity.classId)
    put("make", confirmedIdentity.make)
    put("model", confirmedIdentity.model)
    put("generation_label", confirmedIdentity.generationLabel)
    put("approximate_year_range", confirmedIdentity.approximateYearRange)
    put("display_name", confirmedIdentity.displayName)
    put("identity_source", confirmedIdentity.source.name)
    put("category_id", confirmedIdentity.categoryId)
    put("scientific_name", confirmedIdentity.scientificName)
    put("alternate_names_json", JSONArray(confirmedIdentity.alternateNames).toString())
    put("identity_attributes_json", JSONObject(confirmedIdentity.attributes).toString())
    put("album_date", albumDate)
    put("is_favorite", if (isFavorite) 1 else 0)
    put("notes", notes)
    put("created_at_epoch_ms", createdAtEpochMs)
    put("updated_at_epoch_ms", updatedAtEpochMs)
    put("entry_schema_version", schemaVersion)
}

internal fun Cursor.toAlbumEntry(): AlbumEntry = AlbumEntry(
    entryId = text("entry_id"),
    managedImage = ManagedImageRef(text("image_id"), text("image_relative_path")),
    confirmedIdentity = CategoryIdentity(
        categoryId = text("category_id"),
        classId = text("class_id"),
        scientificName = nullableText("scientific_name"),
        displayName = text("display_name"),
        alternateNames = JSONArray(text("alternate_names_json")).strings(),
        attributes = JSONObject(text("identity_attributes_json")).stringMap() + listOfNotNull(
            nullableText("generation_label")?.let { "generation_label" to it },
            nullableText("approximate_year_range")?.let { "approximate_year_range" to it }
        ).toMap(),
        source = IdentitySource.valueOf(text("identity_source"))
    ),
    albumDate = text("album_date"),
    isFavorite = getInt(getColumnIndexOrThrow("is_favorite")) == 1,
    notes = text("notes"),
    createdAtEpochMs = getLong(getColumnIndexOrThrow("created_at_epoch_ms")),
    updatedAtEpochMs = getLong(getColumnIndexOrThrow("updated_at_epoch_ms")),
    schemaVersion = getInt(getColumnIndexOrThrow("entry_schema_version"))
)

private fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column))

private fun Cursor.nullableText(column: String): String? =
    getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }

private fun JSONArray.strings() = (0 until length()).map(::getString)
private fun JSONObject.stringMap() = keys().asSequence().associateWith(::getString)
