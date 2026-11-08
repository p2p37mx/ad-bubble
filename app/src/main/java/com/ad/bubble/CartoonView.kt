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

/**
 * شاشة كارتونية للأطفال: حيوان (كل إعلان حيوان مختلف) يتنطط ويمسك بالون بيه العد التنازلي.
 */
class CartoonView(c: Context) : View(c) {

    companion object {
        const val ANIMALS = 8
        val NAMES = arrayOf("الكتكوت", "البزونة", "الجرو", "الأرنب", "الباندا", "الضفدع", "الدبدوب", "الأسد")
    }

    /** الثواني الباقية (-1 = ما نعرف) */
    var seconds: Int = -1
        set(v) {
            if (v != field) popAt = SystemClock.uptimeMillis()
            field = v
        }
    var exact = false
    var animal = 0
    /** true = نعد من 1 لـ 10 ويتكرر (حتى الطفل يتعلم الأرقام) */
    var countUp = true
    private var lastCount = 0
    private val WORDS = arrayOf("واحد", "اثنين", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة", "عشرة")
    private val AR_DIGITS = arrayOf("١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "١٠")

    private val start = SystemClock.uptimeMillis()
    private var popAt = 0L
    private var pokeAt = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private val d = resources.displayMetrics.density
    private val balloonColors = intArrayOf(
        0xFFEF4444.toInt(), 0xFFF97316.toInt(), 0xFF22C55E.toInt(),
        0xFF3B82F6.toInt(), 0xFFA855F7.toInt(), 0xFFEC4899.toInt()
    )
    private val skyTop = intArrayOf(
        0xFF7DD3FC.toInt(), 0xFFFBCFE8.toInt(), 0xFFA7F3D0.toInt(), 0xFFFDE68A.toInt(),
        0xFFBFDBFE.toInt(), 0xFFBBF7D0.toInt(), 0xFFFED7AA.toInt(), 0xFFFEF08A.toInt()
    )

    /** لما الطفل يدوس ✕: الحيوان ينطّ ويگول "استنى شوية" */
    fun poke() {
        pokeAt = SystemClock.uptimeMillis()
    }

    override fun onDraw(cv: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - start) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        val a = ((animal % ANIMALS) + ANIMALS) % ANIMALS

        // السما
        p.shader = LinearGradient(0f, 0f, 0f, h, skyTop[a], 0xFFE9D5FF.toInt(), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null

        for (i in 0 until 14) {
            val sx = ((i * 137) % 100) / 100f * w
            val sy = ((i * 61) % 45) / 100f * h + 40 * d
            val s = (5 + (i % 3) * 2) * d * (0.6f + 0.4f * sin(t * 3 + i).toFloat())
            star(cv, sx, sy, s, 0xFFFDE047.toInt())
        }
        for (i in 0 until 3) {
            val speed = (14 + i * 8) * d
            val cx = ((i * w / 3f + t * speed) % (w + 160 * d)) - 80 * d
            cloud(cv, cx, h * (0.12f + i * 0.09f), (26 + i * 6) * d)
        }

        // التلة
        p.color = 0xFF86EFAC.toInt()
        oval.set(-w * 0.3f, h * 0.82f, w * 1.3f, h * 1.4f); cv.drawOval(oval, p)
        p.color = 0xFF4ADE80.toInt()
        oval.set(-w * 0.2f, h * 0.88f, w * 1.2f, h * 1.5f); cv.drawOval(oval, p)

        // الحيوان
        val r = min(w, h) * 0.16f
        val cx = w / 2f
        val poked = now - pokeAt < 1600
        val jump = if (poked) 2.2f else 1f
        val bounce = abs(sin(t * (if (poked) 6.0 else 3.2))).toFloat()
        val baseY = h * 0.66f
        val cy = baseY - bounce * 22 * d * jump
        p.color = 0x33000000
        val sh = r * (1f - bounce * 0.3f)
        oval.set(cx - sh, baseY + r * 0.95f, cx + sh, baseY + r * 1.15f); cv.drawOval(oval, p)

        // خيط + بالون
        val bx = cx + r * 0.95f + sin(t * 1.6).toFloat() * 8 * d
        val by = cy - r * 2.35f
        val br = r * 0.85f
        p.color = 0xFF475569.toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        path.reset()
        path.moveTo(cx + r * 0.95f, cy - r * 0.1f)
        path.quadTo(cx + r * 1.4f, cy - r * 0.9f, bx, by + br)
        cv.drawPath(path, p)
        p.style = Paint.Style.FILL

        drawAnimal(cv, a, cx, cy, r, t)

        val sec = seconds
        val count = ((now - start) / 1000L % 10L).toInt() + 1
        if (countUp && count != lastCount) { lastCount = count; popAt = now }
        val ci = if (countUp) count % balloonColors.size
            else if (sec >= 0) sec % balloonColors.size else ((t / 1.5f).toInt() % balloonColors.size)
        p.color = balloonColors[ci]
        oval.set(bx - br, by - br * 1.15f, bx + br, by + br); cv.drawOval(oval, p)
        p.color = 0x55FFFFFF
        oval.set(bx - br * 0.6f, by - br * 0.85f, bx - br * 0.2f, by - br * 0.3f); cv.drawOval(oval, p)
        p.color = balloonColors[ci]
        path.reset()
        path.moveTo(bx - br * 0.12f, by + br * 1.12f)
        path.lineTo(bx + br * 0.12f, by + br * 1.12f)
        path.lineTo(bx, by + br * 0.95f)
        path.close()
        cv.drawPath(path, p)

        val pop = 1f + 0.35f * max(0f, 1f - (now - popAt) / 300f)
        val label = when {
            countUp -> count.toString()
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

        // فقاعة كلام لما يدوس ✕
        if (poked) {
            val tx = cx - r * 1.2f
            val tyb = cy - r * 1.55f
            p.textSize = 18 * d
            val msg = "استنى شوية! ⏳"
            val tw = p.measureText(msg)
            p.color = Color.WHITE
            oval.set(tx - tw / 2 - 14 * d, tyb - 26 * d, tx + tw / 2 + 14 * d, tyb + 12 * d)
            cv.drawRoundRect(oval, 18 * d, 18 * d, p)
            p.color = 0xFF1E293B.toInt()
            cv.drawText(msg, tx, tyb, p)
        }

        // الكتابة
        p.textSize = 22 * d
        p.color = 0xFF1E3A8A.toInt()
        cv.drawText("الإعلان مخفي 🙈", w / 2, h * 0.30f, p)
        p.textSize = 17 * d
        p.color = 0xFF4C1D95.toInt()
        val msg = when {
            countUp -> "نعد ويا " + NAMES[a] + ": " + AR_DIGITS[count - 1] + " " + WORDS[count - 1]
            sec == 0 -> "شوية ونرجع للعبة… 🎉"
            sec > 0 && exact -> NAMES[a] + " ينتظر وياك $sec ثانية"
            else -> NAMES[a] + " ينتظر وياك شوية…"
        }
        cv.drawText(msg, w / 2, h * 0.30f + 30 * d, p)
        p.typeface = Typeface.DEFAULT
        p.strokeCap = Paint.Cap.BUTT

        postInvalidateOnAnimation()
    }

    // ---------------- رسم الحيوانات ----------------
    private fun drawAnimal(cv: Canvas, a: Int, cx: Float, cy: Float, r: Float, t: Float) {
        val body: Int
        val belly: Int
        when (a) {
            0 -> { body = 0xFFFDE047.toInt(); belly = 0xFFFEF9C3.toInt() }  // كتكوت
            1 -> { body = 0xFFFB923C.toInt(); belly = 0xFFFFEDD5.toInt() }  // بزونة
            2 -> { body = 0xFFC08457.toInt(); belly = 0xFFF5E1C8.toInt() }  // جرو
            3 -> { body = 0xFFF8FAFC.toInt(); belly = 0xFFFCE7F3.toInt() }  // أرنب
            4 -> { body = 0xFFFFFFFF.toInt(); belly = 0xFFF1F5F9.toInt() }  // باندا
            5 -> { body = 0xFF4ADE80.toInt(); belly = 0xFFD9F99D.toInt() }  // ضفدع
            6 -> { body = 0xFFA16207.toInt(); belly = 0xFFE7C9A0.toInt() }  // دبدوب
            else -> { body = 0xFFFBBF24.toInt(); belly = 0xFFFEF3C7.toInt() } // أسد
        }
        val dark = 0xFF1E293B.toInt()

        // لبدة الأسد خلف الجسم
        if (a == 7) {
            p.color = 0xFFEA580C.toInt()
            for (k in 0 until 12) {
                val ang = k * PI / 6 + t * 0.3
                cv.drawCircle(cx + (cos(ang) * r * 0.95).toFloat(), cy + (sin(ang) * r * 0.95).toFloat(), r * 0.38f, p)
            }
        }

        // الأذان
        when (a) {
            1 -> { // بزونة: مثلثات
                for (sgn in intArrayOf(-1, 1)) {
                    p.color = body
                    path.reset()
                    path.moveTo(cx + sgn * r * 0.75f, cy - r * 0.45f)
                    path.lineTo(cx + sgn * r * 0.75f, cy - r * 1.25f)
                    path.lineTo(cx + sgn * r * 0.2f, cy - r * 0.85f)
                    path.close(); cv.drawPath(path, p)
                    p.color = 0xFFFDA4AF.toInt()
                    path.reset()
                    path.moveTo(cx + sgn * r * 0.68f, cy - r * 0.6f)
                    path.lineTo(cx + sgn * r * 0.68f, cy - r * 1.05f)
                    path.lineTo(cx + sgn * r * 0.35f, cy - r * 0.82f)
                    path.close(); cv.drawPath(path, p)
                }
            }
            3 -> { // أرنب: أذان طويلة تتمايل
                val sway = sin(t * 2.5).toFloat() * 8f
                for (sgn in intArrayOf(-1, 1)) {
                    cv.save(); cv.rotate(sgn * (12f + sway), cx + sgn * r * 0.35f, cy - r * 0.8f)
                    p.color = body
                    oval.set(cx + sgn * r * 0.35f - r * 0.2f, cy - r * 2.0f, cx + sgn * r * 0.35f + r * 0.2f, cy - r * 0.6f)
                    cv.drawOval(oval, p)
                    p.color = 0xFFFBCFE8.toInt()
                    oval.set(cx + sgn * r * 0.35f - r * 0.1f, cy - r * 1.85f, cx + sgn * r * 0.35f + r * 0.1f, cy - r * 0.75f)
                    cv.drawOval(oval, p)
                    cv.restore()
                }
            }
            4, 6, 7 -> { // دوائر (باندا سودة، دب وأسد بني)
                for (sgn in intArrayOf(-1, 1)) {
                    p.color = if (a == 4) dark else body
                    cv.drawCircle(cx + sgn * r * 0.72f, cy - r * 0.72f, r * 0.3f, p)
                    if (a != 4) {
                        p.color = belly
                        cv.drawCircle(cx + sgn * r * 0.72f, cy - r * 0.72f, r * 0.16f, p)
                    }
                }
            }
            5 -> { // ضفدع: عيون فوگ الراس
                for (sgn in intArrayOf(-1, 1)) {
                    p.color = body
                    cv.drawCircle(cx + sgn * r * 0.45f, cy - r * 0.85f, r * 0.32f, p)
                }
            }
        }

        // الإيدين / الجناحات
        val flap = sin(t * 9).toFloat() * 22f
        p.color = if (a == 4) dark else body
        cv.save(); cv.rotate(-20 - flap, cx - r * 0.9f, cy)
        oval.set(cx - r * 1.35f, cy - r * 0.2f, cx - r * 0.6f, cy + r * 0.32f); cv.drawOval(oval, p)
        cv.restore()
        cv.save(); cv.rotate(20 + flap, cx + r * 0.9f, cy)
        oval.set(cx + r * 0.6f, cy - r * 0.2f, cx + r * 1.35f, cy + r * 0.32f); cv.drawOval(oval, p)
        cv.restore()

        // الرجول
        p.color = when (a) { 0 -> 0xFFF97316.toInt(); 4 -> dark; else -> body }
        oval.set(cx - r * 0.55f, cy + r * 0.82f, cx - r * 0.15f, cy + r * 1.1f); cv.drawOval(oval, p)
        oval.set(cx + r * 0.15f, cy + r * 0.82f, cx + r * 0.55f, cy + r * 1.1f); cv.drawOval(oval, p)

        // ذيل الجرو يهز
        if (a == 2) {
            p.color = body
            cv.save(); cv.rotate(sin(t * 12).toFloat() * 25f, cx + r * 0.8f, cy + r * 0.5f)
            oval.set(cx + r * 0.75f, cy + r * 0.25f, cx + r * 1.25f, cy + r * 0.5f); cv.drawOval(oval, p)
            cv.restore()
        }

        // الجسم
        p.color = body
        cv.drawCircle(cx, cy, r, p)
        if (a == 3 || a == 4) { // حافة خفيفة للأبيض
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = 0x33000000
            cv.drawCircle(cx, cy, r, p); p.style = Paint.Style.FILL
        }
        p.color = belly
        oval.set(cx - r * 0.55f, cy + r * 0.05f, cx + r * 0.55f, cy + r * 0.85f); cv.drawOval(oval, p)

        // أذان الجرو المتدلية (فوگ الجسم)
        if (a == 2) {
            p.color = 0xFF7C4A21.toInt()
            for (sgn in intArrayOf(-1, 1)) {
                cv.save(); cv.rotate(sgn * (-15f + sin(t * 3).toFloat() * 6f), cx + sgn * r * 0.75f, cy - r * 0.6f)
                oval.set(cx + sgn * r * 0.75f - r * 0.22f, cy - r * 0.75f, cx + sgn * r * 0.75f + r * 0.22f, cy + r * 0.05f)
                cv.drawOval(oval, p)
                cv.restore()
            }
        }

        // خصلة الكتكوت
        if (a == 0) {
            p.color = 0xFFFACC15.toInt()
            p.style = Paint.Style.STROKE; p.strokeWidth = 4 * d; p.strokeCap = Paint.Cap.ROUND
            for (k in -1..1) {
                path.reset()
                path.moveTo(cx + k * r * 0.12f, cy - r * 0.95f)
                path.quadTo(cx + k * r * 0.3f, cy - r * 1.35f, cx + k * r * 0.25f + r * 0.1f, cy - r * 1.25f)
                cv.drawPath(path, p)
            }
            p.style = Paint.Style.FILL
        }

        // بقع عيون الباندا
        if (a == 4) {
            p.color = dark
            for (sgn in intArrayOf(-1, 1)) {
                cv.save(); cv.rotate(sgn * -25f, cx + sgn * r * 0.36f, cy - r * 0.18f)
                oval.set(cx + sgn * r * 0.36f - r * 0.24f, cy - r * 0.38f, cx + sgn * r * 0.36f + r * 0.24f, cy + r * 0.02f)
                cv.drawOval(oval, p); cv.restore()
            }
        }

        // العيون
        val blink = (t % 3.4f) < 0.14f
        val ex = r * 0.36f
        val ey = if (a == 5) cy - r * 0.85f else cy - r * 0.2f
        val ew = if (a == 5) r * 0.24f else r * 0.2f
        for (sgn in intArrayOf(-1, 1)) {
            val x = cx + sgn * (if (a == 5) r * 0.45f else ex)
            if (blink) {
                p.color = Color.BLACK; p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d
                cv.drawLine(x - ew * 0.7f, ey, x + ew * 0.7f, ey, p)
                p.style = Paint.Style.FILL
            } else {
                p.color = Color.WHITE
                cv.drawCircle(x, ey, ew, p)
                p.color = dark
                val look = sin(t * 0.8).toFloat() * r * 0.05f
                cv.drawCircle(x + look, ey + r * 0.03f, ew * 0.6f, p)
                p.color = Color.WHITE
                cv.drawCircle(x + look + r * 0.04f, ey - r * 0.03f, ew * 0.2f, p)
            }
        }

        // خدود
        p.color = 0x66F472B6
        cv.drawCircle(cx - r * 0.6f, cy + r * 0.12f, r * 0.13f, p)
        cv.drawCircle(cx + r * 0.6f, cy + r * 0.12f, r * 0.13f, p)

        // الوجه (منقار / خشم / بوز)
        when (a) {
            0 -> {
                p.color = 0xFFF97316.toInt()
                path.reset()
                path.moveTo(cx - r * 0.14f, cy + r * 0.02f)
                path.lineTo(cx + r * 0.14f, cy + r * 0.02f)
                path.lineTo(cx, cy + r * 0.22f)
                path.close(); cv.drawPath(path, p)
            }
            2, 6, 7 -> {
                p.color = belly
                oval.set(cx - r * 0.3f, cy - r * 0.02f, cx + r * 0.3f, cy + r * 0.38f); cv.drawOval(oval, p)
                p.color = dark
                oval.set(cx - r * 0.1f, cy + r * 0.02f, cx + r * 0.1f, cy + r * 0.14f); cv.drawOval(oval, p)
                smile(cv, cx, cy + r * 0.2f, r * 0.12f)
            }
            else -> {
                p.color = if (a == 1 || a == 3) 0xFFF472B6.toInt() else dark
                if (a != 5) cv.drawCircle(cx, cy + r * 0.05f, r * 0.07f, p)
                smile(cv, cx, cy + r * (if (a == 5) 0.0f else 0.14f), r * (if (a == 5) 0.35f else 0.12f))
            }
        }

        // شوارب البزونة
        if (a == 1) {
            p.color = 0xFF78350F.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d
            for (sgn in intArrayOf(-1, 1)) for (k in -1..1) {
                cv.drawLine(cx + sgn * r * 0.2f, cy + r * 0.1f, cx + sgn * r * 0.75f, cy + r * (0.05f + k * 0.1f), p)
            }
            p.style = Paint.Style.FILL
        }
    }

    private fun smile(cv: Canvas, x: Float, y: Float, s: Float) {
        p.color = 0xFF1E293B.toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f * d
        p.strokeCap = Paint.Cap.ROUND
        oval.set(x - s, y - s, x + s, y + s)
        cv.drawArc(oval, 20f, 140f, false, p)
        p.style = Paint.Style.FILL
    }

    private fun star(cv: Canvas, x: Float, y: Float, s: Float, color: Int) {
        path.reset()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) s else s * 0.45f
            val ang = -PI / 2 + i * PI / 5
            val px = x + (cos(ang) * rad).toFloat()
            val py = y + (sin(ang) * rad).toFloat()
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
