package com.northphoenix.hairdresserclientmanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.northphoenix.hairdresserclientmanager.data.SharedContacts
import kotlinx.coroutines.launch

/**
 * Where a contact shared from another app arrives, such as a contact card from WhatsApp: "New Client" in
 * Android's share menu. It shows nothing. It reads the contact, leaves it for the app
 * ([com.northphoenix.hairdresserclientmanager.core.AppContainer.sharedContact]) and brings the app to
 * the front, as its icon does, where it opens as a new Client once the Stylist is signed in.
 */
class ShareContactActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val contact = SharedContacts.read(this@ShareContactActivity, intent)

            (application as HcmApplication).container.sharedContact.value = contact
            // The app's own task, not the one of the app that shared: what the Stylist had open there stays.
            startActivity(Intent(this@ShareContactActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }
}
