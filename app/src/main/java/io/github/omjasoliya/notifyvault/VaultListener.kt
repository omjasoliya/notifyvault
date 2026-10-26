package io.github.omjasoliya.notifyvault

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class VaultListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return

        // WhatsApp posts placeholders such as "3 new messages" and "Checking for new messages".
        if (title == "WhatsApp" || SUMMARY.matches(text)) return

        Db.get(this).addMessage(title, text, sbn.postTime)
        // New notification usually means a new file may have arrived too.
        Thread { MediaScanner.scan(applicationContext) }.start()
    }

    companion object {
        private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val SUMMARY = Regex("\\d+ new messages?.*")
    }
}
