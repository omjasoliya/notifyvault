package io.github.omjasoliya.notifyvault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object Kind {
    const val ALL = -1
    const val MESSAGE = 0
    const val PHOTO = 1
    const val VIDEO = 2
    const val FILE = 3

    private val PHOTO_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic")
    private val VIDEO_EXT = setOf("mp4", "3gp", "mkv", "webm", "mov")

    fun ofFile(name: String): Int {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in PHOTO_EXT -> PHOTO
            in VIDEO_EXT -> VIDEO
            else -> FILE
        }
    }
}

data class Entry(
    val kind: Int,
    val id: Long,
    val title: String,
    val body: String,
    val ts: Long,
    val path: String? = null,
)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "vault.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "sender TEXT, body TEXT, ts INTEGER, UNIQUE(sender, body, ts))"
        )
        db.execSQL(
            "CREATE TABLE media(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT, path TEXT, ts INTEGER, size INTEGER, src TEXT UNIQUE)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun addMessage(sender: String, body: String, ts: Long) {
        val v = ContentValues().apply {
            put("sender", sender)
            put("body", body)
            put("ts", ts)
        }
        writableDatabase.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** An empty [path] records a source file that was seen but deliberately not copied (a duplicate). */
    fun addMedia(name: String, path: String, size: Long, src: String) {
        val v = ContentValues().apply {
            put("name", name)
            put("path", path)
            put("ts", System.currentTimeMillis())
            put("size", size)
            put("src", src)
        }
        writableDatabase.insertWithOnConflict("media", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun hasMedia(src: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM media WHERE src=?", arrayOf(src)).use { it.moveToFirst() }

    /** WhatsApp writes a temp file and renames it; the same bytes would otherwise be saved twice. */
    fun hasSimilarMedia(size: Long, sinceTs: Long): Boolean =
        size > 0 && readableDatabase.rawQuery(
            "SELECT 1 FROM media WHERE size=? AND ts>=? AND path != ''",
            arrayOf(size.toString(), sinceTs.toString()),
        ).use { it.moveToFirst() }

    fun entries(filter: String, kind: Int): List<Entry> {
        val like = "%$filter%"
        val out = ArrayList<Entry>()
        if (kind == Kind.ALL || kind == Kind.MESSAGE) {
            readableDatabase.rawQuery(
                "SELECT id, sender, body, ts FROM messages WHERE sender LIKE ? OR body LIKE ? ORDER BY ts DESC LIMIT 500",
                arrayOf(like, like),
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(Entry(Kind.MESSAGE, c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
                }
            }
        }
        if (kind != Kind.MESSAGE) {
            readableDatabase.rawQuery(
                "SELECT id, name, path, ts FROM media WHERE path != '' AND name LIKE ? ORDER BY ts DESC LIMIT 500",
                arrayOf(like),
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    val k = Kind.ofFile(name)
                    if (kind == Kind.ALL || kind == k) out.add(Entry(k, c.getLong(0), name, "", c.getLong(3), c.getString(2)))
                }
            }
        }
        return out.sortedByDescending { it.ts }
    }

    /** Deletes one entry; returns its file path if it had one. */
    fun delete(e: Entry): String? {
        if (e.kind == Kind.MESSAGE) {
            writableDatabase.delete("messages", "id = ?", arrayOf(e.id.toString()))
        } else {
            writableDatabase.delete("media", "id = ?", arrayOf(e.id.toString()))
        }
        return e.path
    }

    /** Deletes rows older than [cutoff]; returns file paths whose rows were removed. */
    fun purgeOlderThan(cutoff: Long): List<String> {
        writableDatabase.delete("messages", "ts < ?", arrayOf(cutoff.toString()))
        val paths = ArrayList<String>()
        readableDatabase.rawQuery("SELECT path FROM media WHERE ts < ? AND path != ''", arrayOf(cutoff.toString())).use { c ->
            while (c.moveToNext()) paths.add(c.getString(0))
        }
        writableDatabase.delete("media", "ts < ?", arrayOf(cutoff.toString()))
        return paths
    }

    fun mediaTotalSize(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(size), 0) FROM media", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    /** Removes the oldest saved file's row and returns its path, or null if there is none. */
    fun popOldestMedia(): String? {
        val row = readableDatabase.rawQuery(
            "SELECT id, path FROM media WHERE path != '' ORDER BY ts ASC LIMIT 1", null,
        ).use { if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null } ?: return null
        writableDatabase.delete("media", "id = ?", arrayOf(row.first.toString()))
        return row.second
    }

    fun clearAll(): List<String> {
        val paths = ArrayList<String>()
        readableDatabase.rawQuery("SELECT path FROM media WHERE path != ''", null).use { c ->
            while (c.moveToNext()) paths.add(c.getString(0))
        }
        writableDatabase.delete("messages", null, null)
        writableDatabase.delete("media", null, null)
        return paths
    }

    companion object {
        @Volatile private var instance: Db? = null

        fun get(ctx: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(ctx.applicationContext).also { instance = it }
            }
    }
}
