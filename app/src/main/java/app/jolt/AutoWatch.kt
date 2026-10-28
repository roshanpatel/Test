package app.jolt

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Closes the "just install another browser" loophole: every app that can open ordinary web
 * pages is watched, and so is any newly installed social or video app.
 */
object AutoWatch {
    /** Apps that can open any web page. Apps that only open their own links don't count. */
    fun browsers(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .toSet()
    }

    /** Adds any browser not yet watched. Returns the ones added. */
    fun sync(context: Context, prefs: Prefs): Set<String> {
        if (!prefs.autoWatch) return emptySet()
        val added = browsers(context) - prefs.watched
        if (added.isNotEmpty()) prefs.watched = prefs.watched + added
        return added
    }

    /** Watches [pkg] if it's a newly installed browser, social or video app. */
    fun onInstalled(context: Context, prefs: Prefs, pkg: String): Boolean {
        if (!prefs.autoWatch || pkg == context.packageName || pkg in prefs.watched) return false
        val info = try {
            context.packageManager.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val social = info.category == ApplicationInfo.CATEGORY_SOCIAL || info.category == ApplicationInfo.CATEGORY_VIDEO
        if (!social && pkg !in browsers(context)) return false
        prefs.watched = prefs.watched + pkg
        return true
    }

    fun label(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
