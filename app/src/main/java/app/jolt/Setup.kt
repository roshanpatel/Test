package app.jolt

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Checks and shortcuts for the system permissions Jolt needs. */
object Setup {
    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = ComponentName(context, JoltService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun batteryUnrestricted(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    fun openAccessibilitySettings(context: Context) {
        start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun requestBatteryExemption(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
        if (!start(context, direct)) start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** App info page, where "Allow restricted settings" lives for side-loaded apps. */
    fun openAppInfo(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))
    }

    private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
