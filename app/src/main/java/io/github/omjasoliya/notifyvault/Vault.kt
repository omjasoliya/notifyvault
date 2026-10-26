package io.github.omjasoliya.notifyvault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

object Prefs {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun get(ctx: Context) = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun treeUri(ctx: Context): String? = get(ctx).getString("tree", null)

    fun setTree(ctx: Context, uri: String) {
        get(ctx).edit().putString("tree", uri).putLong("lastScan", System.currentTimeMillis()).apply()
    }

    fun retentionDays(ctx: Context): Int = get(ctx).getInt("days", 7)

    fun setRetentionDays(ctx: Context, days: Int) = get(ctx).edit().putInt("days", days).apply()

    fun capGb(ctx: Context): Int = get(ctx).getInt("capGb", 2)

    fun setCapGb(ctx: Context, gb: Int) = get(ctx).edit().putInt("capGb", gb).apply()

    fun retentionMs(ctx: Context): Long = retentionDays(ctx) * DAY_MS

    fun capBytes(ctx: Context): Long = capGb(ctx) * 1024L * 1024 * 1024
}

/** Copies new WhatsApp media into app-private storage so it survives "delete for everyone". */
object MediaScanner {
    private val lock = Any()

    fun scan(ctx: Context) {
        synchronized(lock) {
            val prefs = Prefs.get(ctx)
            val uri = prefs.getString("tree", null) ?: return
            val root = DocumentFile.fromTreeUri(ctx, Uri.parse(uri)) ?: return
            val since = prefs.getLong("lastScan", System.currentTimeMillis())
            val started = System.currentTimeMillis()

            copyNew(ctx, root, since)
            // Small overlap so a file written during the scan is not skipped; src is UNIQUE so repeats are ignored.
            prefs.edit().putLong("lastScan", started - 60_000).apply()
        }
    }

    private fun copyNew(ctx: Context, dir: DocumentFile, since: Long) {
        val db = Db.get(ctx)
        val outDir = File(ctx.filesDir, "media").apply { mkdirs() }
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (f.isDirectory) {
                // "Sent" and "Private" hold the user's own files, which are not wanted here.
                if (name != "Sent" && name != "Private") copyNew(ctx, f, since)
                continue
            }
            if (name.startsWith(".") || f.lastModified() <= since) continue
            val src = f.uri.toString()
            if (db.hasMedia(src)) continue

            // Same size as something saved a moment ago: the temp-file-then-rename duplicate.
            if (db.hasSimilarMedia(f.length(), System.currentTimeMillis() - 120_000)) {
                db.addMedia(name, "", 0, src)
                continue
            }

            val dest = File(outDir, "${System.currentTimeMillis()}_$name")
            try {
                ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: continue
                db.addMedia(name, dest.absolutePath, dest.length(), src)
            } catch (e: Exception) {
                dest.delete()
            }
        }
    }
}

object Purge {
    fun run(ctx: Context) {
        val db = Db.get(ctx)
        db.purgeOlderThan(System.currentTimeMillis() - Prefs.retentionMs(ctx)).forEach { File(it).delete() }

        // Enforce the size cap by dropping the oldest files first.
        val cap = Prefs.capBytes(ctx)
        while (db.mediaTotalSize() > cap) {
            val path = db.popOldestMedia() ?: break
            File(path).delete()
        }
    }

    fun everything(ctx: Context) {
        Db.get(ctx).clearAll().forEach { File(it).delete() }
    }
}

class MaintenanceWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        MediaScanner.scan(applicationContext)
        Purge.run(applicationContext)
        return Result.success()
    }
}
