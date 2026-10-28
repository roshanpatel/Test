package app.jolt

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings

/**
 * A family filter for the whole phone, using Android's built-in Private DNS setting. It
 * blocks adult sites in every browser and app. Jolt can't switch it on itself, but it can
 * walk the user through it and guard the setting while settings are locked.
 */
object WebFilter {
    /** Cloudflare's free family resolver: blocks malware and adult content. */
    const val HOSTNAME = "family.cloudflare-dns.com"

    /** The Private DNS hostname in use, or null if it's off, automatic, or unknown. */
    fun activeHostname(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return null
        return connectivity.getLinkProperties(network)?.privateDnsServerName
    }

    fun copyHostname(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Private DNS", HOSTNAME))
    }

    /** Opens Network & internet, where Private DNS lives. */
    fun openSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
