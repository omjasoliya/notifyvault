package io.github.omjasoliya.notifyvault

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: EntryAdapter
    private lateinit var search: TextInputEditText
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var emptyView: TextView
    private var kind = Kind.ALL

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Prefs.setTree(this, uri.toString())
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        adapter = EntryAdapter(onClick = ::onEntryClick, onLongClick = ::confirmDeleteOne)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        emptyView = findViewById(R.id.emptyView)

        search = findViewById(R.id.search)
        search.doAfterTextChanged { refresh() }

        findViewById<ChipGroup>(R.id.chips).setOnCheckedStateChangeListener { _, ids ->
            kind = when (ids.firstOrNull()) {
                R.id.chipMessages -> Kind.MESSAGE
                R.id.chipPhotos -> Kind.PHOTO
                R.id.chipVideos -> Kind.VIDEO
                R.id.chipFiles -> Kind.FILE
                else -> Kind.ALL
            }
            refresh()
        }

        findViewById<MaterialButton>(R.id.notifButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.folderButton).setOnClickListener {
            pickTree.launch(Uri.parse(WHATSAPP_MEDIA_URI))
        }
        findViewById<MaterialButton>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        swipe = findViewById(R.id.swipe)
        swipe.setOnRefreshListener {
            Thread {
                MediaScanner.scan(applicationContext)
                runOnUiThread {
                    refresh()
                    swipe.isRefreshing = false
                }
            }.start()
        }

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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_retention -> chooseRetention()
            R.id.action_cap -> chooseCap()
            R.id.action_privacy -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privacy_title)
                .setMessage(R.string.privacy_body)
                .setPositiveButton(R.string.ok, null)
                .show()
            R.id.action_delete_all -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_all_title)
                .setMessage(R.string.delete_all_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    Purge.everything(this)
                    refresh()
                }
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun refresh() {
        val notifOk = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        val folderOk = Prefs.treeUri(this) != null
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        setRow(R.id.notifIcon, R.id.notifText, R.id.notifButton, notifOk, R.string.notif_ok, R.string.notif_missing)
        setRow(R.id.folderIcon, R.id.folderText, R.id.folderButton, folderOk, R.string.folder_ok, R.string.folder_missing)
        setRow(R.id.batteryIcon, R.id.batteryText, R.id.batteryButton, batteryOk, R.string.battery_ok, R.string.battery_missing)

        val setupDone = notifOk && folderOk
        findViewById<MaterialCardView>(R.id.setupCard).visibility = if (setupDone && batteryOk) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.statusLine).text = getString(
            R.string.status_line, Prefs.retentionDays(this), Prefs.capGb(this),
        )

        val query = search.text?.toString().orEmpty()
        val items = Db.get(this).entries(query, kind)
        adapter.submitList(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        emptyView.setText(
            when {
                query.isNotEmpty() || kind != Kind.ALL -> R.string.empty_search
                !setupDone -> R.string.empty_setup
                else -> R.string.empty_list
            }
        )
    }

    private fun setRow(iconId: Int, textId: Int, buttonId: Int, ok: Boolean, okText: Int, missingText: Int) {
        val icon = findViewById<ImageView>(iconId)
        icon.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning)
        val attr = if (ok) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorError
        icon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(icon, attr))
        findViewById<TextView>(textId).setText(if (ok) okText else missingText)
        findViewById<View>(buttonId).visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun onEntryClick(e: Entry) {
        if (e.kind == Kind.MESSAGE) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", e.body))
            Snackbar.make(findViewById(android.R.id.content), R.string.copied, Snackbar.LENGTH_SHORT).show()
        } else {
            e.path?.let(::openFile)
        }
    }

    private fun confirmDeleteOne(e: Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_one_title)
            .setMessage(R.string.delete_one_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                Db.get(this).delete(e)?.let { File(it).delete() }
                refresh()
            }
            .show()
    }

    private fun chooseRetention() {
        val days = intArrayOf(1, 3, 7, 14, 30)
        val labels = days.map { resources.getQuantityString(R.plurals.days, it, it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_retention)
            .setSingleChoiceItems(labels, days.indexOf(Prefs.retentionDays(this)).coerceAtLeast(0)) { d, i ->
                Prefs.setRetentionDays(this, days[i])
                Thread { Purge.run(applicationContext) }.start()
                d.dismiss()
                refresh()
            }
            .show()
    }

    private fun chooseCap() {
        val gb = intArrayOf(1, 2, 5, 10)
        val labels = gb.map { "$it GB" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_cap)
            .setSingleChoiceItems(labels, gb.indexOf(Prefs.capGb(this)).coerceAtLeast(0)) { d, i ->
                Prefs.setCapGb(this, gb[i])
                Thread { Purge.run(applicationContext) }.start()
                d.dismiss()
                refresh()
            }
            .show()
    }

    private fun openFile(path: String) {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(findViewById(android.R.id.content), R.string.no_viewer, Snackbar.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val WHATSAPP_MEDIA_URI =
            "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia"
    }
}
