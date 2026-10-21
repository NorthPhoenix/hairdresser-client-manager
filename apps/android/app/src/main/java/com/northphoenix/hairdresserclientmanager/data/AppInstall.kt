package com.northphoenix.hairdresserclientmanager.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.northphoenix.hairdresserclientmanager.state.AppUpdateStore

/** [AppUpdateStore] in this app's own preferences. */
class SharedPreferencesAppUpdateStore(context: Context) : AppUpdateStore {
    private val preferences = context.applicationContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    override var lastSeenVersionCode: Int?
        get() = preferences.getInt(LAST_SEEN, 0).takeIf { it > 0 }
        set(value) = preferences.edit().apply { if (value == null) remove(LAST_SEEN) else putInt(LAST_SEEN, value) }.apply()

    override var dismissedVersionCode: Int?
        get() = preferences.getInt(DISMISSED, 0).takeIf { it > 0 }
        set(value) = preferences.edit().apply { if (value == null) remove(DISMISSED) else putInt(DISMISSED, value) }.apply()

    private companion object {
        const val LAST_SEEN = "last_seen_version_code"
        const val DISMISSED = "dismissed_version_code"
    }
}

/**
 * Whether the app on this device was installed over an earlier version rather than afresh: Android
 * moves the time of the last update on from the first install's then. False when it cannot be told.
 */
fun Context.wasUpdatedInPlace(): Boolean =
    try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }

        info.lastUpdateTime > info.firstInstallTime
    } catch (_: Exception) {
        false
    }
