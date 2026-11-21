package com.northphoenix.hairdresserclientmanager.data

import android.content.Context
import com.northphoenix.hairdresserclientmanager.state.KeptStylist
import com.northphoenix.hairdresserclientmanager.state.KeptStylistStore
import com.northphoenix.hairdresserclientmanager.state.ListsOf
import kotlinx.serialization.Serializable

/** [KeptStylistStore] in this app's own preferences, deleted at a sign-out the Stylist asks for (see `AppViewModel.forgetDevice`). */
class SharedPreferencesKeptStylistStore(context: Context) : KeptStylistStore {
    private val preferences = context.applicationContext.getSharedPreferences("kept_stylist", Context.MODE_PRIVATE)

    override var kept: KeptStylist?
        get() = KeptStylists.read(preferences.getString(KEPT, null))
        set(value) = preferences.edit().apply { if (value == null) remove(KEPT) else putString(KEPT, KeptStylists.write(value)) }.apply()

    private companion object {
        const val KEPT = "kept"
    }
}

/** [KeptStylist] as the device keeps it: JSON, read leniently. */
object KeptStylists {
    fun write(kept: KeptStylist): String = hcmJson.encodeToString(Stored.serializer(), Stored(kept.account, kept.lists.stylistId, kept.lists.timezone))

    /** Null for nothing kept, and for what this version cannot read. */
    fun read(text: String?): KeptStylist? =
        text?.let { runCatching { hcmJson.decodeFromString(Stored.serializer(), it) }.getOrNull() }?.let { KeptStylist(it.account, ListsOf(it.stylistId, it.timezone)) }

    @Serializable
    private data class Stored(val account: String, val stylistId: String, val timezone: String)
}
