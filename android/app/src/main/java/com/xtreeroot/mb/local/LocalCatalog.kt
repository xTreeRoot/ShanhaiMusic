package com.xtreeroot.mb.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.xtreeroot.mb.music.Track
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal data class LocalTrack(
    val track: Track,
    val album: String,
    val albumArtist: String,
    val year: String,
    val disc: Int,
    val number: Int,
    val folderId: String? = null,
    val addedAt: Long = 0,
)

internal data class LocalTrackActivity(val trackId: String, val likedAt: Long, val playedAt: Long)

internal class LocalCatalog(context: Context, databaseName: String = "local-library.db") : SQLiteOpenHelper(context, databaseName, null, 3) {
    private val root = File(context.filesDir, "local-media")
    private val audio = File(root, "audio")
    private val artwork = File(root, "artwork")
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL("""CREATE TABLE audio (
            id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, duration INTEGER NOT NULL,
            album TEXT NOT NULL, album_artist TEXT NOT NULL, year TEXT NOT NULL, disc INTEGER NOT NULL,
            track_number INTEGER NOT NULL, artwork INTEGER NOT NULL, lyrics TEXT NOT NULL DEFAULT '',
            added INTEGER NOT NULL, folder_id TEXT
        )""")
        createActivityTable(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createActivityTable(database)
            database.execSQL("""INSERT INTO track_activity(track_id, liked, played)
                SELECT '$localSourceId:' || id, CASE WHEN favorite = 1 THEN max(added, 1) ELSE 0 END, played
                FROM audio WHERE favorite = 1 OR played > 0""")
        }
        if (oldVersion < 3) database.execSQL("ALTER TABLE audio ADD COLUMN folder_id TEXT")
    }

    private fun createActivityTable(database: SQLiteDatabase) {
        database.execSQL("""CREATE TABLE track_activity (
            track_id TEXT PRIMARY KEY NOT NULL, liked INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0
        )""")
    }

    suspend fun list(): List<LocalTrack> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT * FROM audio ORDER BY added DESC, id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTrack()) }
        }
    }

    fun get(identifier: String): LocalTrack? = readableDatabase.rawQuery("SELECT * FROM audio WHERE id = ?",
        arrayOf(identifier)).use { cursor -> if (cursor.moveToFirst()) cursor.toTrack() else null }

    fun write(track: LocalTrack, picture: ByteArray?, lyrics: String = "") {
        val id = track.track.sourceTrackId
        val cover = picture?.let {
            artwork.mkdirs()
            image(id).apply { writeBytes(it) }
        }
        try {
            writableDatabase.insertOrThrow("audio", null, ContentValues().apply {
                put("id", id)
                put("title", track.track.title)
                put("artist", track.track.artist)
                put("duration", track.track.durationMs)
                put("album", track.album)
                put("album_artist", track.albumArtist)
                put("year", track.year)
                put("disc", track.disc)
                put("track_number", track.number)
                put("folder_id", track.folderId)
                put("artwork", if (cover != null) 1 else 0)
                put("lyrics", lyrics)
                put("added", track.addedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            })
        } catch (failure: Exception) {
            cover?.delete()
            throw failure
        }
        revision.update { it + 1 }
    }

    fun restoreMetadata(id: String, metadata: org.json.JSONObject) {
        writableDatabase.update("audio", ContentValues().apply {
            put("title", metadata.getString("title")); put("artist", metadata.optString("artist"))
            put("album", metadata.optString("album")); put("album_artist", metadata.optString("albumArtist"))
            put("year", metadata.optString("year")); put("track_number", metadata.optInt("trackNumber"))
            put("disc", metadata.optInt("discNumber")); put("duration", metadata.optLong("duration"))
        }, "id = ?", arrayOf(id))
        revision.update { it + 1 }
    }

    suspend fun activity(): List<LocalTrackActivity> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT track_id, liked, played FROM track_activity", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(LocalTrackActivity(cursor.getString(0), cursor.getLong(1), cursor.getLong(2))) }
        }
    }

    suspend fun toggleFavorite(trackId: String) = updateActivity(trackId, "liked = CASE WHEN liked > 0 THEN 0 ELSE ? END", System.currentTimeMillis())

    suspend fun recordPlay(trackId: String, playedAt: Long) = updateActivity(trackId, "played = max(played, ?)", playedAt)

    private suspend fun updateActivity(trackId: String, assignment: String, time: Long) = withContext(Dispatchers.IO) {
        writableDatabase.transaction {
            execSQL("INSERT OR IGNORE INTO track_activity(track_id) VALUES (?)", arrayOf(trackId))
            execSQL("UPDATE track_activity SET $assignment WHERE track_id = ?", arrayOf<Any>(time, trackId))
        }
        revision.update { it + 1 }
        Unit
    }

    suspend fun restoreActivity(entries: List<LocalTrackActivity>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        writableDatabase.transaction {
            for (entry in entries) execSQL("INSERT OR IGNORE INTO track_activity(track_id, liked, played) VALUES (?, ?, ?)",
                arrayOf<Any>(entry.trackId, entry.likedAt, entry.playedAt))
        }
        revision.update { it + 1 }
        Unit
    }

    private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try { block(); setTransactionSuccessful() } finally { endTransaction() }
    }

    suspend fun delete(identifier: String) = withContext(Dispatchers.IO) {
        if (writableDatabase.delete("audio", "id = ?", arrayOf(identifier)) > 0) {
            file(identifier).delete()
            image(identifier).delete()
            revision.update { it + 1 }
        }
    }

    fun temporaryFile(): File {
        audio.mkdirs()
        return File.createTempFile("import-", ".part", audio)
    }

    fun file(identifier: String): File {
        require(contentId.matches(identifier))
        return File(audio, identifier)
    }

    fun lyrics(identifier: String): String = readableDatabase.rawQuery("SELECT lyrics FROM audio WHERE id = ?",
        arrayOf(identifier)).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }

    fun lyricDocument(identifier: String): Pair<String, Long>? = readableDatabase.rawQuery(
        "SELECT lyrics, duration FROM audio WHERE id = ?", arrayOf(identifier),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null }

    fun writeLyrics(identifier: String, value: String) {
        check(writableDatabase.update("audio", ContentValues().apply { put("lyrics", value) }, "id = ?",
            arrayOf(identifier)) == 1) { "曲目不存在" }
        revision.update { it + 1 }
    }

    private fun image(identifier: String) = File(artwork, identifier)

    private fun Cursor.toTrack(): LocalTrack {
        fun text(field: String): String = getString(getColumnIndexOrThrow(field))
        fun number(field: String): Long = getLong(getColumnIndexOrThrow(field))
        val id = text("id")
        val cover = if (number("artwork") > 0) Uri.fromFile(image(id)).toString() else null
        val track = Track(localSourceId, id, text("title"), text("artist"), cover, number("duration"))
        return LocalTrack(track, text("album"), text("album_artist"), text("year"), number("disc").toInt(),
            number("track_number").toInt(), getString(getColumnIndexOrThrow("folder_id")), number("added"))
    }

    companion object {
        private val contentId = Regex("[0-9a-f]{64}")
    }
}

internal const val localSourceId = "local"
