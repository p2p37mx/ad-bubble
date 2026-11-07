package com.ad.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** شاشة كارتونية للأطفال: صوص أصفر يتنطط ويمسك بالون بيه العد التنازلي */
class CartoonView(c: Context) : View(c) {

    /** الثواني الباقية (-1 = ما نعرف) */
    var seconds: Int = -1
        set(v) {
            if (v != field) popAt = SystemClock.uptimeMillis()
            field = v
        }
    /** true إذا الرقم مقروء من الإعلان نفسه */
    var exact = false
    var hint: String = ""

    private val start = SystemClock.uptimeMillis()
    private var popAt = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private val d = resources.displayMetrics.density
    private val balloonColors = intArrayOf(
        0xFFEF4444.toInt(), 0xFFF97316.toInt(), 0xFF22C55E.toInt(),
        0xFF3B82F6.toInt(), 0xFFA855F7.toInt(), 0xFFEC4899.toInt()
    )

    override fun onDraw(cv: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - start) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()

        // السما
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xFF7DD3FC.toInt(), 0xFFE9D5FF.toInt(), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // نجوم تلمع
        for (i in 0 until 14) {
            val sx = ((i * 137) % 100) / 100f * w
            val sy = ((i * 61) % 45) / 100f * h + 40 * d
            val s = (5 + (i % 3) * 2) * d * (0.6f + 0.4f * sin(t * 3 + i).toFloat())
            star(cv, sx, sy, s, 0xFFFDE047.toInt())
        }

        // غيوم تمشي
        for (i in 0 until 3) {
            val speed = (14 + i * 8) * d
            val cx = ((i * w / 3f + t * speed) % (w + 160 * d)) - 80 * d
            val cy = h * (0.12f + i * 0.09f)
            cloud(cv, cx, cy, (26 + i * 6) * d)
        }

        // التلة الخضرة
        p.color = 0xFF86EFAC.toInt()
        oval.set(-w * 0.3f, h * 0.82f, w * 1.3f, h * 1.4f)
        cv.drawOval(oval, p)
        p.color = 0xFF4ADE80.toInt()
        oval.set(-w * 0.2f, h * 0.88f, w * 1.2f, h * 1.5f)
        cv.drawOval(oval, p)

        // الصوص
        val r = min(w, h) * 0.16f
        val cx = w / 2f
        val bounce = abs(sin(t * 3.2)).toFloat()
        val cy = h * 0.66f - bounce * 22 * d
        // ظل
        p.color = 0x33000000
        val sh = r * (1f - bounce * 0.3f)
        oval.set(cx - sh, h * 0.66f + r * 0.95f, cx + sh, h * 0.66f + r * 1.15f)
        cv.drawOval(oval, p)

        // خيط البالون
        val bx = cx + r * 0.9f + sin(t * 1.6).toFloat() * 8 * d
        val by = cy - r * 2.3f
        val br = r * 0.85f
        p.color = 0xFF475569.toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        path.reset()
        path.moveTo(cx + r * 0.95f, cy - r * 0.1f)
        path.quadTo(cx + r * 1.4f, cy - r * 0.9f, bx, by + br)
        cv.drawPath(path, p)
        p.style = Paint.Style.FILL

        // جناحات ترفرف
        val flap = sin(t * 9).toFloat() * 22f
        p.color = 0xFFFACC15.toInt()
        cv.save(); cv.rotate(-20 - flap, cx - r * 0.9f, cy)
        oval.set(cx - r * 1.35f, cy - r * 0.25f, cx - r * 0.6f, cy + r * 0.35f); cv.drawOval(oval, p)
        cv.restore()
        cv.save(); cv.rotate(20 + flap, cx + r * 0.9f, cy)
        oval.set(cx + r * 0.6f, cy - r * 0.25f, cx + r * 1.35f, cy + r * 0.35f); cv.drawOval(oval, p)
        cv.restore()

        // رجول
        p.color = 0xFFF97316.toInt()
        oval.set(cx - r * 0.55f, cy + r * 0.85f, cx - r * 0.15f, cy + r * 1.1f); cv.drawOval(oval, p)
        oval.set(cx + r * 0.15f, cy + r * 0.85f, cx + r * 0.55f, cy + r * 1.1f); cv.drawOval(oval, p)

        // الجسم
        p.color = 0xFFFDE047.toInt()
        cv.drawCircle(cx, cy, r, p)
        p.color = 0xFFFEF9C3.toInt()
        oval.set(cx - r * 0.55f, cy + r * 0.05f, cx + r * 0.55f, cy + r * 0.85f); cv.drawOval(oval, p)

        // خصلة شعر
        p.color = 0xFFFACC15.toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4 * d
        p.strokeCap = Paint.Cap.ROUND
        for (k in -1..1) {
            path.reset()
            path.moveTo(cx + k * r * 0.12f, cy - r * 0.95f)
            path.quadTo(cx + k * r * 0.3f, cy - r * 1.35f, cx + k * r * 0.25f + r * 0.1f, cy - r * 1.25f)
            cv.drawPath(path, p)
        }
        p.style = Paint.Style.FILL

