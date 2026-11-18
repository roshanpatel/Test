package app.jolt

// Pure schedule rules: the morning check-in and blocked hours. No Android imports.

const val MINUTES_PER_DAY = 1440

/** Blocked from [startMin] up to [endMin], in minutes after midnight. Can wrap past midnight. */
data class TimeWindow(val startMin: Int, val endMin: Int) {
    fun contains(minuteOfDay: Int): Boolean =
        if (startMin <= endMin) minuteOfDay in startMin until endMin else minuteOfDay >= startMin || minuteOfDay < endMin

    val label: String get() = "${hhmm(startMin)} – ${hhmm(endMin)}"
}

/** The answer to the morning question. */
enum class DayPlan {
    /** Working from home: watched apps blocked until the next check-in. */
    HOME,

    /** Out: the blocked hours apply. */
    OUT,
}

/**
 * Each "day" here runs from one morning check-in to the next. Until the question is answered
 * the day counts as [DayPlan.HOME], the stricter choice, so ignoring it is never a way in.
 */
data class Schedule(
    val on: Boolean = true,
    val checkInMin: Int = DEFAULT_CHECK_IN_MIN,
    val windows: List<TimeWindow> = DEFAULT_WINDOWS,
    val answerDay: Long = Stats.NO_DAY,
    val answer: DayPlan? = null,
) {
    /** Which check-in day a moment belongs to. [localMinute] counts minutes since 1970 in local time. */
    fun checkInDay(localMinute: Long): Long {
        val day = Math.floorDiv(localMinute, MINUTES_PER_DAY.toLong())
        val minute = Math.floorMod(localMinute, MINUTES_PER_DAY.toLong()).toInt()
        return if (minute >= checkInMin) day else day - 1
    }

    /** The answer given for the check-in day containing [localMinute], or null if none yet. */
    fun answerAt(localMinute: Long): DayPlan? = answer.takeIf { answerDay == checkInDay(localMinute) }

    /** The plan in force: the answer, or a home day if there isn't one. */
    fun planAt(localMinute: Long): DayPlan = answerAt(localMinute) ?: DayPlan.HOME

    fun blockedAt(localMinute: Long): Boolean {
        if (!on) return false
        if (planAt(localMinute) == DayPlan.HOME) return true
        val minute = Math.floorMod(localMinute, MINUTES_PER_DAY.toLong()).toInt()
        return windows.any { it.contains(minute) }
    }

    /**
     * Time left in the scheduled block happening now, or 0 if there isn't one. It never runs
     * past the next check-in: from then, the day's answer decides.
     */
    fun blockMsLeft(localMinute: Long, msIntoMinute: Long): Long {
        if (!blockedAt(localMinute)) return 0
        var minutes = 1L
        while (minutes <= MINUTES_PER_DAY) {
            val next = localMinute + minutes
            if (isCheckIn(next) || !blockedAt(next)) break
            minutes++
        }
        return minutes * MINUTE_MS - msIntoMinute
    }

    /** Time until the next scheduled block starts, 0 if one is on now, or null if none ahead. */
    fun msUntilBlock(localMinute: Long, msIntoMinute: Long): Long? {
        if (blockedAt(localMinute)) return 0
        for (minutes in 1L..MINUTES_PER_DAY) {
            if (blockedAt(localMinute + minutes)) return minutes * MINUTE_MS - msIntoMinute
        }
        return null
    }

    /**
     * Records the answer for the current check-in day. A home day is final; an "out" answer
     * can still be changed to home. Returns null if the change isn't allowed.
     */
    fun answer(localMinute: Long, plan: DayPlan): Schedule? {
        val current = answerAt(localMinute)
        if (current == DayPlan.HOME && plan == DayPlan.OUT) return null
        return copy(answerDay = checkInDay(localMinute), answer = plan)
    }

    private fun isCheckIn(localMinute: Long) =
        Math.floorMod(localMinute, MINUTES_PER_DAY.toLong()).toInt() == checkInMin

    companion object {
        const val DEFAULT_CHECK_IN_MIN = 6 * 60
        val DEFAULT_WINDOWS = listOf(TimeWindow(19 * 60, 9 * 60), TimeWindow(16 * 60 + 30, 19 * 60))

        fun encodeWindows(windows: List<TimeWindow>) = windows.joinToString(",") { "${it.startMin}-${it.endMin}" }

        fun decodeWindows(text: String?): List<TimeWindow> {
            if (text == null) return DEFAULT_WINDOWS
            if (text.isEmpty()) return emptyList()
            return try {
                text.split(',').map { part ->
                    val (start, end) = part.split('-').map(String::toInt)
                    require(start in 0 until MINUTES_PER_DAY && end in 0 until MINUTES_PER_DAY)
                    TimeWindow(start, end)
                }
            } catch (e: RuntimeException) {
                DEFAULT_WINDOWS
            }
        }
    }
}

fun hhmm(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** Shown on every block screen, with the user's own reason and clips. */
val MOTIVATION = listOf(
    "Remember why you're doing this.",
    "You set this up for a reason. Trust the version of you that did.",
    "This feeling passes. What you're building lasts.",
    "Every time you let this go, it gets a little easier.",
    "You're worth more than another scroll.",
)
