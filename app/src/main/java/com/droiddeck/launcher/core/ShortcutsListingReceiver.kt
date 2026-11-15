package com.droiddeck.launcher.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ShortcutsListingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SteamIntegration.syncShortcutsListing(context)
        // After an update the process is gone until something starts it; bring Remote control back if it was on.
        com.droiddeck.launcher.agent.AgentRemote.start(context)
    }
}
