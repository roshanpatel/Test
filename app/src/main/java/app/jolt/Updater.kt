package app.jolt

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Updates Jolt from its GitHub releases without a browser, so a block never stands in the
 * way of an update. Each build's version number is its release number (build-N).
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/roshanpatel/Test/releases/latest"

    class Release(val number: Long, val apkUrl: String)

    fun installedNumber(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    /** The newest release, or null if it can't be reached. Call off the main thread. */
    fun latest(): Release? = try {
        val json = JSONObject(fetch(LATEST))
        val number = json.getString("tag_name").removePrefix("build-").toLong()
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("name").endsWith(".apk") }
        Release(number, apk.getString("browser_download_url"))
    } catch (e: Exception) {
        null
    }

    /** Downloads the release and asks Android to install it. Call off the main thread. */
    fun install(context: Context, release: Release) {
        val file = File(context.cacheDir, "update.apk")
        download(release.apkUrl, file)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("jolt.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
        file.delete()
    }

    private fun fetch(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun download(url: String, target: File) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
    }
}

/** Shows Android's "update this app?" confirmation, and reports failures. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Update didn't install: $message", Toast.LENGTH_LONG).show()
            }
        }
    }
}
