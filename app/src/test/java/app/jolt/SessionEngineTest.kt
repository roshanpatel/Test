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
    fun app_switches_while_prompt_showing_are_ignored() {
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        ui.calls.clear()
        engine.onForeground(CHROME)
        assertTrue(engine.promptShowing)
        assertEquals(emptyList<String>(), ui.calls)
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
