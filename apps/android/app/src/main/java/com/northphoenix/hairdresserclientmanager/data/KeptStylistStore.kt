package com.northphoenix.hairdresserclientmanager.data

import android.content.Context
import com.northphoenix.hairdresserclientmanager.state.KeptStylist
import com.northphoenix.hairdresserclientmanager.state.KeptStylistStore
import kotlinx.serialization.Serializable

/** [KeptStylistStore] in this app's own preferences, deleted at sign-out (see `AppViewModel.forgetDevice`). */
class SharedPreferencesKeptStylistStore(context: Context) : KeptStylistStore {
    private val preferences = context.applicationContext.getSharedPreferences("kept_stylist", Context.MODE_PRIVATE)

    override var kept: KeptStylist?
        get() = KeptStylists.read(preferences.getString(KEPT, null))
        set(value) = preferences.edit().apply { if (value == null) remove(KEPT) else putString(KEPT, KeptStylists.write(value)) }.apply()

    private companion object {
        const val KEPT = "kept"
    }
}

/** [KeptStylist] as the device keeps it: JSON, the Stylist read with the same leniency as the server's answer. */
object KeptStylists {
    fun write(kept: KeptStylist): String = hcmJson.encodeToString(Stored.serializer(), Stored(kept.account, kept.stylist))

    /** Null for nothing kept, and for what this version cannot read. */
    fun read(text: String?): KeptStylist? =
        text?.let { runCatching { hcmJson.decodeFromString(Stored.serializer(), it) }.getOrNull() }?.let { KeptStylist(it.account, it.stylist) }

    @Serializable
    private data class Stored(val account: String, val stylist: Stylist)
}
