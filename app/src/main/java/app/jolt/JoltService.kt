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
    private var webFilterCheckedAt = 0L
    private var webFilterOn = false

    /** Watches newly installed browsers, social and video apps straight away. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (AutoWatch.onInstalled(this@JoltService, prefs, pkg)) {
                Toast.makeText(this@JoltService, "Now watching ${AutoWatch.label(this@JoltService, pkg)}", Toast.LENGTH_LONG).show()
            }
        }
    }

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
        engine = SessionEngine({ prefs.config(clock) }, clock, prefs, prefs, this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val packages = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(packageReceiver, packages, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
            registerReceiver(packageReceiver, packages)
        }
        receiverRegistered = true
        AutoWatch.sync(this, prefs)
        CheckIn.scheduleNext(this)

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
                if (blocked && (watched || isAppStore(pkg)) && !isToast(cls)) {
                    kickOut()
                    return
                }
                if (guardActive() && isTamperScreen(pkg, blocked)) {
                    kickOut()
                    return
                }
                if (isDnsScreen(pkg)) {
                    kickOut(DNS_LOCKED)
                    return
                }
                // Watched apps count on any window change; for other apps only a real screen
                // (activity) counts, so their pop-ups don't end a watched session.
                if (cls == null || (watched && !isToast(cls)) || isActivity(pkg, cls)) onAppOpened(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Catches a watched app that opened without a window change we recognised
                // (it fires constantly while scrolling), and Settings swapping pages in place.
                val blockedApp = blocked && (watched || isAppStore(pkg))
                val guard = guardActive()
                if (!blockedApp && !(needsTextCheck(pkg) && (guard || isWebFilterOn()))) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastTamperCheck < TAMPER_CHECK_INTERVAL_MS) return
                lastTamperCheck = now
                when {
                    blockedApp && activeWindowPackage() == pkg -> kickOut()
                    guard && isTamperScreen(pkg, blocked) -> kickOut()
                    isDnsScreen(pkg) -> kickOut(DNS_LOCKED)
                }
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
        if (isDnsScreen(pkg)) {
            kickOut(DNS_LOCKED)
            return
        }
        if (blocked) {
            if (pkg in prefs.watched || isAppStore(pkg)) kickOut()
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

    /** App stores are off limits during a block, so a way round can't be installed. */
    private fun isAppStore(pkg: String) = pkg in APP_STORES

    private fun needsTextCheck(pkg: String) =
        pkg.contains("settings") || pkg.contains("packageinstaller") || pkg == PLAY_STORE

    /**
     * Uninstalling Jolt, or a Settings / Play Store screen that mentions this app.
     *
     * The installer: uninstalling any *other* app is always allowed, since removing
     * temptation should never be blocked. Uninstalling Jolt is stopped. During a block,
     * installing anything is stopped too, so a new way round can't be added mid-block.
     */
    private fun isTamperScreen(pkg: String, blocked: Boolean): Boolean {
        if (pkg.contains("packageinstaller")) {
            // Wait until the dialog's text is readable before deciding.
            if (rootInActiveWindow?.packageName?.toString() != pkg) return false
            val uninstalling = screenMentions(pkg, "uninstall")
            if (screenMentions(pkg, appLabel)) return uninstalling || blocked
            return blocked && !uninstalling
        }
        if (!needsTextCheck(pkg)) return false
        return screenMentions(pkg, appLabel)
    }

    /**
     * The Private DNS dialog, while the web filter is on. Guarded whenever Jolt is running,
     * locked or not, so the filter can't be quietly switched off.
     */
    private fun isDnsScreen(pkg: String) =
        pkg.contains("settings") && isWebFilterOn() && screenMentions(pkg, "provider hostname")

    /** Cached, as this is checked on every Settings screen change. */
    private fun isWebFilterOn(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - webFilterCheckedAt > WEB_FILTER_CHECK_MS) {
            webFilterCheckedAt = now
            webFilterOn = WebFilter.activeHostname(this) != null
        }
        return webFilterOn
    }

    private fun screenMentions(pkg: String, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        return root.findAccessibilityNodeInfosByText(text).isNotEmpty()
    }

    /** Sends the user home; shows the block screen if blocked, otherwise [notice]. */
    private fun kickOut(notice: String = "Settings are locked.") {
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (engine.isBlocked && notice != DNS_LOCKED) {
            showBlock(engine.blockReason)
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLockedNotice > LOCKED_NOTICE_INTERVAL_MS) {
                lastLockedNotice = now
                Toast.makeText(this, notice, Toast.LENGTH_SHORT).show()
            }
        }
        ensureWatchdog()
    }

    // EngineUi

    override fun showPrompt() {
        overlays.showPrompt(
            PromptInfo(
                continueMs = engine.nextContinueSessionMs,
                pauseSeconds = prefs.pauseSeconds,
                breathing = prefs.breathing,
                reason = prefs.reason,
                todayWatchedMs = engine.today.watchedMs,
                dailyLeftMs = engine.dailyRemainingMs(),
                needsReason = engine.continueNeedsReason,
                clipSound = prefs.clipSound,
            ),
            onContinue = { engine.onContinue() },
            onPanic = { engine.onPanic() },
        )
        ensureWatchdog()
    }

    override fun hidePrompt() = overlays.hidePrompt()

    override fun showBlock(reason: BlockReason) {
        overlays.showBlock(
            remainingMs = { engine.blockRemainingMs() },
            celebration = if (reason == BlockReason.PANIC) celebration() else null,
            explanation = explain(reason),
            myReason = prefs.reason,
            clipSound = prefs.clipSound,
            onDismiss = ::goHome,
        )
        ensureWatchdog()
    }

    /** Why watched apps are blocked, and until when, ending just before the countdown. */
    private fun explain(reason: BlockReason): String = when (reason) {
        BlockReason.PANIC, BlockReason.REOPENED -> "Your watched apps are locked for"
        BlockReason.DAILY_LIMIT -> "You've used today's limit in watched apps. They open again at midnight, in"
        BlockReason.SCHEDULED -> {
            val schedule = prefs.schedule
            val minute = clock.localMinute()
            when (schedule.answerAt(minute)) {
                DayPlan.HOME -> "Working from home today. Watched apps are off until tomorrow's check-in, in"
                null -> "No check-in answer yet, so today counts as a home day. Answer it on Jolt's main screen. Watched apps are off for"
                DayPlan.OUT -> {
                    val endsAt = Math.floorMod(minute + (engine.blockRemainingMs() + clock.msIntoMinute()) / MINUTE_MS, MINUTES_PER_DAY.toLong())
                    if (endsAt.toInt() == schedule.checkInMin) {
                        "Scheduled break, until the morning check-in at ${hhmm(endsAt.toInt())}, in"
                    } else {
                        "Scheduled break. Watched apps open again at ${hhmm(endsAt.toInt())}, in"
                    }
                }
            }
        }
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
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            unregisterReceiver(packageReceiver)
        }
        super.onDestroy()
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val PLAY_STORE = "com.android.vending"
        val APP_STORES = setOf(
            PLAY_STORE,
            "com.sec.android.app.samsungapps", // Galaxy Store
            "com.huawei.appmarket",
            "com.xiaomi.market",
            "com.xiaomi.mipicks",
            "com.heytap.market", // Oppo / OnePlus
            "com.oppo.market",
            "com.bbk.appstore", // Vivo
            "com.amazon.venezia",
            "org.fdroid.fdroid",
            "com.aurora.store",
            "com.apkpure.aegon",
        )
        const val WEB_FILTER_CHECK_MS = 30_000L
        const val DNS_LOCKED = "Private DNS is locked while Jolt is on."
        const val TAMPER_CHECK_INTERVAL_MS = 300L
        const val WATCHDOG_INTERVAL_MS = 2000L
        const val LOCKED_NOTICE_INTERVAL_MS = 3000L
    }
}
