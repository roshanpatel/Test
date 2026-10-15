package app.jolt

// Pure session/block logic. No Android imports, so it runs in plain JVM unit tests.

const val MINUTE_MS = 60_000L

/** A break this long away from every watched app starts a fresh session. */
const val RESET_AFTER_BREAK_MS = 30 * MINUTE_MS

/** Seconds the "time check" pop-up waits before treating silence as PANIC. */
const val PROMPT_SECONDS = 10

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
) {
    fun blockRemainingMs(clock: Clock): Long {
        if (blockLenMs <= 0) return 0
        val boot = clock.bootCount()
        val remaining = if (boot != UNKNOWN_BOOT && boot == blockBoot) {
            blockUntilElapsed - clock.elapsed()
        } else {
            blockUntilWall - clock.wall()
        }
        // Never longer than the block that was started, whatever the clocks claim.
        return remaining.coerceIn(0, blockLenMs)
    }

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
}

interface StateStore {
    fun load(): EngineState
    fun save(state: EngineState)
}

interface EngineUi {
    fun showPrompt()
    fun hidePrompt()
    fun showBlock()
    fun hideBlock()
    fun goHome()
    fun scheduleTick(delayMs: Long)
    fun cancelTick()
}

/**
 * Time spent in any watched app adds up to one shared session. When it reaches
 * [Config.sessionMs] the prompt is shown. Continue starts a fresh session; PANIC (or no
 * answer) blocks every watched app for [Config.blockMs]. Being away from watched apps for
 * [Config.resetMs] also starts a fresh session.
 */
class SessionEngine(
    private val config: () -> Config,
    private val clock: Clock,
    private val store: StateStore,
    private val ui: EngineUi,
) {
    private var state = store.load()
    private var currentPkg: String? = null
    private var timingSince: Long? = null

    var promptShowing = false
        private set

    val isBlocked: Boolean get() = blockRemainingMs() > 0

    fun blockRemainingMs(): Long = state.blockRemainingMs(clock)

    /** Call once when the service starts. */
    fun start() {
        if (isBlocked) ui.scheduleTick(blockRemainingMs())
    }

    /** [pkg] is the app now on screen, or null when the screen is off. */
    fun onForeground(pkg: String?) {
        currentPkg = pkg
        if (promptShowing) return
        val watched = pkg != null && pkg in config().watched
        if (isBlocked) {
            if (watched) {
                ui.goHome()
                ui.showBlock()
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

    fun onContinue() {
        if (!promptShowing) return
        promptShowing = false
        ui.hidePrompt()
        state = state.copy(
            usedMs = 0,
            lastActiveElapsed = clock.elapsed(),
            lastActiveBoot = clock.bootCount(),
        )
        store.save(state)
        onForeground(currentPkg)
    }

    /** PANIC button, or the prompt running out. */
    fun onPanic() {
        promptShowing = false
        ui.hidePrompt()
        timingSince = null
        val blockMs = config().blockMs
        state = state.copy(
            usedMs = 0,
            blockUntilElapsed = clock.elapsed() + blockMs,
            blockUntilWall = clock.wall() + blockMs,
            blockBoot = clock.bootCount(),
            blockLenMs = blockMs,
        )
        store.save(state)
        ui.goHome()
        ui.showBlock()
        ui.scheduleTick(blockMs)
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
        if (idle >= config().resetMs) state = state.copy(usedMs = 0)
        timingSince = now
        ui.scheduleTick((config().sessionMs - state.usedMs).coerceAtLeast(0))
    }

    private fun stopTiming() {
        val since = timingSince ?: return
        val now = clock.elapsed()
        timingSince = null
        ui.cancelTick()
        state = state.copy(
            usedMs = state.usedMs + (now - since),
            lastActiveElapsed = now,
            lastActiveBoot = clock.bootCount(),
        )
        store.save(state)
    }
}

/** 29:59 style countdown, rounding up so it never shows 0:00 while time remains. */
fun formatMmSs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
