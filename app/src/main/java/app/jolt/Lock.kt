package app.jolt

// Pure settings-lock rules. No Android imports, so they run in plain JVM unit tests.

/** How long an unlock request takes to come through. */
const val UNLOCK_DELAY_MS = 24 * 60 * MINUTE_MS

/**
 * A point in the future that survives clock changes and reboots, the same way a block does:
 * measured on the monotonic clock while the phone stays on, and on the wall clock after a
 * reboot. Never reports more than [lenMs] remaining.
 */
data class Deadline(
    val untilElapsed: Long,
    val untilWall: Long,
    val boot: Int,
    val lenMs: Long,
) {
    fun remainingMs(clock: Clock): Long {
        if (lenMs <= 0) return 0
        val now = clock.bootCount()
        val remaining = if (now != EngineState.UNKNOWN_BOOT && now == boot) {
            untilElapsed - clock.elapsed()
        } else {
            untilWall - clock.wall()
        }
        return remaining.coerceIn(0, lenMs)
    }

    companion object {
        val NONE = Deadline(0, 0, EngineState.UNKNOWN_BOOT, 0)

        fun start(clock: Clock, lenMs: Long) =
            Deadline(clock.elapsed() + lenMs, clock.wall() + lenMs, clock.bootCount(), lenMs)
    }
}

/**
 * Settings can be locked at any time. Unlocking takes a request followed by
 * [UNLOCK_DELAY_MS]; the request can be cancelled. While locked, only changes that make
 * the rules stricter are allowed.
 */
data class LockState(
    val locked: Boolean = false,
    /** Pending unlock request, or null. */
    val unlockAt: Deadline? = null,
) {
    /** Unlocked now, either never locked or the request has come through. */
    fun isOpen(clock: Clock): Boolean = !locked || (unlockAt != null && unlockAt.remainingMs(clock) == 0L)

    fun unlockRemainingMs(clock: Clock): Long? = if (locked) unlockAt?.remainingMs(clock) else null

    fun lock() = LockState(locked = true)

    fun requestUnlock(clock: Clock) = if (locked && unlockAt == null) copy(unlockAt = Deadline.start(clock, UNLOCK_DELAY_MS)) else this

    fun cancelUnlock() = copy(unlockAt = null)

    /** Turns a request that has come through into a plain unlocked state. */
    fun settle(clock: Clock) = if (locked && isOpen(clock)) LockState() else this
}

/** Which way a setting must move to count as stricter. */
enum class Stricter { HIGHER, LOWER }

fun isStricterOrSame(old: Int, new: Int, stricter: Stricter) =
    when (stricter) {
        Stricter.HIGHER -> new >= old
        Stricter.LOWER -> new <= old
    }