        // عيون ترمش
        val blink = (t % 3.4f) < 0.14f
        val ex = r * 0.36f
        val ey = cy - r * 0.2f
        for (sgn in intArrayOf(-1, 1)) {
            val x = cx + sgn * ex
            if (blink) {
                p.color = Color.BLACK
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3 * d
                cv.drawLine(x - r * 0.14f, ey, x + r * 0.14f, ey, p)
                p.style = Paint.Style.FILL
            } else {
                p.color = Color.WHITE
                cv.drawCircle(x, ey, r * 0.2f, p)
                p.color = 0xFF1E293B.toInt()
                val look = sin(t * 0.8).toFloat() * r * 0.05f
                cv.drawCircle(x + look, ey + r * 0.03f, r * 0.12f, p)
                p.color = Color.WHITE
                cv.drawCircle(x + look + r * 0.04f, ey - r * 0.03f, r * 0.04f, p)
            }
        }
        // خدود
        p.color = 0x66F472B6
        cv.drawCircle(cx - r * 0.58f, cy + r * 0.12f, r * 0.13f, p)
        cv.drawCircle(cx + r * 0.58f, cy + r * 0.12f, r * 0.13f, p)
        // منقار
        p.color = 0xFFF97316.toInt()
        path.reset()
        path.moveTo(cx - r * 0.14f, cy + r * 0.02f)
        path.lineTo(cx + r * 0.14f, cy + r * 0.02f)
        path.lineTo(cx, cy + r * 0.22f)
        path.close()
        cv.drawPath(path, p)

        // البالون والرقم
        val sec = seconds
        val ci = if (sec >= 0) sec % balloonColors.size else ((t / 1.5f).toInt() % balloonColors.size)
        p.color = balloonColors[ci]
        oval.set(bx - br, by - br * 1.15f, bx + br, by + br)
        cv.drawOval(oval, p)
        p.color = 0x55FFFFFF
        oval.set(bx - br * 0.6f, by - br * 0.85f, bx - br * 0.2f, by - br * 0.3f)
        cv.drawOval(oval, p)
        p.color = balloonColors[ci]
        path.reset()
        path.moveTo(bx - br * 0.12f, by + br * 1.12f)
        path.lineTo(bx + br * 0.12f, by + br * 1.12f)
        path.lineTo(bx, by + br * 0.95f)
        path.close()
        cv.drawPath(path, p)

        val pop = 1f + 0.35f * max(0f, 1f - (now - popAt) / 300f)
        val label = when {
            sec > 0 -> sec.toString()
            sec == 0 -> "👍"
            else -> "⏳"
        }
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        p.textSize = br * 1.1f * pop
        val ty = by - br * 0.08f - (p.descent() + p.ascent()) / 2
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5 * d
        p.color = 0x99000000.toInt()
        cv.drawText(label, bx, ty, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        cv.drawText(label, bx, ty, p)

        // الكتابة
        p.textSize = 22 * d
        p.color = 0xFF1E3A8A.toInt()
        cv.drawText("الإعلان مخفي 🙈", w / 2, h * 0.30f, p)
        p.textSize = 17 * d
        p.color = 0xFF4C1D95.toInt()
        val msg = when {
            sec == 0 -> "يلا نرجع للعبة! 🎉"
            sec > 0 && exact -> "نرجع للعبة بعد $sec ثانية"
            sec > 0 -> "نرجع للعبة بعد شوية…"
            else -> "نرجع للعبة بعد شوية…"
        }
        cv.drawText(msg, w / 2, h * 0.30f + 30 * d, p)
        if (hint.isNotEmpty()) {
            p.textSize = 13 * d
            p.color = 0xFF14532D.toInt()
            cv.drawText(hint, w / 2, h - 28 * d, p)
        }
        p.typeface = Typeface.DEFAULT
        p.strokeCap = Paint.Cap.BUTT

        postInvalidateOnAnimation()
    }

    private fun star(cv: Canvas, x: Float, y: Float, s: Float, color: Int) {
        path.reset()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) s else s * 0.45f
            val a = -PI / 2 + i * PI / 5
            val px = x + (cos(a) * rad).toFloat()
            val py = y + (sin(a) * rad).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        p.color = color
        cv.drawPath(path, p)
    }

    private fun cloud(cv: Canvas, x: Float, y: Float, s: Float) {
        p.color = 0xEEFFFFFF.toInt()
        cv.drawCircle(x, y, s, p)
        cv.drawCircle(x + s * 0.9f, y + s * 0.15f, s * 0.8f, p)
        cv.drawCircle(x - s * 0.9f, y + s * 0.2f, s * 0.7f, p)
        oval.set(x - s * 1.5f, y, x + s * 1.6f, y + s * 0.8f)
        cv.drawOval(oval, p)
    }
}
