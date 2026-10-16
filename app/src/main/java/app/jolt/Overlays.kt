package app.jolt

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Full-screen windows drawn on top of whatever app is open. Accessibility services can add
 * these without the separate "display over other apps" permission.
 *
 * Avoid putting the app's name in overlay text: the tamper check searches Settings
 * screens for it, and would match our own overlay.
 */
class Overlays(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var promptView: View? = null
    private var promptTicker: Runnable? = null
    private var blockView: View? = null
    private var blockTicker: Runnable? = null

    /**
     * PANIC works straight away. Continue stays locked until the countdown ends, so carrying
     * on always costs a pause. The prompt then waits for a choice.
     */
    fun showPrompt(sessionMinutes: Int, pauseSeconds: Int, onContinue: () -> Unit, onPanic: () -> Unit) {
        if (promptView != null) return
        val ctx: Context = service
        var secondsLeft = pauseSeconds
        var decided = false

        val countdown = ctx.label(formatSeconds(secondsLeft), 72f, Palette.ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
        }
        val hint = ctx.label("Take a breath. Continue unlocks when the timer ends.", 13f, Palette.MUTED).apply {
            gravity = Gravity.CENTER
        }

        fun decide(action: () -> Unit) {
            if (decided) return
            decided = true
            promptTicker?.let(handler::removeCallbacks)
            action()
        }

        val continueButton = ctx.pillButton("", Palette.NEUTRAL) {
            if (secondsLeft <= 0) decide(onContinue)
        }

        fun updateContinue() {
            val unlocked = secondsLeft <= 0
            continueButton.isEnabled = unlocked
            continueButton.alpha = if (unlocked) 1f else 0.4f
            continueButton.text = if (unlocked) {
                "Continue browsing (+$sessionMinutes min)"
            } else {
                "Continue browsing (in ${formatSeconds(secondsLeft)})"
            }
        }
        updateContinue()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.rounded(Palette.CARD, 24)
            setPadding(ctx.dp(24), ctx.dp(28), ctx.dp(24), ctx.dp(24))
            addView(ctx.label("Time check", 26f, bold = true))
            addView(
                ctx.label("That's $sessionMinutes ${minutes(sessionMinutes)}. Keep going, or lock it down?", 17f, Palette.MUTED),
                spaced(ctx, top = 8),
            )
            addView(countdown, spaced(ctx, top = 12))
            addView(hint, spaced(ctx, top = 0))
            addView(
                ctx.pillButton("PANIC BUTTON!", Palette.PANIC) { decide(onPanic) }.apply { textSize = 22f; minHeight = ctx.dp(72) },
                spaced(ctx, top = 24),
            )
            addView(continueButton, spaced(ctx, top = 12))
        }

        val root = scrim(ctx, Palette.SCRIM).apply {
            addView(card, FrameLayout.LayoutParams(ctx.dp(340), WRAP_CONTENT, Gravity.CENTER))
        }
        promptView = root
        add(root)
        vibrate()

        val ticker = object : Runnable {
            override fun run() {
                secondsLeft -= 1
                updateContinue()
                if (secondsLeft <= 0) {
                    countdown.visibility = View.GONE
                    hint.text = "Your call."
                } else {
                    countdown.text = formatSeconds(secondsLeft)
                    handler.postDelayed(this, 1000)
                }
            }
        }
        promptTicker = ticker
        handler.postDelayed(ticker, 1000)
    }

    fun hidePrompt() {
        promptTicker?.let(handler::removeCallbacks)
        promptTicker = null
        promptView?.let(::remove)
        promptView = null
    }

    fun showBlock(remainingMs: () -> Long, onDismiss: () -> Unit) {
        if (blockView != null) return
        val ctx: Context = service

        val countdown = ctx.label(formatMmSs(remainingMs()), 56f, Palette.PANIC, bold = true).apply {
            gravity = Gravity.CENTER
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(32), 0, ctx.dp(32), 0)
            addView(ctx.label("BLOCKED", 34f, bold = true).apply { gravity = Gravity.CENTER })
            addView(
                ctx.label("Your watched apps are locked for", 17f, Palette.MUTED).apply { gravity = Gravity.CENTER },
                spaced(ctx, top = 8),
            )
            addView(countdown, spaced(ctx, top = 8))
            addView(
                ctx.label(SUGGESTIONS.random(), 20f, Palette.ACCENT, bold = true).apply { gravity = Gravity.CENTER },
                spaced(ctx, top = 24),
            )
            addView(
                ctx.pillButton("OK", Palette.NEUTRAL) {
                    hideBlock()
                    onDismiss()
                },
                spaced(ctx, top = 40),
            )
        }

        val root = scrim(ctx, Palette.BACKGROUND).apply {
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        }
        blockView = root
        add(root)

        val ticker = object : Runnable {
            override fun run() {
                val left = remainingMs()
                if (left <= 0) {
                    hideBlock()
                } else {
                    countdown.text = formatMmSs(left)
                    handler.postDelayed(this, 1000)
                }
            }
        }
        blockTicker = ticker
        handler.postDelayed(ticker, 1000)
    }

    fun hideBlock() {
        blockTicker?.let(handler::removeCallbacks)
        blockTicker = null
        blockView?.let(::remove)
        blockView = null
    }

    fun hideAll() {
        hidePrompt()
        hideBlock()
    }

    private fun add(view: View) {
        val params = WindowManager.LayoutParams(
            MATCH_PARENT,
            MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        windowManager.addView(view, params)
    }

    private fun remove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already gone.
        }
    }

    private fun vibrate() {
        @Suppress("DEPRECATION")
        val vibrator = service.getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun scrim(ctx: Context, color: Int) = BackBlockingFrame(ctx).apply {
        setBackgroundColor(color)
        isClickable = true // swallow touches meant for the app underneath
        isFocusableInTouchMode = true
    }

    private fun spaced(ctx: Context, top: Int) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(top) }

    /** "45" under a minute, "2:05" from a minute up. */
    private fun formatSeconds(s: Int) = if (s < 60) "$s" else formatMmSs(s * 1000L)

    private fun minutes(n: Int) = if (n == 1) "minute" else "minutes"

    /** Eats the back key so the overlay can't be dismissed without choosing. */
    private class BackBlockingFrame(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            event.keyCode == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event)
    }

    private companion object {
        val SUGGESTIONS = listOf(
            "Put the phone down.",
            "Go for a walk.",
            "Drink a glass of water.",
            "Play a game instead.",
            "Text a friend.",
            "Stretch for two minutes.",
            "Read a few pages of a book.",
        )
    }
}
