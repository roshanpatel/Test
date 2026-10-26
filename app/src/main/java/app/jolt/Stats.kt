package app.jolt

// Pure progress tracking: daily totals, streaks and milestones. No Android imports.

/** Days of history kept. */
const val KEEP_DAYS = 400

/** Continue presses in a row before Continue also requires typing your reason. */
const val TYPE_REASON_ON_CONTINUE = 3

data class DayStats(
    val watchedMs: Long = 0,
    val panics: Int = 0,
    val continues: Int = 0,
) {
    /** A good day: you pressed PANIC, or never chose to keep going. */
    val good: Boolean get() = panics > 0 || continues == 0
}

data class Stats(
    val firstDay: Long = NO_DAY,
    val days: Map<Long, DayStats> = emptyMap(),
    val totalPanics: Int = 0,
    val totalBlockedMs: Long = 0,
) {
    fun day(day: Long) = days[day] ?: DayStats()

    /** Starts counting from [today] if nothing has been recorded yet. */
    fun ensureStarted(today: Long) = if (firstDay == NO_DAY) copy(firstDay = today) else this

    fun addWatched(day: Long, ms: Long) = update(day) { it.copy(watchedMs = it.watchedMs + ms) }

    fun recordContinue(day: Long) = update(day) { it.copy(continues = it.continues + 1) }

    fun recordPanic(day: Long, blockMs: Long) =
        update(day) { it.copy(panics = it.panics + 1) }
            .copy(totalPanics = totalPanics + 1, totalBlockedMs = totalBlockedMs + blockMs)

    /**
     * Good days in a row, ending today. Today counts once it's good; until then it doesn't
     * break the streak, since there's still time to press PANIC.
     *
     * One bad day per [FREEZE_EVERY_DAYS] is frozen: it doesn't add to the streak, but it
     * doesn't break it either. One slip shouldn't wipe out weeks of effort.
     */
    fun streakInfo(today: Long): Streak {
        if (firstDay == NO_DAY) return Streak(0, 0, true)
        var d = if (day(today).good) today else today - 1
        var count = 0
        var frozen = 0
        var lastFrozen: Long? = null
        // A bad day is only forgiven if there's a good run before it to protect.
        var pending: Long? = null
        while (d >= firstDay) {
            if (day(d).good) {
                if (pending != null) {
                    frozen++
                    lastFrozen = pending
                    pending = null
                }
                count++
            } else {
                val previous = pending ?: lastFrozen
                if (pending != null || (previous != null && previous - d < FREEZE_EVERY_DAYS)) break
                pending = d
            }
            d--
        }
        val ready = lastFrozen == null || today - lastFrozen >= FREEZE_EVERY_DAYS
        return Streak(count, frozen, ready)
    }

    fun streak(today: Long): Int = streakInfo(today).days

    /** Yesterday was a bad day: kept going, never pressed PANIC. */
    fun slippedYesterday(today: Long): Boolean =
        firstDay != NO_DAY && today - 1 >= firstDay && !day(today - 1).good

    private fun update(day: Long, change: (DayStats) -> DayStats): Stats {
        val first = if (firstDay == NO_DAY) day else minOf(firstDay, day)
        val updated = (days + (day to change(day(day)))).filterKeys { it > day - KEEP_DAYS }
        return copy(firstDay = first, days = updated)
    }

    fun encode(): String = buildString {
        append("v1|").append(firstDay).append('|').append(totalPanics).append('|').append(totalBlockedMs).append('|')
        days.entries.sortedBy { it.key }.joinTo(this, ",") { (d, s) -> "$d:${s.watchedMs}:${s.panics}:${s.continues}" }
    }

    companion object {
        const val NO_DAY = Long.MIN_VALUE

        fun decode(text: String?): Stats {
            if (text.isNullOrEmpty()) return Stats()
            return try {
                val parts = text.split('|')
                require(parts.size == 5 && parts[0] == "v1")
                val days = if (parts[4].isEmpty()) emptyMap() else parts[4].split(',').associate { entry ->
                    val f = entry.split(':')
                    f[0].toLong() to DayStats(f[1].toLong(), f[2].toInt(), f[3].toInt())
                }
                Stats(parts[1].toLong(), days, parts[2].toInt(), parts[3].toLong())
            } catch (e: RuntimeException) {
                Stats()
            }
        }
    }
}

