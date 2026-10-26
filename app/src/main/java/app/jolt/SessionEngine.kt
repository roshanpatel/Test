package app.jolt

// Pure session/block logic. No Android imports, so it runs in plain JVM unit tests.

const val MINUTE_MS = 60_000L

/** A break this long away from every watched app starts a fresh session. */
const val RESET_AFTER_BREAK_MS = 30 * MINUTE_MS

/** Default seconds the "time check" pop-up keeps Continue locked. PANIC works throughout. */
const val DEFAULT_PAUSE_SECONDS = 10

data class Config(
    val watched: Set<String>,
    val sessionMs: Long,
    val blockMs: Long,
    val resetMs: Long,
)

/**
 * Everything that must survive the service being restarted.
 *
 * Block end is stored twice: against the monotonic clock (can't be dodged by changing the
 * phone's time) and against the wall clock (still valid after a reboot, when the monotonic
 * clock restarts from zero).
 */
data class EngineState(
    val usedMs: Long = 0,
    val lastActiveElapsed: Long = 0,
    val lastActiveBoot: Int = UNKNOWN_BOOT,
    val blockUntilElapsed: Long = 0,
    val blockUntilWall: Long = 0,
    val blockBoot: Int = UNKNOWN_BOOT,
    val blockLenMs: Long = 0,
    /** Continue presses since the last PANIC or fresh session. */
    val continuesInARow: Int = 0,
) {
    val block get() = Deadline(blockUntilElapsed, blockUntilWall, blockBoot, blockLenMs)

    fun blockRemainingMs(clock: Clock): Long = block.remainingMs(clock)

    companion object {
        const val UNKNOWN_BOOT = -1
    }
}

interface Clock {
    /** Monotonic time since boot. */
    fun elapsed(): Long
    fun wall(): Long
    /** Number of boots so far, or [EngineState.UNKNOWN_BOOT]. */
    fun bootCount(): Int
    /** Today's date in the phone's time zone, as days since 1970-01-01. */
    fun localDay(): Long
}

interface StateStore {
    fun load(): EngineState
    fun save(state: EngineState)
}

interface EngineUi {
    fun showPrompt()
    fun hidePrompt()
    /** [celebrate] is true right after PANIC, false when bouncing off a blocked app. */
    fun showBlock(celebrate: Boolean)
    fun hideBlock()
    fun goHome()
    fun scheduleTick(delayMs: Long)
    fun cancelTick()
}

/**
 * Time spent in any watched app adds up to one shared session. When it reaches
 * [Config.sessionMs] the prompt is shown. Continue starts a fresh session; PANIC blocks
 * every watched app for [Config.blockMs]. Being away from watched apps for
 * [Config.resetMs] also starts a fresh session.
 */
