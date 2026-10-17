package app.jolt

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var clock: Clock

    private lateinit var status: TextView
    private lateinit var accessibilityRow: SetupRow
    private lateinit var batteryRow: SetupRow
    private lateinit var watchedSummary: TextView
    private lateinit var chooseApps: Button
    private lateinit var lockedNote: TextView
    private lateinit var lockText: TextView
    private lateinit var lockButton: Button
    private val steppers = mutableListOf<Stepper>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        clock = AndroidClock(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        column.addView(label("Jolt", 34f, bold = true))
        column.addView(label("Break the scroll.", 16f, Palette.MUTED))

        status = label("", 17f, bold = true).apply {
            background = rounded(Palette.CARD)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        column.addView(status, block(top = 20))

        column.addView(section("Setup"))
        accessibilityRow = SetupRow(
            title = "1. Accessibility service (required)",
            detail = "Lets Jolt see which app is open, show the time check, and block apps.",
            action = "Open settings",
        ) { Setup.openAccessibilitySettings(this) }
        column.addView(accessibilityRow.view, block(top = 8))
        column.addView(
            label(
                "Toggle greyed out? Android restricts this for apps installed outside the Play Store. " +
                    "Open App info, tap the ⋮ menu, choose \"Allow restricted settings\", then try again.",
                13f,
                Palette.MUTED,
            ),
            block(top = 8),
        )
        column.addView(
            pillButton("Open App info", Palette.NEUTRAL) { Setup.openAppInfo(this) },
            block(top = 8),
        )
        batteryRow = SetupRow(
            title = "2. Ignore battery optimisation (recommended)",
            detail = "Stops the phone from closing Jolt in the background.",
            action = "Allow",
        ) { Setup.requestBatteryExemption(this) }
        column.addView(batteryRow.view, block(top = 12))

        column.addView(section("Watched apps"))
        watchedSummary = label("", 15f, Palette.MUTED)
        column.addView(watchedSummary, block(top = 8))
        chooseApps = pillButton("Choose apps", Palette.NEUTRAL) {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        column.addView(chooseApps, block(top = 8))

        column.addView(section("Timings"))
        steppers += Stepper("Browse time before the check", 1, 1, 60, Stricter.LOWER, { prefs.sessionMinutes }, { prefs.sessionMinutes = it })
        steppers += Stepper("Block length after PANIC", 5, 5, 240, Stricter.HIGHER, { prefs.blockMinutes }, { prefs.blockMinutes = it })
        steppers += Stepper("Wait before Continue unlocks", 10, 10, 300, Stricter.HIGHER, { prefs.pauseSeconds }, { prefs.pauseSeconds = it }, unit = "s")
        steppers.forEach { column.addView(it.view, block(top = 8)) }

        lockedNote = label("", 14f, Palette.PANIC)
        column.addView(lockedNote, block(top = 16))

        column.addView(section("Lock settings"))
        lockText = label("", 15f, Palette.MUTED)
        column.addView(lockText, block(top = 8))
        lockButton = pillButton("", Palette.ACCENT) { onLockButton() }.apply { setTextColor(Palette.BACKGROUND) }
        column.addView(lockButton, block(top = 8))

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Palette.BACKGROUND)
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        val serviceOn = Setup.accessibilityEnabled(this)
        val batteryOk = Setup.batteryUnrestricted(this)
        val watched = prefs.watched
        val blockLeft = prefs.blockRemainingMs(clock)
        val blocked = blockLeft > 0
        val lock = prefs.settledLock(clock)
        val locked = !lock.isOpen(clock)

        when {
            blocked -> status("BLOCKED: ${formatMmSs(blockLeft)} left", Palette.PANIC)
            !serviceOn -> status("Not running yet. Turn on the accessibility service below.", Palette.ACCENT)
            watched.isEmpty() -> status("Running. Choose which apps to watch.", Palette.ACCENT)
            else -> status(
                "Watching ${watched.size} ${if (watched.size == 1) "app" else "apps"}. " +
                    "After ${prefs.sessionMinutes} min you'll get a time check.",
                Palette.GOOD,
            )
        }

        accessibilityRow.update(serviceOn)
        batteryRow.update(batteryOk)

        watchedSummary.text = if (watched.isEmpty()) "No apps yet." else watched.map(::appName).sorted().joinToString(", ")
        chooseApps.isEnabled = !blocked
        chooseApps.alpha = if (blocked) 0.4f else 1f
        steppers.forEach { it.update(blocked = blocked, locked = locked) }
        lockedNote.text = when {
            blocked -> "Settings are locked until the block ends."
            locked -> "Settings are locked. You can still add apps or make timings stricter."
            else -> ""
        }
        lockedNote.visibility = if (blocked || locked) View.VISIBLE else View.GONE

        val unlockLeft = lock.unlockRemainingMs(clock)
        when {
            !locked -> {
                lockText.text = "Stop yourself loosening the rules in a weak moment. " +
                    "Unlocking later takes a 24-hour wait."
                lockButton.text = "Lock settings"
            }
            unlockLeft == null -> {
                lockText.text = "Locked. Uninstalling Jolt or switching it off is blocked too."
                lockButton.text = "Request unlock (24-hour wait)"
            }
            else -> {
                lockText.text = "Unlock requested. Settings open in ${formatDuration(unlockLeft)}."
                lockButton.text = "Cancel unlock request"
            }
        }
    }

    private fun onLockButton() {
        val lock = prefs.settledLock(clock)
        when {
            lock.isOpen(clock) -> AlertDialog.Builder(this)
                .setTitle("Lock settings?")
                .setMessage(
                    "You'll still be able to add apps and make timings stricter, but not loosen them. " +
                        "Uninstalling Jolt or switching it off will be blocked too.\n\n" +
                        "To unlock, you'll request it and wait 24 hours.",
                )
                .setPositiveButton("Lock") { _, _ ->
                    prefs.lock = lock.lock()
                    refresh()
                }
                .setNegativeButton("Not now", null)
                .show()
            lock.unlockAt == null -> prefs.lock = lock.requestUnlock(clock)
            else -> prefs.lock = lock.cancelUnlock()
        }
        refresh()
    }

    private fun status(text: String, color: Int) {
        status.text = text
        status.setTextColor(color)
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun section(title: String) = label(title, 20f, bold = true).apply {
        layoutParams = block(top = 28)
    }

    private fun block(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private inner class SetupRow(title: String, detail: String, action: String, onAction: () -> Unit) {
        private val check = label("", 20f, bold = true)
        private val button = pillButton(action, Palette.ACCENT, onAction).apply { setTextColor(Palette.BACKGROUND) }

        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.CARD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(title, 16f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(check)
            })
            addView(label(detail, 14f, Palette.MUTED), block(top = 4))
            addView(button, block(top = 10))
        }

        fun update(done: Boolean) {
            check.text = if (done) "✓" else "✗"
            check.setTextColor(if (done) Palette.GOOD else Palette.PANIC)
            button.visibility = if (done) View.GONE else View.VISIBLE
        }
    }

    private inner class Stepper(
        title: String,
        private val step: Int,
        private val min: Int,
        private val max: Int,
        private val stricter: Stricter,
        private val get: () -> Int,
        private val set: (Int) -> Unit,
        private val unit: String = "min",
    ) {
        private val value = label("", 18f, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(72)
        }
        private val minus = smallButton("−") { change(-step) }
        private val plus = smallButton("+") { change(step) }

        val view = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.CARD)
            setPadding(dp(16), dp(8), dp(8), dp(8))
            addView(label(title, 15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(minus)
            addView(value)
            addView(plus)
        }

        private var locked = false

        fun update(blocked: Boolean, locked: Boolean) {
            this.locked = locked
            value.text = "${get()} $unit"
            enable(minus, !blocked && (!locked || stricter == Stricter.LOWER))
            enable(plus, !blocked && (!locked || stricter == Stricter.HIGHER))
        }

        private fun enable(button: Button, enabled: Boolean) {
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.4f
        }

        private fun change(delta: Int) {
            val old = get()
            val new = (old + delta).coerceIn(min, max)
            if (locked && !isStricterOrSame(old, new, stricter)) return
            set(new)
            refresh()
        }

        private fun smallButton(text: String, onClick: () -> Unit) =
            pillButton(text, Palette.NEUTRAL, onClick).apply {
                minWidth = dp(48)
                minimumWidth = dp(48)
                setPadding(0, 0, 0, 0)
                textSize = 20f
            }
    }
}