data class Streak(
    val days: Int,
    /** Bad days forgiven inside the current streak. */
    val freezesUsed: Int,
    /** Whether the next bad day would be forgiven. */
    val freezeReady: Boolean,
)

/** A streak freeze becomes available again this many days after the last one. */
const val FREEZE_EVERY_DAYS = 7

interface StatsStore {
    fun loadStats(): Stats
    fun saveStats(stats: Stats)
}

val PANIC_MILESTONES = listOf(1, 5, 10, 25, 50, 100, 250, 500, 1000)
val STREAK_MILESTONES = listOf(3, 7, 14, 30, 60, 100, 365)

fun panicMilestone(count: Int): String? = when (count) {
    1 -> "Your first PANIC. The first one is the hardest."
    in PANIC_MILESTONES -> "Milestone: $count PANICs!"
    else -> null
}

fun streakMilestone(days: Int): String? =
    if (days in STREAK_MILESTONES) "Milestone: a $days-day streak!" else null

fun nextPanicMilestone(count: Int): Int? = PANIC_MILESTONES.firstOrNull { it > count }

/** How far [count] is between the last milestone reached and the next one, 0..1. */
fun milestoneProgress(count: Int): Float {
    val next = nextPanicMilestone(count) ?: return 1f
    val previous = PANIC_MILESTONES.lastOrNull { it <= count } ?: 0
    return (count - previous).toFloat() / (next - previous)
}

private class Equivalent(val one: String, val many: String, val minutes: Int)

private val EQUIVALENTS = listOf(
    Equivalent("a book", "books", 360),
    Equivalent("a film", "films", 120),
    Equivalent("a gym session", "gym sessions", 60),
    Equivalent("a TV episode", "TV episodes", 45),
    Equivalent("a walk round the block", "walks round the block", 15),
)

/** Time back put in everyday terms: "about 3 films". Null under 15 minutes. */
fun timeBackInRealTerms(ms: Long): String? {
    val minutes = ms / MINUTE_MS
    val unit = EQUIVALENTS.firstOrNull { minutes >= it.minutes } ?: return null
    val count = minutes / unit.minutes
    return if (count == 1L) "about ${unit.one}" else "about $count ${unit.many}"
}

/** Shown the day after a slip. Picked by date, so it stays the same all day. */
val SLIP_KINDNESS = listOf(
    "Yesterday was hard. That's human, not failure. Today is a fresh start.",
    "One rough day doesn't undo your progress. Be kind to yourself, and go again.",
    "Everyone slips. What matters is that you're still here, still trying.",
    "Progress isn't a straight line. You're doing better than you think.",
    "You're not starting from zero. You're starting from experience.",
)

fun slipKindness(today: Long): String = SLIP_KINDNESS[Math.floorMod(today, SLIP_KINDNESS.size.toLong()).toInt()]

/** For a PANIC that came after one or more Continues. */
val COMEBACK_PRAISE = listOf(
    "Stopping late is still stopping. Well done.",
    "You kept going for a while, and still chose to stop. That counts.",
    "It's never too late to stop. You just proved it.",
)

/** For the first PANIC after a bad day. */
const val WELCOME_BACK = "Welcome back. Yesterday doesn't define you."

val PANIC_PRAISE = listOf(
    "That took strength.",
    "Future you says thanks.",
    "You chose yourself over the scroll.",
    "Urges pass. You just proved it.",
    "That's how habits break: one choice at a time.",
    "You were in charge, not the app.",
    "Nicely done. Go do something good.",
)

/** "18 h 30 min" or "45 min". */
fun formatHoursMinutes(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / MINUTE_MS
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

/** Lenient match for typing your reason: ignores case, punctuation and extra spaces. */
fun matchesReason(typed: String, reason: String): Boolean {
    fun normalise(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }
        .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    val target = normalise(reason)
    return target.isNotEmpty() && normalise(typed) == target
}
