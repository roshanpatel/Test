package app.jolt

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Built-in celebration animations, drawn in code: no files, no internet, no licences. */
enum class Scene(val caption: String) {
    SUNRISE("Every day is a fresh start."),
    GROWTH("Small choices grow into big change."),
    CHAIN("You just broke the chain."),
    SUMMIT("One step closer to the top."),
}

/** Plays one [Scene] over about four seconds, then holds the final frame. */
class SceneView(context: Context, private val scene: Scene) : View(context) {
    private val startedAt = SystemClock.uptimeMillis()
    private val ease = DecelerateInterpolator(1.5f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val rect = RectF()

    /** 0..1 progress of the part of the animation running from [from] to [to] seconds. */
    private fun part(t: Float, from: Float, to: Float) = ease.getInterpolation(((t - from) / (to - from)).coerceIn(0f, 1f))

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        when (scene) {
            Scene.SUNRISE -> sunrise(canvas, t, w, h)
            Scene.GROWTH -> growth(canvas, t, w, h)
            Scene.CHAIN -> chain(canvas, t, w, h)
            Scene.SUMMIT -> summit(canvas, t, w, h)
        }
        if (t < DURATION_S) postInvalidateOnAnimation()
    }

    private fun sunrise(canvas: Canvas, t: Float, w: Float, h: Float) {
        val p = part(t, 0f, 3.5f)
        val horizon = h * 0.72f
        fill.shader = LinearGradient(0f, 0f, 0f, horizon, blend(0xFF0D1B3E.toInt(), 0xFF3A6EA5.toInt(), p), blend(0xFF22264B.toInt(), 0xFFFFB074.toInt(), p), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, horizon, fill)
        fill.shader = null
        val r = min(w, h) * 0.16f
        val cy = horizon + r - (horizon * 0.55f + r) * p
        val rays = part(t, 2.2f, 4f)
        if (rays > 0f) {
            stroke.color = Palette.ACCENT
            stroke.alpha = (200 * rays).toInt()
            stroke.strokeWidth = context.dp(3).toFloat()
            for (i in 0 until 12) {
                val a = (i * 30f + t * 6f) * (Math.PI / 180).toFloat()
                val inner = r * 1.25f
                val outer = r * (1.25f + 0.5f * rays)
                canvas.drawLine(w / 2 + cos(a) * inner, cy + sin(a) * inner, w / 2 + cos(a) * outer, cy + sin(a) * outer, stroke)
            }
        }
        fill.color = Palette.ACCENT
        canvas.drawCircle(w / 2, cy, r, fill)
        fill.color = 0xFF14202E.toInt()
        canvas.drawRect(0f, horizon, w, h, fill)
    }

    private fun growth(canvas: Canvas, t: Float, w: Float, h: Float) {
        val ground = h * 0.85f
        fill.color = 0xFF3E2C1C.toInt()
        canvas.drawRect(0f, ground, w, h, fill)
        val stem = part(t, 0f, 2.2f)
        val top = ground - (h * 0.6f) * stem
        stroke.color = Palette.GOOD
        stroke.strokeWidth = context.dp(5).toFloat()
        path.reset()
        path.moveTo(w / 2, ground)
        path.quadTo(w / 2 - w * 0.06f, (ground + top) / 2, w / 2, top)
        canvas.drawPath(path, stroke)
        val leaves = part(t, 1.0f, 2.6f)
        fill.color = Palette.GOOD
        if (leaves > 0f) {
            val ly = ground - h * 0.25f
            val lw = w * 0.11f * leaves
            val lh = h * 0.05f * leaves
            rect.set(w / 2, ly - lh, w / 2 + lw * 2, ly + lh)
            canvas.drawOval(rect, fill)
            rect.set(w / 2 - lw * 2, ly - lh - h * 0.08f, w / 2, ly + lh - h * 0.08f)
            canvas.drawOval(rect, fill)
        }
        val bloom = part(t, 2.2f, 3.8f)
        if (bloom > 0f) {
            val pr = min(w, h) * 0.07f * bloom
            for (i in 0 until 6) {
                val a = (i * 60f + 90f * bloom) * (Math.PI / 180).toFloat()
                fill.color = if (i % 2 == 0) Palette.PANIC else 0xFFF06292.toInt()
                canvas.drawCircle(w / 2 + cos(a) * pr * 1.2f, top + sin(a) * pr * 1.2f, pr, fill)
            }
            fill.color = Palette.ACCENT
            canvas.drawCircle(w / 2, top, pr * 0.8f, fill)
        }
    }

