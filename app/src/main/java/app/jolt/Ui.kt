package app.jolt

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import kotlin.math.roundToInt

// Small helpers for building views in code (the app has no layout XML or AndroidX).

object Palette {
    const val BACKGROUND = 0xFF101014.toInt()
    const val CARD = 0xFF1E1E26.toInt()
    const val TEXT = 0xFFF2F2F5.toInt()
    const val MUTED = 0xFFA0A0AC.toInt()
    const val ACCENT = 0xFFFFB020.toInt()
    const val PANIC = 0xFFE53935.toInt()
    const val GOOD = 0xFF43A047.toInt()
    const val NEUTRAL = 0xFF3A3A46.toInt()
    const val SCRIM = 0xF2000000.toInt()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

fun Context.label(text: String, sizeSp: Float = 16f, color: Int = Palette.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

fun Context.rounded(color: Int, radiusDp: Int = 16): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

fun Context.pillButton(text: String, background: Int, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(Palette.TEXT)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        stateListAnimator = null
        this.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            rounded(background, 28),
            null,
        )
        minHeight = dp(52)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        setOnClickListener { onClick() }
    }
