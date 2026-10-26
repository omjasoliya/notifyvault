package io.github.omjasoliya.notifyvault

import android.app.Application
import com.google.android.material.color.DynamicColors

class NotifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Use the wallpaper-based colours on Android 12+, the teal theme elsewhere.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
