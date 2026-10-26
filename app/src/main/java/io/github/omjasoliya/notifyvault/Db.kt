package io.github.omjasoliya.notifyvault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Entry(
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

    fun entries(filter: String): List<Entry> {
        val like = "%$filter%"
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT sender, body, ts FROM messages WHERE sender LIKE ? OR body LIKE ? ORDER BY ts DESC LIMIT 500",
            arrayOf(like, like),
        ).use { c ->
            while (c.moveToNext()) out.add(Entry(c.getString(0), c.getString(1), c.getLong(2)))
        }
        readableDatabase.rawQuery(
            "SELECT name, path, ts FROM media WHERE name LIKE ? ORDER BY ts DESC LIMIT 500",
            arrayOf(like),
        ).use { c ->
            while (c.moveToNext()) out.add(Entry("[file] " + c.getString(0), "", c.getLong(2), c.getString(1)))
        }
        return out.sortedByDescending { it.ts }
    }

    /** Deletes rows older than [cutoff]; returns file paths whose rows were removed. */
    fun purgeOlderThan(cutoff: Long): List<String> {
        writableDatabase.delete("messages", "ts < ?", arrayOf(cutoff.toString()))
        val paths = ArrayList<String>()
        readableDatabase.rawQuery("SELECT path FROM media WHERE ts < ?", arrayOf(cutoff.toString())).use { c ->
            while (c.moveToNext()) paths.add(c.getString(0))
        }
        writableDatabase.delete("media", "ts < ?", arrayOf(cutoff.toString()))
        return paths
    }

    fun mediaTotalSize(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(size), 0) FROM media", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    /** Removes the oldest media row and returns its file path, or null if there is none. */
    fun popOldestMedia(): String? {
        val row = readableDatabase.rawQuery("SELECT id, path FROM media ORDER BY ts ASC LIMIT 1", null).use {
            if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
        } ?: return null
        writableDatabase.delete("media", "id = ?", arrayOf(row.first.toString()))
        return row.second
    }

    fun clearAll(): List<String> {
        val paths = ArrayList<String>()
        readableDatabase.rawQuery("SELECT path FROM media", null).use { c ->
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
