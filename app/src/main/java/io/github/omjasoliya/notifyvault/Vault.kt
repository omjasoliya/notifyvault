package io.github.omjasoliya.notifyvault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

const val RETENTION_DAYS = 7L
const val MEDIA_CAP_BYTES = 2L * 1024 * 1024 * 1024

object Prefs {
    fun get(ctx: Context) = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun treeUri(ctx: Context): String? = get(ctx).getString("tree", null)

    fun setTree(ctx: Context, uri: String) {
        get(ctx).edit().putString("tree", uri).putLong("lastScan", System.currentTimeMillis()).apply()
    }
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
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24 * 60 * 60 * 1000
        db.purgeOlderThan(cutoff).forEach { File(it).delete() }

        // Enforce the size cap by dropping the oldest files first.
        while (db.mediaTotalSize() > MEDIA_CAP_BYTES) {
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
