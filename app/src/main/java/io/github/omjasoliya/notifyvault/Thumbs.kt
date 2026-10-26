package io.github.omjasoliya.notifyvault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Decodes small previews off the main thread and caches them. */
object Thumbs {
    private const val SIZE = 192

    private val cache = LruCache<String, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(path: String, kind: Int, view: ImageView) {
        view.tag = path
        view.setImageDrawable(null)
        cache.get(path)?.let {
            view.setImageBitmap(it)
            return
        }
        pool.execute {
            val bmp = decode(path, kind) ?: return@execute
            cache.put(path, bmp)
            main.post { if (view.tag == path) view.setImageBitmap(bmp) }
        }
    }

    private fun decode(path: String, kind: Int): Bitmap? = try {
        if (kind == Kind.VIDEO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(path)
                r.getFrameAtTime(0)?.let { Bitmap.createScaledBitmap(it, SIZE, SIZE, true) }
            } finally {
                r.release()
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }
}
