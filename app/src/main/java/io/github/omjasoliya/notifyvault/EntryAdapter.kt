package io.github.omjasoliya.notifyvault

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView

class EntryAdapter(
    private val onClick: (Entry) -> Unit,
    private val onLongClick: (Entry) -> Unit,
) : ListAdapter<Entry, EntryAdapter.VH>(Diff) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ShapeableImageView = v.findViewById(R.id.thumb)
        val initial: TextView = v.findViewById(R.id.initial)
        val title: TextView = v.findViewById(R.id.title)
        val body: TextView = v.findViewById(R.id.body)
        val time: TextView = v.findViewById(R.id.time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = getItem(position)
        val ctx = h.itemView.context

        h.time.text = DateUtils.getRelativeTimeSpanString(
            e.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        )

        if (e.kind == Kind.MESSAGE) {
            h.title.text = e.title
            h.body.text = e.body
            h.thumb.visibility = View.GONE
            h.initial.visibility = View.VISIBLE
            h.initial.text = e.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
            h.initial.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(avatarColor(e.title))
            }
        } else {
            h.title.text = ctx.getString(
                when (e.kind) {
                    Kind.PHOTO -> R.string.photo
                    Kind.VIDEO -> R.string.video
                    else -> R.string.file
                }
            )
            h.body.text = e.title
            if (e.kind == Kind.FILE) {
                h.thumb.visibility = View.GONE
                h.initial.visibility = View.VISIBLE
                h.initial.text = e.title.substringAfterLast('.', "?").take(4).uppercase()
                h.initial.textSize = 13f
                h.initial.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#546E7A"))
                }
            } else {
                h.initial.visibility = View.GONE
                h.thumb.visibility = View.VISIBLE
                e.path?.let { Thumbs.load(it, e.kind, h.thumb) }
            }
        }
        if (e.kind == Kind.MESSAGE) h.initial.textSize = 20f

        h.itemView.setOnClickListener { onClick(e) }
        h.itemView.setOnLongClickListener {
            onLongClick(e)
            true
        }
    }

    private fun avatarColor(name: String): Int {
        // Dark enough for white text to stay readable.
        val palette = intArrayOf(0xFF00695C.toInt(), 0xFF1565C0.toInt(), 0xFF6A1B9A.toInt(), 0xFFAD1457.toInt(), 0xFFEF6C00.toInt(), 0xFF4E342E.toInt())
        return palette[Math.floorMod(name.hashCode(), palette.size)]
    }

    private object Diff : DiffUtil.ItemCallback<Entry>() {
        override fun areItemsTheSame(a: Entry, b: Entry) = a.kind == b.kind && a.id == b.id
        override fun areContentsTheSame(a: Entry, b: Entry) = a == b
    }
}
