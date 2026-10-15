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

    var resetMinutes: Int
        get() = sp.getInt(KEY_RESET_MIN, 30)
        set(value) = sp.edit().putInt(KEY_RESET_MIN, value).apply()

    fun config() = Config(
        watched = watched,
        sessionMs = sessionMinutes * MINUTE_MS,
        blockMs = blockMinutes * MINUTE_MS,
        resetMs = resetMinutes * MINUTE_MS,
    )

    fun blockRemainingMs(clock: Clock): Long = load().blockRemainingMs(clock)

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
        const val KEY_RESET_MIN = "reset_min"
    }
}

class AndroidClock(context: Context) : Clock {
    private val resolver = context.applicationContext.contentResolver

    override fun elapsed() = SystemClock.elapsedRealtime()
    override fun wall() = System.currentTimeMillis()
    override fun bootCount() = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, EngineState.UNKNOWN_BOOT)
}
