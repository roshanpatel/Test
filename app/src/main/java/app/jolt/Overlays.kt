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
import android.media.AudioManager
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
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
    /** Length of the session Continue would buy. */
    val continueMs: Long,
    val pauseSeconds: Int,
    val breathing: Boolean,
    val reason: String,
    val todayWatchedMs: Long,
    val needsReason: Boolean,
    val maths: Boolean,
    /** 1 for the first Continue in a row, rising with each one. */
    val mathsLevel: Int,
    /** Time left under the daily limit, or null if there's no limit. */
    val dailyLeftMs: Long?,
    val clipSound: Boolean,
)

/** What the block screen celebrates right after PANIC. */
class Celebration(
    val panicNumber: Int,
    val praise: String,
    val milestones: List<String>,
    val streakDays: Int,
    /** e.g. "4 h 30 min back so far: about 2 films". */
    val timeBack: String?,
    /** e.g. "2 more to 25 PANICs". */
    val nextMilestone: String?,
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
    private var promptVideo: VideoView? = null
    /** The clip shown last, so the next one is different. */
    private var lastClip: String? = null
    private var jabView: View? = null

    /** The maths question is on screen, waiting for an answer. */
    var mathsShowing = false
        private set

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
            addView(
                ctx.label(
                    "You've kept going a few times in a row. No judgement, just check in with yourself. " +
                        "To continue, type your reason:",
                    14f,
                    Palette.MUTED,
                ),
                spaced(ctx, top = 4),
            )
            addView(ctx.label("“$reason”", 15f, Palette.ACCENT, bold = true), spaced(ctx, top = 8))
            addView(typed, spaced(ctx, top = 8))
            addView(typingWarning, spaced(ctx, top = 4))
        }

        // Maths step: a sum that gets harder with each Continue in a row.
        var problem = mathProblem(info.mathsLevel)
        var mathsSolved = !info.maths
        val mathsQuestion = ctx.label("${problem.question} = ?", 30f, Palette.ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
        }
        val mathsFeedback = ctx.label("", 13f, Palette.PANIC).apply { visibility = View.GONE }
        val mathsAnswer = StrictEditText(ctx, numeric = true).apply {
            hint = "Answer"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED)
            background = ctx.rounded(Palette.NEUTRAL, 12)
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
        }
        val mathsStep = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(ctx.label("Human check, level ${info.mathsLevel.coerceAtMost(MAX_MATHS_LEVEL)}", 18f, bold = true))
            addView(
                ctx.label("Prove you're a human, not a scroll-bot. It gets harder each time you keep going.", 14f, Palette.MUTED),
                spaced(ctx, top = 4),
            )
            addView(mathsQuestion, spaced(ctx, top = 10))
            addView(mathsAnswer, spaced(ctx, top = 8))
            addView(mathsFeedback, spaced(ctx, top = 4))
        }

        fun canContinue() = secondsLeft <= 0 && mathsSolved &&
            (!info.needsReason || matchesReason(typed.text.toString(), reason))

        val continueButton = ctx.pillButton("", Palette.NEUTRAL) {
            if (canContinue()) decide(onContinue)
        }

        fun updateContinue() {
            val unlocked = canContinue()
            continueButton.isEnabled = unlocked
            continueButton.alpha = if (unlocked) 1f else 0.4f
            continueButton.text = when {
                secondsLeft > 0 -> "Continue browsing (in ${formatSeconds(secondsLeft)})"
                !mathsSolved -> "Solve the sum to continue"
                !unlocked -> "Type your reason to continue"
                else -> "Continue browsing (+${formatShort(info.continueMs)})"
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

        var clipFrame: View? = null
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
                    "Today so far: ${formatHoursMinutes(info.todayWatchedMs)} in these apps." +
                        (info.dailyLeftMs?.let { " ${formatHoursMinutes(it)} left of today's limit." } ?: ""),
                    16f,
                    Palette.MUTED,
                ),
                spaced(ctx, top = 8),
            )
            // Your own clip, if you've added any: a reminder of what this is for, right at the
            // moment of choosing. It takes the breathing circle's place.
            val clip = pickClip(ctx)?.let { clipView(ctx, it, info.clipSound) { v -> promptVideo = v } }
            if (clip != null) {
                clipFrame = clip
                addView(clip, LinearLayout.LayoutParams(MATCH_PARENT, ctx.dp(PROMPT_CLIP_DP)).apply { topMargin = ctx.dp(12) })
            } else if (info.breathing) {
                val breath = BreathingView(ctx)
                breathingView = breath
                addView(breath, LinearLayout.LayoutParams(MATCH_PARENT, ctx.dp(170)).apply { topMargin = ctx.dp(12) })
            }
            addView(countdown, spaced(ctx, top = if (clipFrame != null || info.breathing) 4 else 12))
            addView(hintLabel, spaced(ctx, top = 0))
            addView(
                ctx.pillButton("PANIC BUTTON!", Palette.PANIC) { decide(onPanic) }.apply { textSize = 22f; minHeight = ctx.dp(72) },
                spaced(ctx, top = 20),
            )
            addView(mathsStep, spaced(ctx, top = 16))
            addView(typingStep, spaced(ctx, top = 16))
            addView(continueButton, spaced(ctx, top = 12))
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(
                FrameLayout(ctx).apply {
                    addView(card, FrameLayout.LayoutParams(ctx.dp(340), WRAP_CONTENT, Gravity.CENTER))
                },
            )
        }
        val root = scrim(ctx, Palette.SCRIM).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Shrink to the space above the keyboard, so the scroll view can bring the box into view.
                setOnApplyWindowInsetsListener { view, insets ->
                    val keyboard = insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars())
                    view.setPadding(0, keyboard.top, 0, keyboard.bottom)
                    // The active step sits at the top of the card, so keep the top in view.
                    if (typed.hasFocus() || mathsAnswer.hasFocus()) scroll.post { scroll.smoothScrollTo(0, 0) }
                    insets
                }
            }
        }

        /**
         * Moves [step] and Continue to the top of the screen, right under the title, so the
         * keyboard can't cover them.
         */
        fun showStep(step: View, input: EditText) {
            breathingView?.stop()
            breathingView?.visibility = View.GONE
            // The clip stays, just smaller, to make room for the keyboard.
            clipFrame?.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ctx.dp(PROMPT_CLIP_SMALL_DP)).apply {
                topMargin = ctx.dp(12)
            }
            card.removeView(step)
            card.removeView(continueButton)
            card.addView(step, 1, spaced(ctx, top = 12))
            card.addView(continueButton, 2, spaced(ctx, top = 12))
            card.layoutParams = FrameLayout.LayoutParams(ctx.dp(340), WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = ctx.dp(24)
            }
            step.visibility = View.VISIBLE
            input.requestFocus()
            scroll.post { scroll.scrollTo(0, 0) }
        }

        fun showTypingStep() = showStep(typingStep, typed)

        fun showMathsStep() {
            mathsShowing = true
            showStep(mathsStep, mathsAnswer)
        }

        // Checks the answer as soon as it has as many digits as the right one.
        mathsAnswer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val typedAnswer = s?.toString()?.trim().orEmpty()
                if (mathsSolved || typedAnswer.length < problem.answer.toString().length) return
                if (typedAnswer.toIntOrNull() == problem.answer) {
                    mathsSolved = true
                    mathsShowing = false
                    mathsStep.visibility = View.GONE
                    if (info.needsReason) {
                        showTypingStep()
                    } else {
                        hideKeyboard(mathsAnswer)
                    }
                } else {
                    problem = mathProblem(info.mathsLevel)
                    mathsQuestion.text = "${problem.question} = ?"
                    mathsFeedback.text = WRONG_ANSWER.random()
                    mathsFeedback.visibility = View.VISIBLE
                    s?.clear()
                }
                updateContinue()
            }
        })
        promptView = root
        add(root, typing = info.needsReason || info.maths)
        vibrate(longArrayOf(0, 400))
        breathingView?.start()

        val ticker = object : Runnable {
            override fun run() {
                secondsLeft -= 1
                if (secondsLeft <= 0) {
                    countdown.visibility = View.GONE
                    hintLabel.text = "Your call."
                    when {
                        info.maths -> showMathsStep()
                        info.needsReason -> showTypingStep()
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
        mathsShowing = false
        promptVideo?.stopPlayback()
        promptVideo = null
        promptTicker?.let(handler::removeCallbacks)
        promptTicker = null
        breathingView?.stop()
        breathingView = null
        promptView?.let(::remove)
        promptView = null
    }

    fun showBlock(
        remainingMs: () -> Long,
        celebration: Celebration?,
        dailyLimit: Boolean,
        clipSound: Boolean,
        onDismiss: () -> Unit,
    ) {
        if (blockView != null) return
        val ctx: Context = service

        val countdown = ctx.label(formatDuration(remainingMs()), 56f, Palette.PANIC, bold = true).apply {
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
                addView(inspiration(ctx, clipSound), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = ctx.dp(12) })
                addView(centered("🎉 Well done!", 30f, Palette.GOOD, bold = true))
                addView(centered("That's PANIC #${celebration.panicNumber}. ${celebration.praise}", 17f), spaced(ctx, top = 6))
                celebration.milestones.forEach {
                    addView(centered("🏆 $it", 17f, Palette.ACCENT, bold = true), spaced(ctx, top = 6))
                }
                if (celebration.streakDays > 1) {
                    addView(centered("🔥 ${celebration.streakDays}-day streak", 15f, Palette.MUTED), spaced(ctx, top = 4))
                }
                celebration.timeBack?.let { addView(centered("⏳ $it", 14f, Palette.MUTED), spaced(ctx, top = 4)) }
                celebration.nextMilestone?.let { addView(centered("🏆 $it", 14f, Palette.MUTED), spaced(ctx, top = 4)) }
                addView(centered("Your watched apps are locked for", 15f, Palette.MUTED), spaced(ctx, top = 16))
                countdown.textSize = 44f
            } else if (dailyLimit) {
                addView(View(ctx), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                addView(centered("That's today's time", 30f, bold = true))
                addView(
                    centered("You've used your daily limit in watched apps. They open again at midnight, in", 17f, Palette.MUTED),
                    spaced(ctx, top = 8),
                )
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
                    countdown.text = formatDuration(left)
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
    private fun inspiration(ctx: Context, sound: Boolean): View {
        pickClip(ctx)?.let { clip -> clipView(ctx, clip, sound) { video = it }?.let { return it } }
        val scene = Scene.entries.random()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(SceneView(ctx, scene), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(ctx.label(scene.caption, 15f, Palette.MUTED).apply { gravity = Gravity.CENTER }, spaced(ctx, top = 6))
        }
    }

    /** A random clip, avoiding the one shown last time when there's a choice. */
    private fun pickClip(ctx: Context): File? {
        val all = Clips(ctx).list()
        val pick = all.filter { it.name != lastClip }.randomOrNull() ?: all.randomOrNull()
        lastClip = pick?.name
        return pick
    }

    /**
     * Shows a photo, GIF or video. Videos loop, muted unless [sound] is on and the phone
     * isn't on silent or vibrate; even then they play quietly.
     */
    private fun clipView(ctx: Context, file: File, sound: Boolean, onVideo: (VideoView) -> Unit): View? = try {
        val media: View = if (Clips.isVideo(file)) {
            val ringerNormal = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode == AudioManager.RINGER_MODE_NORMAL
            val volume = if (sound && ringerNormal) CLIP_VOLUME else 0f
            VideoView(ctx).apply {
                setVideoPath(file.path)
                setOnPreparedListener {
                    it.isLooping = true
                    it.setVolume(volume, volume)
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    visibility = View.GONE
                    true
                }
                // Don't steal audio focus (pausing music) when silent anyway.
                if (volume == 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                onVideo(this)
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

    /** A short message at the top of the screen that disappears by itself. */
    fun showJab(text: String) {
        jabView?.let(::remove)
        val ctx: Context = service
        val view = ctx.label(text, 17f, Palette.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = ctx.rounded(Palette.CARD, 20)
            setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(16))
        }
        val params = WindowManager.LayoutParams(
            WRAP_CONTENT,
            WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ctx.dp(72)
        }
        windowManager.addView(view, params)
        jabView = view
        handler.postDelayed({
            if (jabView === view) {
                remove(view)
                jabView = null
            }
        }, JAB_MS)
    }

    private fun hideKeyboard(view: View) {
        (service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun hideAll() {
        hidePrompt()
        hideBlock()
        jabView?.let(::remove)
        jabView = null
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
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
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
    private class StrictEditText(context: Context, numeric: Boolean = false) : EditText(context) {
        init {
            inputType = if (numeric) {
                InputType.TYPE_CLASS_NUMBER
            } else {
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
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
        const val PROMPT_CLIP_DP = 190
        const val PROMPT_CLIP_SMALL_DP = 110
        const val CLIP_VOLUME = 0.4f
        const val MAX_CHARS_PER_KEYSTROKE = 2
        const val JAB_MS = 3500L

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
