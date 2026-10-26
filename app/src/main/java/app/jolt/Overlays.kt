package app.jolt

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.VideoView
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** What the time check shows, gathered by the service. */
class PromptInfo(
    val sessionMinutes: Int,
    val pauseSeconds: Int,
    val breathing: Boolean,
    val reason: String,
    val todayWatchedMs: Long,
    val needsReason: Boolean,
)

/** What the block screen celebrates right after PANIC. */
class Celebration(
    val panicNumber: Int,
    val praise: String,
    val milestones: List<String>,
    val streakDays: Int,
)

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
    private var breathingView: BreathingView? = null
    private var blockView: View? = null
    private var blockTicker: Runnable? = null
    private var video: VideoView? = null

    /**
     * PANIC works straight away. Continue stays locked until the countdown ends, so carrying
     * on always costs a pause. If [PromptInfo.needsReason], Continue also needs the user's
     * reason typed out. The prompt then waits for a choice.
     */
    fun showPrompt(info: PromptInfo, onContinue: () -> Unit, onPanic: () -> Unit) {
        if (promptView != null) return
        val ctx: Context = service
        var secondsLeft = info.pauseSeconds
        var decided = false
        val reason = info.reason.ifEmpty { DEFAULT_REASON }

        val countdown = ctx.label(formatSeconds(secondsLeft), if (info.breathing) 20f else 72f, Palette.ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
        }
        val hintLabel = ctx.label("Take a breath. Continue unlocks when the timer ends.", 13f, Palette.MUTED).apply {
            gravity = Gravity.CENTER
        }

        fun decide(action: () -> Unit) {
            if (decided) return
            decided = true
            promptTicker?.let(handler::removeCallbacks)
            action()
        }

        // Typing step, shown once the countdown ends when a reason is required.
        val typed = StrictEditText(ctx).apply {
            hint = "Type it here"
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED)
            background = ctx.rounded(Palette.NEUTRAL, 12)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        }
        val typingWarning = ctx.label("Letter by letter, please. No shortcuts.", 13f, Palette.PANIC).apply {
            visibility = View.GONE
        }
        val typingStep = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(ctx.label("Is what you're doing important?", 18f, bold = true))
            addView(ctx.label("You've kept going a few times in a row. To continue, type your reason:", 14f, Palette.MUTED), spaced(ctx, top = 4))
            addView(ctx.label("“$reason”", 15f, Palette.ACCENT, bold = true), spaced(ctx, top = 8))
            addView(typed, spaced(ctx, top = 8))
            addView(typingWarning, spaced(ctx, top = 4))
        }

        val continueButton = ctx.pillButton("", Palette.NEUTRAL) {
            if (secondsLeft <= 0 && (!info.needsReason || matchesReason(typed.text.toString(), reason))) decide(onContinue)
        }

        fun updateContinue() {
            val timerDone = secondsLeft <= 0
            val unlocked = timerDone && (!info.needsReason || matchesReason(typed.text.toString(), reason))
            continueButton.isEnabled = unlocked
            continueButton.alpha = if (unlocked) 1f else 0.4f
            continueButton.text = if (timerDone) {
                "Continue browsing (+${info.sessionMinutes} min)"
            } else {
                "Continue browsing (in ${formatSeconds(secondsLeft)})"
            }
        }
        // Anything that adds several characters at once (paste, autofill, a keyboard
        // suggestion) wipes the box, so the reason really has to be typed out.
        typed.addTextChangedListener(object : TextWatcher {
            private var shortcut = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (count - before > MAX_CHARS_PER_KEYSTROKE) shortcut = true
            }
            override fun afterTextChanged(s: Editable?) {
                if (shortcut) {
                    shortcut = false
                    typingWarning.visibility = View.VISIBLE
                    s?.clear()
                }
                updateContinue()
            }
        })
        updateContinue()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.rounded(Palette.CARD, 24)
            setPadding(ctx.dp(24), ctx.dp(28), ctx.dp(24), ctx.dp(24))
            addView(ctx.label("Time check", 26f, bold = true))
            if (info.reason.isNotEmpty()) {
                addView(ctx.label("“${info.reason}”", 19f, Palette.ACCENT, bold = true), spaced(ctx, top = 10))
            }
            addView(
                ctx.label(
                    "That's ${info.sessionMinutes} ${minutes(info.sessionMinutes)}. " +
                        "Today so far: ${formatHoursMinutes(info.todayWatchedMs)} in these apps.",
                    16f,
                    Palette.MUTED,
                ),
                spaced(ctx, top = 8),
            )
            if (info.breathing) {
                val breath = BreathingView(ctx)
                breathingView = breath
                addView(breath, LinearLayout.LayoutParams(MATCH_PARENT, ctx.dp(170)).apply { topMargin = ctx.dp(12) })
            }
            addView(countdown, spaced(ctx, top = if (info.breathing) 4 else 12))
            addView(hintLabel, spaced(ctx, top = 0))
            addView(
                ctx.pillButton("PANIC BUTTON!", Palette.PANIC) { decide(onPanic) }.apply { textSize = 22f; minHeight = ctx.dp(72) },
                spaced(ctx, top = 20),
            )
            addView(typingStep, spaced(ctx, top = 16))
            addView(continueButton, spaced(ctx, top = 12))
        }

        val root = scrim(ctx, Palette.SCRIM).apply {
            addView(
                ScrollView(ctx).apply {
                    isFillViewport = true
                    addView(
                        FrameLayout(ctx).apply {
                            addView(card, FrameLayout.LayoutParams(ctx.dp(340), WRAP_CONTENT, Gravity.CENTER))
                        },
                    )
                },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
            )
        }
        promptView = root
        add(root, typing = info.needsReason)
        vibrate(longArrayOf(0, 400))
        breathingView?.start()

        val ticker = object : Runnable {
            override fun run() {
                secondsLeft -= 1
                if (secondsLeft <= 0) {
                    countdown.visibility = View.GONE
                    hintLabel.text = "Your call."
                    if (info.needsReason) {
                        typingStep.visibility = View.VISIBLE
                        typed.requestFocus()
                    }
                } else {
                    countdown.text = formatSeconds(secondsLeft)
                    handler.postDelayed(this, 1000)
                }
                updateContinue()
            }
        }
        promptTicker = ticker
        handler.postDelayed(ticker, 1000)
    }

    fun hidePrompt() {
        promptTicker?.let(handler::removeCallbacks)
        promptTicker = null
        breathingView?.stop()
        breathingView = null
        promptView?.let(::remove)
        promptView = null
    }

    fun showBlock(remainingMs: () -> Long, celebration: Celebration?, onDismiss: () -> Unit) {
        if (blockView != null) return
        val ctx: Context = service

        val countdown = ctx.label(formatMmSs(remainingMs()), 56f, Palette.PANIC, bold = true).apply {
            gravity = Gravity.CENTER
        }

        fun centered(text: String, sizeSp: Float, color: Int = Palette.TEXT, bold: Boolean = false) =
            ctx.label(text, sizeSp, color, bold).apply { gravity = Gravity.CENTER }

        // Everything fits on one screen: the clip (or a spacer) soaks up whatever height is left.
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(28), ctx.dp(16), ctx.dp(28), ctx.dp(16))
            if (celebration != null) {
                addView(inspiration(ctx), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = ctx.dp(12) })
                addView(centered("🎉 Well done!", 30f, Palette.GOOD, bold = true))
                addView(centered("That's PANIC #${celebration.panicNumber}. ${celebration.praise}", 17f), spaced(ctx, top = 6))
                celebration.milestones.forEach {
                    addView(centered("🏆 $it", 17f, Palette.ACCENT, bold = true), spaced(ctx, top = 6))
                }
                if (celebration.streakDays > 1) {
                    addView(centered("🔥 ${celebration.streakDays}-day streak", 15f, Palette.MUTED), spaced(ctx, top = 4))
                }
                addView(centered("Your watched apps are locked for", 15f, Palette.MUTED), spaced(ctx, top = 16))
                countdown.textSize = 44f
            } else {
                addView(View(ctx), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                addView(centered("BLOCKED", 34f, bold = true))
                addView(centered("Your watched apps are locked for", 17f, Palette.MUTED), spaced(ctx, top = 8))
            }
            addView(countdown, spaced(ctx, top = 4))
            addView(centered(SUGGESTIONS.random(), 18f, Palette.ACCENT, bold = true), spaced(ctx, top = 12))
            addView(
                ctx.pillButton("OK", Palette.NEUTRAL) {
                    hideBlock()
                    onDismiss()
                },
                spaced(ctx, top = 16),
            )
            if (celebration == null) addView(View(ctx), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }

        val root = scrim(ctx, Palette.BACKGROUND).apply {
            fitsSystemWindows = true // keep clear of the status and navigation bars
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (celebration != null) {
                addView(ConfettiView(ctx), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        }
        blockView = root
        add(root)
        if (celebration != null) vibrate(longArrayOf(0, 80, 80, 80, 80, 160))

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
        video?.stopPlayback()
        video = null
        blockView?.let(::remove)
        blockView = null
    }

    /** One of the user's own clips if they've added any, otherwise a built-in animation. */
    private fun inspiration(ctx: Context): View {
        Clips(ctx).random()?.let { clip -> clipView(ctx, clip)?.let { return it } }
        val scene = Scene.entries.random()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(SceneView(ctx, scene), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(ctx.label(scene.caption, 15f, Palette.MUTED).apply { gravity = Gravity.CENTER }, spaced(ctx, top = 6))
        }
    }

    private fun clipView(ctx: Context, file: File): View? = try {
        val media: View = if (Clips.isVideo(file)) {
            VideoView(ctx).apply {
                setVideoPath(file.path)
                setOnPreparedListener {
                    it.isLooping = true
                    it.setVolume(0f, 0f)
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    visibility = View.GONE
                    true
                }
                video = this
            }
        } else {
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(decodeImage(ctx, file))
            }
        }
        FrameLayout(ctx).apply {
            addView(media, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        }
    } catch (e: Exception) {
        null
    }

    /** Decodes a photo or GIF, animating GIFs where the phone supports it. */
    private fun decodeImage(ctx: Context, file: File): Drawable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_IMAGE_PX) decoder.setTargetSampleSize(longest / MAX_IMAGE_PX + 1)
            }
            if (drawable is AnimatedImageDrawable) {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                drawable.start()
            }
            return drawable
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = (longest / MAX_IMAGE_PX + 1).coerceAtLeast(1) }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: error("Unreadable image")
        return BitmapDrawable(ctx.resources, bitmap)
    }

    fun hideAll() {
        hidePrompt()
        hideBlock()
    }

    private fun add(view: View, typing: Boolean = false) {
        val params = WindowManager.LayoutParams(
            MATCH_PARENT,
            MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        if (typing) {
            @Suppress("DEPRECATION")
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windowManager.addView(view, params)
    }

    private fun remove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already gone.
        }
    }

    private fun vibrate(pattern: LongArray) {
        @Suppress("DEPRECATION")
        val vibrator = service.getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
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

    /**
     * A text box with every typing shortcut turned off: no autofill, no keyboard suggestions
     * or learning, no paste. Treated as a visible password, which makes most keyboards drop
     * word suggestions and swipe typing.
     */
    private class StrictEditText(context: Context) : EditText(context) {
        init {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.DEFAULT // the password variation switches to monospace
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isLongClickable = false
            setTextIsSelectable(false)
            val noMenu = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
                override fun onDestroyActionMode(mode: ActionMode?) {}
            }
            customSelectionActionModeCallback = noMenu
            customInsertionActionModeCallback = noMenu
        }

        override fun isSuggestionsEnabled() = false

        override fun getAutofillType() = View.AUTOFILL_TYPE_NONE

        override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
            android.R.id.paste, android.R.id.pasteAsPlainText, android.R.id.autofill -> false
            else -> super.onTextContextMenuItem(id)
        }
    }

    /** Eats the back key so the overlay can't be dismissed without choosing. */
    private class BackBlockingFrame(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            event.keyCode == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event)
    }

    /** A circle that grows for 4 seconds (breathe in) and shrinks for 6 (breathe out). */
    private class BreathingView(context: Context) : View(context) {
        private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT; alpha = 70 }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.ACCENT
            style = Paint.Style.STROKE
            strokeWidth = context.dp(2).toFloat()
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.TEXT
            textAlign = Paint.Align.CENTER
            textSize = context.dp(18).toFloat()
            typeface = Typeface.DEFAULT_BOLD
        }
        private val ease = AccelerateDecelerateInterpolator()
        private var phase = 0f // 0..1 over one 10-second breath
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BREATH_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        fun start() = animator.start()
        fun stop() = animator.cancel()

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val inhaling = phase < INHALE_SHARE
            val size = if (inhaling) ease.getInterpolation(phase / INHALE_SHARE) else 1 - ease.getInterpolation((phase - INHALE_SHARE) / (1 - INHALE_SHARE))
            val maxR = min(width, height) / 2f - ring.strokeWidth
            val minR = maxR * 0.4f
            val r = minR + (maxR - minR) * size
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, maxR, ring)
            canvas.drawCircle(cx, cy, r, circle)
            canvas.drawText(if (inhaling) "Breathe in" else "Breathe out", cx, cy - (text.ascent() + text.descent()) / 2, text)
        }

        private companion object {
            const val BREATH_MS = 10_000L
            const val INHALE_SHARE = 0.4f
        }
    }

    /** A short burst of falling confetti. */
    private class ConfettiView(context: Context) : View(context) {
        private class Piece(val x: Float, val start: Float, val speed: Float, val drift: Float, val spin: Float, val size: Float, val color: Int)

        private val colors = intArrayOf(Palette.ACCENT, Palette.GOOD, Palette.PANIC, 0xFF42A5F5.toInt(), 0xFFAB47BC.toInt())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val startedAt = SystemClock.uptimeMillis()
        private val pieces = List(90) {
            Piece(
                x = Random.nextFloat(),
                start = Random.nextFloat() * 0.4f,
                speed = 0.5f + Random.nextFloat() * 0.7f,
                drift = (Random.nextFloat() - 0.5f) * 0.3f,
                spin = Random.nextFloat() * 720f,
                size = context.dp(6 + Random.nextInt(6)).toFloat(),
                color = colors[Random.nextInt(colors.size)],
            )
        }

        init {
            isClickable = false
        }

        override fun onDraw(canvas: Canvas) {
            val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
            if (t > DURATION_S) return
            for (p in pieces) {
                // Pieces start staggered above the screen and fall at their own speed.
                val y = height * (t / DURATION_S * p.speed * 1.6f - p.start)
                val x = width * (p.x + p.drift * t / DURATION_S)
                paint.color = p.color
                paint.alpha = (255 * (1 - t / DURATION_S)).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.rotate(p.spin * t, x, y)
                canvas.drawRect(x - p.size / 2, y - p.size / 4, x + p.size / 2, y + p.size / 4, paint)
                canvas.restore()
            }
            postInvalidateOnAnimation()
        }

        private companion object {
            const val DURATION_S = 3f
        }
    }

    private companion object {
        const val DEFAULT_REASON = "I choose to keep scrolling"
        const val MAX_IMAGE_PX = 1080
        const val MAX_CHARS_PER_KEYSTROKE = 2

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
