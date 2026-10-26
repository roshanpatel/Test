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
        override fun localDay() = wall / (24 * 60 * MINUTE_MS)

        fun advance(ms: Long) {
            elapsed += ms
            wall += ms
        }
    }

    private class FakeStore : StateStore, StatsStore {
        var state = EngineState()
        var stats = Stats()
        override fun load() = state
        override fun save(state: EngineState) {
            this.state = state
        }
        override fun loadStats() = stats
        override fun saveStats(stats: Stats) {
            this.stats = stats
        }
    }

    private class RecordingUi : EngineUi {
        val calls = mutableListOf<String>()
        var tickDelay: Long? = null

        override fun showPrompt() { calls += "showPrompt" }
        override fun hidePrompt() { calls += "hidePrompt" }
        override fun showBlock(celebrate: Boolean) { calls += if (celebrate) "celebrate" else "showBlock" }
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

    private fun newEngine() = SessionEngine({ config }, clock, store, store, ui)

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
        assertEquals(listOf("hidePrompt", "goHome", "celebrate"), ui.calls)
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
    fun reason_required_from_third_continue_in_a_row() {
        engine.onForeground(FACEBOOK)
        repeat(2) {
            runFor(3 * MINUTE_MS)
            assertFalse(engine.continueNeedsReason)
            engine.onContinue()
        }
        runFor(3 * MINUTE_MS)
        assertTrue(engine.continueNeedsReason)
        engine.onContinue()
        runFor(3 * MINUTE_MS)
        assertTrue("still required after the third", engine.continueNeedsReason)
    }

    @Test
    fun panic_resets_continue_count() {
        engine.onForeground(FACEBOOK)
        repeat(2) {
            runFor(3 * MINUTE_MS)
            engine.onContinue()
        }
        runFor(3 * MINUTE_MS)
        engine.onPanic()
        runFor(30 * MINUTE_MS)
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        assertFalse(engine.continueNeedsReason)
    }

    @Test
    fun long_break_resets_continue_count() {
        engine.onForeground(FACEBOOK)
        repeat(2) {
            runFor(3 * MINUTE_MS)
            engine.onContinue()
        }
        engine.onForeground(MAPS)
        runFor(30 * MINUTE_MS)
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        assertFalse(engine.continueNeedsReason)
    }

    @Test
    fun stats_track_time_continues_and_panics() {
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        engine.onContinue()
        runFor(3 * MINUTE_MS)
        engine.onPanic()

        val today = engine.today
        assertEquals(6 * MINUTE_MS, today.watchedMs)
        assertEquals(1, today.continues)
        assertEquals(1, today.panics)
        assertEquals(1, engine.stats.totalPanics)
        assertEquals(30 * MINUTE_MS, engine.stats.totalBlockedMs)
    }

    @Test
    fun streak_counts_good_days_and_skips_unfinished_today() {
        val start = 100L
        var stats = Stats().ensureStarted(start)
        stats = stats.recordPanic(start, MINUTE_MS) // day 100: good
        // day 101: unused, good
        stats = stats.recordContinue(start + 2).recordPanic(start + 2, MINUTE_MS) // day 102: good
        stats = stats.recordContinue(start + 3) // day 103 (today so far): not good yet
        assertEquals(3, stats.streak(start + 3))

        stats = stats.recordPanic(start + 3, MINUTE_MS)
        assertEquals(4, stats.streak(start + 3))
    }

    @Test
    fun one_bad_day_is_forgiven() {
        var stats = Stats().ensureStarted(10)
        stats = stats.recordContinue(11) // day 11: continued, never panicked
        val streak = stats.streakInfo(12)
        assertEquals(2, streak.days) // days 10 and 12; 11 is forgiven but doesn't count
        assertEquals(1, streak.freezesUsed)
        assertFalse(streak.freezeReady)
        assertEquals(0, Stats().streak(5))
    }

    @Test
    fun second_bad_day_within_a_week_breaks_streak() {
        var stats = Stats().ensureStarted(10)
        stats = stats.recordContinue(11).recordContinue(13)
        assertEquals(2, stats.streak(14)) // 14 and 12; 13 forgiven, 11 breaks it
    }

    @Test
    fun bad_days_a_week_apart_are_both_forgiven() {
        var stats = Stats().ensureStarted(1)
        stats = stats.recordContinue(3).recordContinue(12)
        val streak = stats.streakInfo(20)
        assertEquals(18, streak.days)
        assertEquals(2, streak.freezesUsed)
        assertTrue(streak.freezeReady)
    }

    @Test
    fun two_bad_days_in_a_row_break_streak() {
        var stats = Stats().ensureStarted(1)
        stats = stats.recordContinue(5).recordContinue(6)
        assertEquals(2, stats.streak(8)) // 8 and 7
    }

    @Test
    fun bad_yesterday_is_forgiven_while_today_is_unfinished() {
        var stats = Stats().ensureStarted(10)
        stats = stats.recordContinue(11).recordContinue(12)
        assertEquals(1, stats.streak(12)) // day 10; 11 forgiven; 12 still in progress
    }

    @Test
    fun bad_first_day_protects_nothing() {
        val stats = Stats().ensureStarted(10).recordContinue(10)
        val streak = stats.streakInfo(11)
        assertEquals(1, streak.days)
        assertEquals(0, streak.freezesUsed)
    }

    @Test
    fun slip_yesterday_is_noticed() {
        val stats = Stats().ensureStarted(10).recordContinue(11)
        assertTrue(stats.slippedYesterday(12))
        assertFalse(stats.slippedYesterday(11)) // day 10 was fine
        assertFalse(Stats().slippedYesterday(12))
        assertEquals(slipKindness(12), slipKindness(12))
    }

    @Test
    fun time_back_in_real_terms() {
        assertEquals(null, timeBackInRealTerms(10 * MINUTE_MS))
        assertEquals("about a walk round the block", timeBackInRealTerms(20 * MINUTE_MS))
        assertEquals("about a film", timeBackInRealTerms(150 * MINUTE_MS))
        assertEquals("about 2 films", timeBackInRealTerms(4 * 60 * MINUTE_MS))
        assertEquals("about 5 books", timeBackInRealTerms(30 * 60 * MINUTE_MS))
    }

    @Test
    fun milestone_progress() {
        assertEquals(0f, milestoneProgress(0), 0.001f)
        assertEquals(0.5f, milestoneProgress(3), 0.001f) // between 1 and 5
        assertEquals(0f, milestoneProgress(10), 0.001f)
        assertEquals(1f, milestoneProgress(5000), 0.001f)
    }

    /** Works out "a op b" or "a × b + c" the slow way, to check the generator's answers. */
    private fun evaluate(question: String): Int {
        val t = question.split(" ")
        fun apply(x: Int, op: String, y: Int) = when (op) {
            "+" -> x + y
            "−" -> x - y
            "×" -> x * y
            else -> error(op)
        }
        val first = apply(t[0].toInt(), t[1], t[2].toInt())
        return if (t.size == 5) apply(first, t[3], t[4].toInt()) else first
    }

    @Test
    fun maths_answers_are_right_and_never_negative() {
        val random = kotlin.random.Random(42)
        for (level in 1..8) repeat(200) {
            val p = mathProblem(level, random)
            assertEquals(p.question, evaluate(p.question), p.answer)
            assertTrue(p.question, p.answer >= 0)
        }
    }

    @Test
    fun maths_gets_harder_with_level() {
        val random = kotlin.random.Random(7)
        fun hardest(level: Int) = (1..200).maxOf { mathProblem(level, random).answer }
        fun easiest(level: Int) = (1..200).minOf { mathProblem(level, random).answer }
        assertTrue(hardest(1) <= 18)
        assertTrue(easiest(5) >= 12 * 11)
        assertTrue(easiest(6) > hardest(3))
        assertEquals(mathProblem(6, kotlin.random.Random(1)), mathProblem(99, kotlin.random.Random(1)))
    }

    @Test
    fun recognises_calculators() {
        assertTrue(isCalculator("com.google.android.calculator"))
        assertTrue(isCalculator("com.sec.android.app.popupcalculator"))
        assertFalse(isCalculator("com.android.chrome"))
    }

    @Test
    fun maths_level_rises_with_each_continue() {
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        assertEquals(1, engine.nextContinueNumber)
        engine.onContinue()
        runFor(3 * MINUTE_MS)
        assertEquals(2, engine.nextContinueNumber)
    }

    @Test
    fun remembers_continues_before_panic() {
        engine.onForeground(FACEBOOK)
        runFor(3 * MINUTE_MS)
        engine.onContinue()
        runFor(3 * MINUTE_MS)
        engine.onPanic()
        assertEquals(1, engine.continuesBeforeLastPanic)
    }

    @Test
    fun stats_survive_encoding() {
        val stats = Stats().ensureStarted(5).addWatched(5, 1234).recordContinue(6).recordPanic(6, 30 * MINUTE_MS)
        assertEquals(stats, Stats.decode(stats.encode()))
        assertEquals(Stats(), Stats.decode("garbage"))
        assertEquals(Stats(), Stats.decode(null))
    }

    @Test
    fun old_days_are_dropped() {
        val stats = Stats().addWatched(0, 1).addWatched(KEEP_DAYS.toLong() + 5, 1)
        assertEquals(setOf(KEEP_DAYS.toLong() + 5), stats.days.keys)
    }

    @Test
    fun milestones() {
        assertEquals("Your first PANIC. The first one is the hardest.", panicMilestone(1))
        assertEquals("Milestone: 10 PANICs!", panicMilestone(10))
        assertEquals(null, panicMilestone(11))
        assertEquals(25, nextPanicMilestone(10))
        assertEquals("Milestone: a 7-day streak!", streakMilestone(7))
        assertEquals(null, streakMilestone(8))
    }

    @Test
    fun reason_matching_is_forgiving_but_exact_in_words() {
        val reason = "I want to be present with my kids."
        assertTrue(matchesReason("i want to be  present with my kids", reason))
        assertFalse(matchesReason("i want to be present", reason))
        assertFalse(matchesReason("", ""))
    }

    @Test
    fun formats_hours_and_minutes() {
        assertEquals("45 min", formatHoursMinutes(45 * MINUTE_MS))
        assertEquals("18 h 30 min", formatHoursMinutes((18 * 60 + 30) * MINUTE_MS))
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
