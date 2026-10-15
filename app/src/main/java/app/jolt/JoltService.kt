package app.jolt

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Watches which app is on screen and drives [SessionEngine]. While a block is running it
 * also keeps the user out of the screens that could end it early: uninstalling Jolt,
 * force-stopping it, or turning this service off.
 */
class JoltService : AccessibilityService(), EngineUi {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { engine.onTick() }
    private val activityCache = HashMap<String, Boolean>()

    private lateinit var prefs: Prefs
    private lateinit var engine: SessionEngine
    private lateinit var overlays: Overlays
    private lateinit var appLabel: String

    /** Last real app screen seen, kept so we can resume it when the screen turns back on. */
    private var lastActivityPkg: String? = null
    private var lastTamperCheck = 0L
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> engine.onForeground(null)
                Intent.ACTION_SCREEN_ON ->
                    if (!(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) {
                        engine.onForeground(lastActivityPkg)
                    }
                Intent.ACTION_USER_PRESENT -> engine.onForeground(lastActivityPkg)
            }
        }
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        overlays = Overlays(this)
        appLabel = applicationInfo.loadLabel(packageManager).toString()
        engine = SessionEngine(prefs::config, AndroidClock(this), prefs, this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true

        engine.start()
        rootInActiveWindow?.packageName?.toString()
            ?.takeIf { it != packageName }
            ?.let(::onAppOpened)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::engine.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == SYSTEM_UI) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (engine.isBlocked && isTamperScreen(pkg)) {
                    kickOut()
                    return
                }
                val cls = event.className?.toString()
                if (cls == null || isActivity(pkg, cls)) onAppOpened(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Settings swaps pages without a new window, so recheck as content changes.
                if (!engine.isBlocked || !needsTextCheck(pkg)) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastTamperCheck < TAMPER_CHECK_INTERVAL_MS) return
                lastTamperCheck = now
                if (isTamperScreen(pkg)) kickOut()
            }
        }
    }

    private fun onAppOpened(pkg: String) {
        lastActivityPkg = pkg
        if ((getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) engine.onForeground(pkg)
    }

    /** Ignores dialogs, toasts and pop-up windows so they don't count as switching app. */
    private fun isActivity(pkg: String, cls: String): Boolean = activityCache.getOrPut("$pkg/$cls") {
        try {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun needsTextCheck(pkg: String) = pkg.contains("settings") || pkg == PLAY_STORE

    /** Uninstall dialogs, or a Settings / Play Store screen that mentions this app. */
    private fun isTamperScreen(pkg: String): Boolean {
        if (pkg.contains("packageinstaller")) return true
        if (!needsTextCheck(pkg)) return false
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        return root.findAccessibilityNodeInfosByText(appLabel).isNotEmpty()
    }

    private fun kickOut() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        showBlock()
    }

    // EngineUi

    override fun showPrompt() = overlays.showPrompt(
        sessionMinutes = prefs.sessionMinutes,
        onContinue = { engine.onContinue() },
        onPanic = { engine.onPanic() },
    )

    override fun hidePrompt() = overlays.hidePrompt()

    override fun showBlock() = overlays.showBlock(
        remainingMs = { engine.blockRemainingMs() },
        onDismiss = ::goHome,
    )

    override fun hideBlock() = overlays.hideBlock()

    override fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun scheduleTick(delayMs: Long) {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs)
    }

    override fun cancelTick() {
        handler.removeCallbacks(tick)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::overlays.isInitialized) overlays.hideAll()
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val PLAY_STORE = "com.android.vending"
        const val TAMPER_CHECK_INTERVAL_MS = 300L
    }
}
