package app.jolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionEngineTest {
    private class FakeClock : Clock {
        var elapsed = 1_000_000L
        var wall = 1_700_000_000_000L
        var boot = 7

        override fun elapsed() = elapsed
        override fun wall() = wall
        override fun bootCount() = boot

        fun advance(ms: Long) {
            elapsed += ms
            wall += ms
        }
    }

    private class FakeStore : StateStore {
        var state = EngineState()
        override fun load() = state
        override fun save(state: EngineState) {
            this.state = state
        }
    }

    private class RecordingUi : EngineUi {
        val calls = mutableListOf<String>()
        var tickDelay: Long? = null

        override fun showPrompt() { calls += "showPrompt" }
        override fun hidePrompt() { calls += "hidePrompt" }
        override fun showBlock() { calls += "showBlock" }
        override fun hideBlock() { calls += "hideBlock" }
        override fun goHome() { calls += "goHome" }
        override fun scheduleTick(delayMs: Long) { tickDelay = delayMs }
        override fun cancelTick() { tickDelay = null }
    }

    private val config = Config(
        watched = setOf(FACEBOOK, CHROME),
        sessionMs = 3 * MINUTE_MS,
        blockMs = 30 * MINUTE_MS,
        resetMs = 30 * MINUTE_MS,
    )

    private lateinit var clock: FakeClock
    private lateinit var store: FakeStore
    private lateinit var ui: RecordingUi
    private lateinit var engine: SessionEngine

    @Before
    fun setUp() {
        clock = FakeClock()
        store = FakeStore()
        ui = RecordingUi()
        engine = newEngine()
    }

    private fun newEngine() = SessionEngine({ config }, clock, store, ui)

    /** Advance time and fire the tick if it is due, like the Android Handler would. */
    private fun runFor(ms: Long) {
        var left = ms
        while (true) {
            val due = ui.tickDelay ?: break
            if (due > left) {
                ui.tickDelay = due - left
                break
            }
            clock.advance(due)
            left -= due
            ui.tickDelay = null
            engine.onTick()
        }
        clock.advance(left)
    }

    @Test
    fun opening_watched_app_starts_full_session() {
        engine.onForeground(FACEBOOK)
        assertEquals(3 * MINUTE_MS, ui.tickDelay)
    }

    @Test
    fun unwatched_app_is_not_timed() {
        engine.onForeground(MAPS)
        assertEquals(null, ui.tickDelay)
    }

    @Test
    fun prompt_appears_when_session_runs_out() {
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS - 1)
        assertFalse(engine.promptShowing)
        runFor(1)
        assertTrue(engine.promptShowing)
        assertEquals(listOf("showPrompt"), ui.calls)
    }

    @Test
    fun session_is_shared_across_watched_apps() {
        engine.onForeground(FACEBOOK)
        runFor(2 * MINUTE_MS)
        engine.onForeground(CHROME)
        assertEquals(MINUTE_MS, ui.tickDelay)
        runFor(MINUTE_MS)
        assertTrue(engine.promptShowing)
    }

    @Test
    fun short_break_pauses_session() {
        engine.onForeground(FACEBOOK)
        runFor(2 * MINUTE_MS)
        engine.onForeground(MAPS)
        runFor(10 * MINUTE_MS)
        engine.onForeground(FACEBOOK)
        assertEquals(MINUTE_MS, ui.tickDelay)
    }

    @Test
    fun long_break_starts_new_session() {
        engine.onForeground(FACEBOOK)
        runFor(2 * MINUTE_MS)
        engine.onForeground(null) // screen off
        runFor(30 * MINUTE_MS)
        engine.onForeground(FACEBOOK)
        assertEquals(3 * MINUTE_MS, ui.tickDelay)
    }

    @Test
    fun continue_gives_another_full_session() {
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        engine.onContinue()
        assertFalse(engine.promptShowing)
        assertEquals(3 * MINUTE_MS, ui.tickDelay)
        runFor(3 * MINUTE_MS)
        assertTrue(engine.promptShowing)
    }

    @Test
    fun prompt_stays_when_switching_between_watched_apps() {
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        ui.calls.clear()
        engine.onForeground(CHROME)
        assertTrue(engine.promptShowing)
        assertEquals(emptyList<String>(), ui.calls)
    }

    @Test
    fun leaving_watched_apps_puts_prompt_away_until_return() {
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        ui.calls.clear()

        engine.onForeground(MAPS)
        assertFalse(engine.promptShowing)
        assertEquals(listOf("hidePrompt"), ui.calls)
        assertFalse(engine.isTiming)

        runFor(5 * MINUTE_MS)
        assertFalse("prompt must not reappear outside watched apps", engine.promptShowing)

        engine.onForeground(CHROME)
        runFor(0)
        assertTrue("session is still used up", engine.promptShowing)
    }

    @Test
    fun checkpoint_keeps_used_time_if_service_restarts() {
        engine.onForeground(FACEBOOK)
        runFor(2 * MINUTE_MS)
        engine.checkpoint()

        ui = RecordingUi()
        engine = newEngine()
        engine.onForeground(FACEBOOK)
        assertEquals(MINUTE_MS, ui.tickDelay)
    }

    @Test
    fun checkpoint_does_not_change_when_prompt_appears() {
        engine.onForeground(FACEBOOK)
        runFor(MINUTE_MS)
        engine.checkpoint()
        runFor(MINUTE_MS)
        engine.checkpoint()
        runFor(MINUTE_MS - 1)
        assertFalse(engine.promptShowing)
        runFor(1)
        assertTrue(engine.promptShowing)
    }

    @Test
    fun panic_blocks_all_watched_apps() {
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        ui.calls.clear()
        engine.onPanic()

        assertTrue(engine.isBlocked)
        assertEquals(listOf("hidePrompt", "goHome", "showBlock"), ui.calls)
        assertEquals(30 * MINUTE_MS, engine.blockRemainingMs())

        ui.calls.clear()
        engine.onForeground(MAPS)
        assertEquals(emptyList<String>(), ui.calls)
        engine.onForeground(CHROME)
        assertEquals(listOf("goHome", "showBlock"), ui.calls)
    }

    @Test
    fun block_ends_after_block_length_and_timing_resumes() {
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        engine.onPanic()
        engine.onForeground(MAPS)
        runFor(30 * MINUTE_MS - 1)
        assertTrue(engine.isBlocked)
        runFor(1)
        assertFalse(engine.isBlocked)
        assertTrue("hideBlock" in ui.calls)

        engine.onForeground(FACEBOOK)
        assertEquals(3 * MINUTE_MS, ui.tickDelay)
    }

    @Test
    fun block_survives_service_restart() {
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        engine.onPanic()
        runFor(10 * MINUTE_MS)

        ui = RecordingUi()
        engine = newEngine()
        engine.start()
        assertEquals(20 * MINUTE_MS, ui.tickDelay)
        engine.onForeground(FACEBOOK)
        assertEquals(listOf("goHome", "showBlock"), ui.calls)
    }

    @Test
    fun changing_phone_time_does_not_end_block() {
        engine.onPanic()
        clock.wall += 24 * 60 * MINUTE_MS
        assertEquals(30 * MINUTE_MS, engine.blockRemainingMs())
    }

    @Test
    fun block_survives_reboot_using_wall_clock() {
        engine.onPanic()
        clock.advance(10 * MINUTE_MS)
        clock.boot += 1
        clock.elapsed = 5_000 // monotonic clock restarts at boot
        assertEquals(20 * MINUTE_MS, newEngine().blockRemainingMs())
    }

    @Test
    fun block_never_reported_longer_than_its_length() {
        engine.onPanic()
        clock.boot += 1
        clock.wall -= 24 * 60 * MINUTE_MS
        assertEquals(30 * MINUTE_MS, engine.blockRemainingMs())
    }

    @Test
    fun unknown_boot_count_falls_back_to_wall_clock() {
        clock.boot = EngineState.UNKNOWN_BOOT
        engine.onPanic()
        clock.elapsed = 0
        clock.wall += 5 * MINUTE_MS
        assertEquals(25 * MINUTE_MS, engine.blockRemainingMs())
    }

    @Test
    fun lock_opens_only_after_requested_delay() {
        var lock = LockState().lock()
        assertFalse(lock.isOpen(clock))
        assertEquals(null, lock.unlockRemainingMs(clock))

        lock = lock.requestUnlock(clock)
        clock.advance(UNLOCK_DELAY_MS - 1)
        assertFalse(lock.isOpen(clock))
        clock.advance(1)
        assertTrue(lock.isOpen(clock))
        assertEquals(LockState(), lock.settle(clock))
    }

    @Test
    fun cancelling_unlock_request_keeps_lock() {
        var lock = LockState().lock().requestUnlock(clock)
        clock.advance(UNLOCK_DELAY_MS / 2)
        lock = lock.cancelUnlock()
        clock.advance(UNLOCK_DELAY_MS)
        assertFalse(lock.isOpen(clock))
    }

    @Test
    fun repeated_unlock_request_does_not_restart_wait() {
        var lock = LockState().lock().requestUnlock(clock)
        clock.advance(UNLOCK_DELAY_MS - MINUTE_MS)
        lock = lock.requestUnlock(clock)
        clock.advance(MINUTE_MS)
        assertTrue(lock.isOpen(clock))
    }

    @Test
    fun changing_phone_time_does_not_speed_up_unlock() {
        val lock = LockState().lock().requestUnlock(clock)
        clock.wall += 2 * UNLOCK_DELAY_MS
        assertFalse(lock.isOpen(clock))
    }

    @Test
    fun stricter_direction_rules() {
        assertTrue(isStricterOrSame(3, 2, Stricter.LOWER))
        assertFalse(isStricterOrSame(3, 4, Stricter.LOWER))
        assertTrue(isStricterOrSame(30, 35, Stricter.HIGHER))
        assertFalse(isStricterOrSame(30, 25, Stricter.HIGHER))
        assertTrue(isStricterOrSame(30, 30, Stricter.HIGHER))
    }

    @Test
    fun formats_long_durations() {
        assertEquals("24:00:00", formatDuration(UNLOCK_DELAY_MS))
        assertEquals("1:00:00", formatDuration(60 * MINUTE_MS))
        assertEquals("59:59", formatDuration(60 * MINUTE_MS - 1000))
    }

    @Test
    fun formats_countdown() {
        assertEquals("30:00", formatMmSs(30 * MINUTE_MS))
        assertEquals("0:01", formatMmSs(1))
        assertEquals("0:00", formatMmSs(0))
        assertEquals("2:05", formatMmSs(125_000))
    }

    private companion object {
        const val FACEBOOK = "com.facebook.katana"
        const val CHROME = "com.android.chrome"
        const val MAPS = "com.google.android.apps.maps"
    }
}
