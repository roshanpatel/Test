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
import android.widget.Toast

/**
 * Watches which app is on screen and drives [SessionEngine]. While a block is running, or
 * settings are locked, it also keeps the user out of the screens that could get around
 * Jolt: uninstalling it, force-stopping it, or turning this service off.
 */
class JoltService : AccessibilityService(), EngineUi {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { engine.onTick() }
    private val watchdog = object : Runnable {
        override fun run() {
            watchdogRunning = false
            checkScreen()
            engine.checkpoint()
            ensureWatchdog()
        }
    }
    private val activityCache = HashMap<String, Boolean>()

    private lateinit var prefs: Prefs
    private lateinit var clock: Clock
    private lateinit var engine: SessionEngine
    private lateinit var overlays: Overlays
    private lateinit var appLabel: String

    /** Last real app screen seen, kept so we can resume it when the screen turns back on. */
    private var lastActivityPkg: String? = null
    private var lastTamperCheck = 0L
    private var lastLockedNotice = 0L
    private var watchdogRunning = false
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> engine.onForeground(null)
                Intent.ACTION_SCREEN_ON -> {
                    if (!(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) {
                        engine.onForeground(lastActivityPkg)
                    }
                    ensureWatchdog()
                }
                Intent.ACTION_USER_PRESENT -> {
                    engine.onForeground(lastActivityPkg)
                    ensureWatchdog()
                }
            }
        }
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        clock = AndroidClock(this)
        overlays = Overlays(this)
        appLabel = applicationInfo.loadLabel(packageManager).toString()
        engine = SessionEngine(prefs::config, clock, prefs, prefs, this)

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
        ensureWatchdog()
        rootInActiveWindow?.packageName?.toString()
            ?.takeIf { it != packageName }
            ?.let(::onAppOpened)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::engine.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == SYSTEM_UI) return

        val cls = event.className?.toString()
        val watched = pkg in prefs.watched
        val blocked = engine.isBlocked

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (blocked && watched && !isToast(cls)) {
                    kickOut()
                    return
                }
                if (guardActive() && isTamperScreen(pkg, blocked)) {
                    kickOut()
                    return
                }
                // Watched apps count on any window change; for other apps only a real screen
                // (activity) counts, so their pop-ups don't end a watched session.
                if (cls == null || (watched && !isToast(cls)) || isActivity(pkg, cls)) onAppOpened(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Catches a watched app that opened without a window change we recognised
                // (it fires constantly while scrolling), and Settings swapping pages in place.
                val blockedApp = blocked && watched
                if (!blockedApp && !(needsTextCheck(pkg) && guardActive())) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastTamperCheck < TAMPER_CHECK_INTERVAL_MS) return
                lastTamperCheck = now
                if (blockedApp && activeWindowPackage() == pkg || isTamperScreen(pkg, blocked)) kickOut()
            }
        }
    }

    /** Blocked, or settings locked: guard the screens that could get around Jolt. */
    private fun guardActive() = engine.isBlocked || !prefs.settledLock(clock).isOpen(clock)

    /**
     * Double-checks what's actually on screen every couple of seconds, in case an app switch
     * was missed. Without this the session clock (and the check) could follow you into an
     * app you aren't watching.
     */
    private fun checkScreen() {
        val pkg = activeWindowPackage() ?: return
        if (pkg == packageName || pkg == SYSTEM_UI) return
        val blocked = engine.isBlocked
        if (guardActive() && isTamperScreen(pkg, blocked)) {
            kickOut()
            return
        }
        if (blocked) {
            if (pkg in prefs.watched) kickOut()
            return
        }
        if (pkg != lastActivityPkg) onAppOpened(pkg)
    }

    /** Runs only while the screen is on; the screen-on broadcast restarts it. */
    private fun ensureWatchdog() {
        if (watchdogRunning || !isScreenOn()) return
        if (!engine.isTiming && !engine.promptShowing && !guardActive()) return
        watchdogRunning = true
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun isScreenOn() = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun activeWindowPackage(): String? = rootInActiveWindow?.packageName?.toString()

    private fun isToast(cls: String?) = cls != null && cls.startsWith("android.widget.Toast")

    private fun onAppOpened(pkg: String) {
        lastActivityPkg = pkg
        if (isScreenOn()) engine.onForeground(pkg)
        ensureWatchdog()
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

    private fun needsTextCheck(pkg: String) =
        pkg.contains("settings") || pkg.contains("packageinstaller") || pkg == PLAY_STORE

    /**
     * Uninstall dialogs, or a Settings / Play Store screen that mentions this app. During a
     * block the installer is off limits entirely; while only locked, installing updates is
     * still allowed and just uninstall screens are stopped.
     */
    private fun isTamperScreen(pkg: String, blocked: Boolean): Boolean {
        if (pkg.contains("packageinstaller")) return blocked || screenMentions(pkg, "uninstall")
        if (!needsTextCheck(pkg)) return false
        return screenMentions(pkg, appLabel)
    }

    private fun screenMentions(pkg: String, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        return root.findAccessibilityNodeInfosByText(text).isNotEmpty()
    }

    private fun kickOut() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (engine.isBlocked) {
            showBlock(celebrate = false)
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLockedNotice > LOCKED_NOTICE_INTERVAL_MS) {
                lastLockedNotice = now
                Toast.makeText(this, "Settings are locked.", Toast.LENGTH_SHORT).show()
            }
        }
        ensureWatchdog()
    }

    // EngineUi

    override fun showPrompt() {
        overlays.showPrompt(
            PromptInfo(
                sessionMinutes = prefs.sessionMinutes,
                pauseSeconds = prefs.pauseSeconds,
                breathing = prefs.breathing,
                reason = prefs.reason,
                todayWatchedMs = engine.today.watchedMs,
                needsReason = engine.continueNeedsReason,
            ),
            onContinue = { engine.onContinue() },
            onPanic = { engine.onPanic() },
        )
        ensureWatchdog()
    }

    override fun hidePrompt() = overlays.hidePrompt()

    override fun showBlock(celebrate: Boolean) {
        overlays.showBlock(
            remainingMs = { engine.blockRemainingMs() },
            celebration = if (celebrate) celebration() else null,
            onDismiss = ::goHome,
        )
        ensureWatchdog()
    }

    private fun celebration(): Celebration {
        val stats = engine.stats
        val today = clock.localDay()
        val streak = stats.streak(today)
        // Only celebrate a streak milestone on the day's first PANIC, when it was reached.
        val firstToday = engine.today.panics == 1
        val praise = when {
            engine.continuesBeforeLastPanic > 0 -> COMEBACK_PRAISE.random()
            firstToday && stats.slippedYesterday(today) -> WELCOME_BACK
            else -> PANIC_PRAISE.random()
        }
        val next = nextPanicMilestone(stats.totalPanics)
        return Celebration(
            panicNumber = stats.totalPanics,
            praise = praise,
            milestones = listOfNotNull(
                panicMilestone(stats.totalPanics),
                streakMilestone(streak).takeIf { firstToday },
            ),
            streakDays = streak,
            timeBack = timeBackInRealTerms(stats.totalBlockedMs)?.let {
                "${formatHoursMinutes(stats.totalBlockedMs)} back so far: $it"
            },
            nextMilestone = next?.let { "${it - stats.totalPanics} more to $it PANICs" },
        )
    }

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
        const val WATCHDOG_INTERVAL_MS = 2000L
        const val LOCKED_NOTICE_INTERVAL_MS = 3000L
    }
}
