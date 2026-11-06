package com.elcoremote.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Helper meant to be run ON THE TV. Many no-name Android TVs hide the stock Android
 * Settings app behind a vendor UI; this opens it directly so Developer options /
 * USB debugging can be enabled, and checks whether ADB is listening on port 5555.
 */
object TvTools {

    private const val PKG = "com.android.settings"

    fun show(activity: Activity) {
        val items = arrayOf(
            "1. Open Android Settings (hidden)",
            "2. Open 'About device' (tap Build number 7×)",
            "3. Open Developer options",
            "4. Check if remote control (ADB) is ready",
        )
        AlertDialog.Builder(activity)
            .setTitle("TV tools — run these on the TV")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launch(activity, listOf(
                        component("$PKG.Settings"),
                        Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")),
                        Intent(Settings.ACTION_SETTINGS),
                    ))
                    1 -> launch(activity, listOf(
                        component("$PKG.Settings\$DeviceInfoSettingsActivity"),
                        component("$PKG.DeviceInfoSettings"),
                        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
                    ))
                    2 -> launch(activity, listOf(
                        component("$PKG.Settings\$DevelopmentSettingsActivity"),
                        component("$PKG.DevelopmentSettings"),
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                    ))
                    3 -> checkAdb(activity)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun component(cls: String) = Intent().setComponent(ComponentName(PKG, cls))

    private fun launch(activity: Activity, candidates: List<Intent>) {
        for (intent in candidates) {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // try the next candidate
            }
        }
        message(activity, "Could not open this screen", "This TV blocks it. Tell Claude which option failed.")
    }

    private fun checkAdb(activity: Activity) {
        Thread {
            val open = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", 5555), 1000); true }
            } catch (_: Exception) {
                false
            }
            val ip = NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress ?: "unknown"
            activity.runOnUiThread {
                if (open) {
                    message(activity, "Ready ✓",
                        "Remote control port 5555 is OPEN.\n\nThis TV's IP: $ip\n\n" +
                        "On your phone: open ELCO Remote → Scan (or Enter IP → $ip → ADB).")
                } else {
                    message(activity, "Not ready yet",
                        "Port 5555 is closed.\n\nThis TV's IP: $ip\n\n" +
                        "Use options 2 and 3 to enable USB debugging, then restart the TV and check again.")
                }
            }
        }.start()
    }

    private fun message(activity: Activity, title: String, text: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }
}
