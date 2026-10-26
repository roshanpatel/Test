package app.jolt

import kotlin.random.Random

// The "human check" sum shown before Continue unlocks. Pure Kotlin, no Android imports.

/** Levels stop getting harder after this. */
const val MAX_MATHS_LEVEL = 6

data class MathProblem(val question: String, val answer: Int)

/**
 * A sum for the given level: 1 for the first Continue in a row, 2 for the second, and so on.
 * Answers are never negative, so a number-only keyboard is enough.
 */
fun mathProblem(level: Int, random: Random = Random.Default): MathProblem {
    fun between(low: Int, high: Int) = random.nextInt(low, high + 1)
    fun add(a: Int, b: Int) = MathProblem("$a + $b", a + b)
    fun subtract(a: Int, b: Int) = MathProblem("$a − $b", a - b)
    return when (level.coerceIn(1, MAX_MATHS_LEVEL)) {
        1 -> add(between(2, 9), between(2, 9))
        2 -> if (random.nextBoolean()) add(between(12, 49), between(3, 9)) else subtract(between(21, 59), between(3, 9))
        3 -> between(11, 19).let { a -> between(3, 9).let { b -> MathProblem("$a × $b", a * b) } }
        4 -> if (random.nextBoolean()) add(between(23, 79), between(14, 49)) else subtract(between(51, 99), between(12, 49))
        5 -> between(12, 19).let { a -> between(11, 19).let { b -> MathProblem("$a × $b", a * b) } }
        else -> {
            val a = between(21, 49)
            val b = between(12, 29)
            val c = between(11, 49)
            MathProblem("$a × $b + $c", a * b + c)
        }
    }
}

val WRONG_ANSWER = listOf(
    "Bzzt. Scroll-bot detected 🤖 Try this one.",
    "Not quite. Brains warm up slowly. Another go:",
    "Close-ish? Here's a fresh one.",
)

val CALCULATOR_JABS = listOf(
    "Nice try. Use your head! 🧠",
    "A calculator? Your brain's free trial hasn't run out yet.",
    "Plot twist: the sum changes when you go back. 😏",
    "Calculators are cheating. Brains are free.",
)

fun isCalculator(pkg: String) = pkg.contains("calculator") || pkg.endsWith(".calc")
