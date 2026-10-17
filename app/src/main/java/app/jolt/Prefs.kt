package app.jolt

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

class Prefs(context: Context) : StateStore {
    private val sp = context.applicationContext.getSharedPreferences("jolt", Context.MODE_PRIVATE)

    var watched: Set<String>
        get() = sp.getStringSet(KEY_WATCHED, null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_WATCHED, HashSet(value)).apply()

    var sessionMinutes: Int
        get() = sp.getInt(KEY_SESSION_MIN, 3)
        set(value) = sp.edit().putInt(KEY_SESSION_MIN, value).apply()

    var blockMinutes: Int
        get() = sp.getInt(KEY_BLOCK_MIN, 30)
        set(value) = sp.edit().putInt(KEY_BLOCK_MIN, value).apply()

    /** How long the pop-up keeps Continue locked. */
    var pauseSeconds: Int
        get() = sp.getInt(KEY_PAUSE_SEC, DEFAULT_PAUSE_SECONDS)
        set(value) = sp.edit().putInt(KEY_PAUSE_SEC, value).apply()

    fun config() = Config(
        watched = watched,
        sessionMs = sessionMinutes * MINUTE_MS,
        blockMs = blockMinutes * MINUTE_MS,
        resetMs = RESET_AFTER_BREAK_MS,
    )

    fun blockRemainingMs(clock: Clock): Long = load().blockRemainingMs(clock)

    var lock: LockState
        get() = LockState(
            locked = sp.getBoolean("lock_locked", false),
            unlockAt = if (sp.getBoolean("lock_requested", false)) {
                Deadline(
                    untilElapsed = sp.getLong("unlock_until_elapsed", 0),
                    untilWall = sp.getLong("unlock_until_wall", 0),
                    boot = sp.getInt("unlock_boot", EngineState.UNKNOWN_BOOT),
                    lenMs = sp.getLong("unlock_len_ms", 0),
                )
            } else {
                null
            },
        )
        set(value) {
            val at = value.unlockAt ?: Deadline.NONE
            sp.edit()
                .putBoolean("lock_locked", value.locked)
                .putBoolean("lock_requested", value.unlockAt != null)
                .putLong("unlock_until_elapsed", at.untilElapsed)
                .putLong("unlock_until_wall", at.untilWall)
                .putInt("unlock_boot", at.boot)
                .putLong("unlock_len_ms", at.lenMs)
                .apply()
        }

    /** Current lock state, with a completed unlock request applied. */
    fun settledLock(clock: Clock): LockState {
        val current = lock
        val settled = current.settle(clock)
        if (settled != current) lock = settled
        return settled
    }

    override fun load() = EngineState(
        usedMs = sp.getLong("used_ms", 0),
        lastActiveElapsed = sp.getLong("last_active_elapsed", 0),
        lastActiveBoot = sp.getInt("last_active_boot", EngineState.UNKNOWN_BOOT),
        blockUntilElapsed = sp.getLong("block_until_elapsed", 0),
        blockUntilWall = sp.getLong("block_until_wall", 0),
        blockBoot = sp.getInt("block_boot", EngineState.UNKNOWN_BOOT),
        blockLenMs = sp.getLong("block_len_ms", 0),
    )

    override fun save(state: EngineState) {
        sp.edit()
            .putLong("used_ms", state.usedMs)
            .putLong("last_active_elapsed", state.lastActiveElapsed)
            .putInt("last_active_boot", state.lastActiveBoot)
            .putLong("block_until_elapsed", state.blockUntilElapsed)
            .putLong("block_until_wall", state.blockUntilWall)
            .putInt("block_boot", state.blockBoot)
            .putLong("block_len_ms", state.blockLenMs)
            .apply()
    }

    private companion object {
        const val KEY_WATCHED = "watched"
        const val KEY_SESSION_MIN = "session_min"
        const val KEY_BLOCK_MIN = "block_min"
        const val KEY_PAUSE_SEC = "pause_sec"
    }
}

class AndroidClock(context: Context) : Clock {
    private val resolver = context.applicationContext.contentResolver

    override fun elapsed() = SystemClock.elapsedRealtime()
    override fun wall() = System.currentTimeMillis()
    override fun bootCount() = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, EngineState.UNKNOWN_BOOT)
}
