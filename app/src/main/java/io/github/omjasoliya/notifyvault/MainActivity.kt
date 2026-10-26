package io.github.omjasoliya.notifyvault

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var search: EditText
    private var shown: List<Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        status = TextView(this)
        root.addView(status)

        root.addView(button("1. Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("2. Choose WhatsApp media folder") { pickFolder() })
        root.addView(button("Delete everything now") {
            Purge.everything(this)
            refresh()
        })

        search = EditText(this).apply { hint = "Search" }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        root.addView(search)

        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.path?.let(::openFile) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "maintenance",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun pickFolder() {
        val hint = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia"
        )
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra("android.provider.extra.INITIAL_URI", hint)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_TREE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Prefs.setTree(this, uri.toString())
        }
    }

    private fun refresh() {
        val listenerOn = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        val folderOn = Prefs.treeUri(this) != null
        status.text = "Notification access: ${if (listenerOn) "on" else "OFF"}\n" +
            "Media folder: ${if (folderOn) "set" else "NOT set"}\n" +
            "Kept for $RETENTION_DAYS days, media capped at ${MEDIA_CAP_BYTES / (1024 * 1024 * 1024)} GB. Stored only on this phone."

        shown = Db.get(this).entries(search.text.toString())
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            shown.map { "${df.format(Date(it.ts))}\n${it.title}${if (it.body.isNotEmpty()) ": " + it.body else ""}" },
        )
    }

    private fun openFile(path: String) {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    companion object {
        private const val REQ_TREE = 1
    }
}