    private fun chain(canvas: Canvas, t: Float, w: Float, h: Float) {
        val linkW = w * 0.22f
        val linkH = h * 0.24f
        val cy = h * 0.45f
        val snap = part(t, 1.4f, 3.2f)
        stroke.color = 0xFFB0BEC5.toInt()
        stroke.strokeWidth = context.dp(8).toFloat()
        // Outer links drift apart and drop once the middle one snaps.
        for (side in listOf(-1, 1)) {
            val shift = side * w * 0.12f * snap
            val drop = h * 0.25f * snap * snap
            for (i in 1..2) {
                val cx = w / 2 + side * linkW * 0.8f * i + shift
                canvas.save()
                canvas.rotate(side * 25f * snap, cx, cy + drop)
                rect.set(cx - linkW / 2, cy - linkH / 2 + drop, cx + linkW / 2, cy + linkH / 2 + drop)
                canvas.drawRoundRect(rect, linkH / 2, linkH / 2, stroke)
                canvas.restore()
            }
        }
        // The middle link cracks into two halves.
        val gap = w * 0.08f * snap
        stroke.color = if (snap > 0f) Palette.ACCENT else 0xFFB0BEC5.toInt()
        for (side in listOf(-1, 1)) {
            canvas.save()
            canvas.translate(side * gap, h * 0.1f * snap * snap)
            canvas.rotate(side * 35f * snap, w / 2, cy)
            rect.set(w / 2 - linkW / 2, cy - linkH / 2, w / 2 + linkW / 2, cy + linkH / 2)
            canvas.drawArc(rect, if (side < 0) 100f else -80f, 160f, false, stroke)
            canvas.restore()
        }
        val flash = part(t, 1.4f, 1.9f)
        if (flash in 0.01f..0.99f) {
            fill.color = Palette.ACCENT
            fill.alpha = (255 * (1 - flash)).toInt()
            canvas.drawCircle(w / 2, cy, linkW * flash, fill)
            fill.alpha = 255
        }
    }

    private fun summit(canvas: Canvas, t: Float, w: Float, h: Float) {
        val base = h * 0.9f
        val peakX = w * 0.55f
        val peakY = h * 0.15f
        fill.color = 0xFF37474F.toInt()
        path.reset()
        path.moveTo(w * 0.05f, base)
        path.lineTo(peakX, peakY)
        path.lineTo(w * 0.95f, base)
        path.close()
        canvas.drawPath(path, fill)
        fill.color = 0xFFECEFF1.toInt()
        path.reset()
        path.moveTo(peakX - w * 0.1f, peakY + h * 0.14f)
        path.lineTo(peakX, peakY)
        path.lineTo(peakX + w * 0.08f, peakY + h * 0.14f)
        path.close()
        canvas.drawPath(path, fill)
        val climb = part(t, 0f, 3f)
        val x = w * 0.12f + (peakX - w * 0.12f) * climb
        val y = base - (base - peakY) * ((x - w * 0.05f) / (peakX - w * 0.05f))
        fill.color = Palette.ACCENT
        canvas.drawCircle(x, y - context.dp(7), context.dp(7).toFloat(), fill)
        val flag = part(t, 3f, 4f)
        if (flag > 0f) {
            stroke.color = Palette.TEXT
            stroke.strokeWidth = context.dp(3).toFloat()
            val poleTop = peakY - h * 0.12f * flag
            canvas.drawLine(peakX, peakY, peakX, poleTop, stroke)
            fill.color = Palette.PANIC
            path.reset()
            path.moveTo(peakX, poleTop)
            path.lineTo(peakX + w * 0.1f * flag, poleTop + h * 0.03f)
            path.lineTo(peakX, poleTop + h * 0.06f)
            path.close()
            canvas.drawPath(path, fill)
        }
    }

    private fun blend(from: Int, to: Int, p: Float): Int {
        fun ch(shift: Int) = (((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * p).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private companion object {
        const val DURATION_S = 4.5f
    }
}