class SessionEngine(
    private val config: () -> Config,
    private val clock: Clock,
    private val store: StateStore,
    private val statsStore: StatsStore,
    private val ui: EngineUi,
) {
    private var state = store.load()
    private var statsState = statsStore.loadStats()
    private var currentPkg: String? = null
    private var timingSince: Long? = null

    var promptShowing = false
        private set

    /** How many Continues came before the most recent PANIC. */
    var continuesBeforeLastPanic = 0
        private set

    val isBlocked: Boolean get() = blockRemainingMs() > 0

    /** Currently in a watched app with the session clock running. */
    val isTiming: Boolean get() = timingSince != null

    fun blockRemainingMs(): Long = state.blockRemainingMs(clock)

    val stats: Stats get() = statsState

    val today: DayStats get() = statsState.day(clock.localDay())

    /** Whether the next Continue needs the user's reason typed out. */
    val continueNeedsReason: Boolean get() = state.continuesInARow + 1 >= TYPE_REASON_ON_CONTINUE

    /** Call once when the service starts. */
    fun start() {
        updateStats { it }
        if (isBlocked) ui.scheduleTick(blockRemainingMs())
    }

    /** [pkg] is the app now on screen, or null when the screen is off. */
    fun onForeground(pkg: String?) {
        currentPkg = pkg
        val watched = pkg != null && pkg in config().watched
        if (promptShowing) {
            // Leaving the watched apps puts the check away. The session is still used up,
            // so it comes straight back on returning to any watched app.
            if (!watched) {
                promptShowing = false
                ui.hidePrompt()
            }
            return
        }
        if (isBlocked) {
            if (watched) {
                ui.goHome()
                ui.showBlock(celebrate = false)
            }
            return
        }
        if (watched) startTiming() else stopTiming()
    }

    fun onTick() {
        if (state.blockLenMs > 0 && !isBlocked) {
            endBlock()
            return
        }
        if (isBlocked) {
            ui.scheduleTick(blockRemainingMs())
            return
        }
        val since = timingSince ?: return
        val used = state.usedMs + (clock.elapsed() - since)
        val sessionMs = config().sessionMs
        if (used >= sessionMs) {
            stopTiming()
            promptShowing = true
            ui.showPrompt()
        } else {
            ui.scheduleTick(sessionMs - used)
        }
    }

    /**
     * Saves time used so far, so it isn't lost if the service is killed mid-session.
     * Cheap to call often; it only writes every [CHECKPOINT_MS].
     */
    fun checkpoint() {
        val since = timingSince ?: return
        val now = clock.elapsed()
        if (now - since < CHECKPOINT_MS) return
        timingSince = now
        foldUsed(now - since, now)
    }

    fun onContinue() {
        if (!promptShowing) return
        promptShowing = false
        ui.hidePrompt()
        state = state.copy(
            usedMs = 0,
            lastActiveElapsed = clock.elapsed(),
            lastActiveBoot = clock.bootCount(),
            continuesInARow = state.continuesInARow + 1,
        )
        store.save(state)
        updateStats { it.recordContinue(clock.localDay()) }
        onForeground(currentPkg)
    }

    /** PANIC button. */
    fun onPanic() {
        continuesBeforeLastPanic = state.continuesInARow
        promptShowing = false
        ui.hidePrompt()
        timingSince = null
        val block = Deadline.start(clock, config().blockMs)
        state = state.copy(
            usedMs = 0,
            blockUntilElapsed = block.untilElapsed,
            blockUntilWall = block.untilWall,
            blockBoot = block.boot,
            blockLenMs = block.lenMs,
            continuesInARow = 0,
        )
        store.save(state)
        updateStats { it.recordPanic(clock.localDay(), block.lenMs) }
        ui.goHome()
        ui.showBlock(celebrate = true)
        ui.scheduleTick(block.lenMs)
    }

    private fun endBlock() {
        state = state.copy(blockUntilElapsed = 0, blockUntilWall = 0, blockBoot = EngineState.UNKNOWN_BOOT, blockLenMs = 0)
        store.save(state)
        ui.hideBlock()
        onForeground(currentPkg)
    }

    private fun startTiming() {
        if (timingSince != null) return
        val now = clock.elapsed()
        val sameBoot = state.lastActiveBoot == clock.bootCount()
        val idle = if (sameBoot) now - state.lastActiveElapsed else Long.MAX_VALUE
        if (idle >= config().resetMs) state = state.copy(usedMs = 0, continuesInARow = 0)
        timingSince = now
        ui.scheduleTick((config().sessionMs - state.usedMs).coerceAtLeast(0))
    }

    private fun stopTiming() {
        val since = timingSince ?: return
        val now = clock.elapsed()
        timingSince = null
        ui.cancelTick()
        foldUsed(now - since, now)
    }

    private fun foldUsed(ms: Long, now: Long) {
        state = state.copy(
            usedMs = state.usedMs + ms,
            lastActiveElapsed = now,
            lastActiveBoot = clock.bootCount(),
        )
        store.save(state)
        updateStats { it.addWatched(clock.localDay(), ms) }
    }

    private fun updateStats(change: (Stats) -> Stats) {
        statsState = change(statsState.ensureStarted(clock.localDay()))
        statsStore.saveStats(statsState)
    }

    private companion object {
        const val CHECKPOINT_MS = 15_000L
    }
}

/** 29:59 style countdown, rounding up so it never shows 0:00 while time remains. */
fun formatMmSs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 23:41:10 style for durations of an hour or more, otherwise 29:59 style. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) + 999) / 1000
    if (totalSeconds < 3600) return formatMmSs(ms)
    return "%d:%02d:%02d".format(totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
}
