package com.ad.bubble

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * إذا طلع إعلان شاشة كاملة (رغم الحجب) يغطيه بشاشة سودة/بيضة مع ✕
 * ويحاول يدوس زر الإغلاق/التخطي بنفسه أول ما يطلع.
 */
class AdCloserService : AccessibilityService() {

    companion object {
        @Volatile
        var connected = false
        val coveredCount = java.util.concurrent.atomic.AtomicInteger(0)
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var cover: FrameLayout? = null
    private var coverParams: WindowManager.LayoutParams? = null
    private var adPkg: String? = null
    private var shownAt = 0L
    private var lastBack = 0L
    private var lastClick = 0L
    private var muted = false
    private val suppressUntil = HashMap<String, Long>()
    private val activityCache = HashMap<String, Boolean>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        connected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        hideCover()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        hideCover()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        // يوتيوب: تخطي الإعلان تلقائياً + كتم صوته
        if (pkg in YT_PKGS) {
            if (Prefs.ytSkip(this)) ytCheck(pkg)
        } else if (ytMuted && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            setMute(false)
            ytMuted = false
        }

        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val cls = e.className?.toString() ?: return
        if (!Prefs.closer(this)) {
            hideCover()
            return
        }
        if (Blocklist.isAdActivity(cls)) {
            val until = suppressUntil[pkg] ?: 0L
            if (pkg !in Prefs.excluded(this) && SystemClock.uptimeMillis() > until) showCover(pkg)
        } else if (cover != null && isActivity(pkg, cls)) {
            hideCover()
        }
    }

    // ---------------- يوتيوب ----------------
    private val YT_PKGS = setOf("com.google.android.youtube", "com.google.android.apps.youtube.music")
    private val ytSkipTexts = setOf(
        "skip", "skip ad", "skip ads", "skip ad ›", "تخطي", "تخطّي", "تخطي الإعلان", "تخطي الإعلانات",
        "تخطّي الإعلان", "تخطّي الإعلانات", "تجاوز", "تجاوز الإعلان"
    )
    private var lastYt = 0L
    private var lastYtClick = 0L
    private var ytMuted = false

    private fun ytCheck(pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastYt < 400) return
        lastYt = now
        val root = findRoot(pkg) ?: return
        var skip: AccessibilityNodeInfo? = null
        var adShown = false
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen < 1500) {
            val n = q.removeFirst()
            seen++
            val id = (n.viewIdResourceName ?: "").substringAfter(":id/").lowercase()
            val t = (n.text?.toString() ?: "").trim().lowercase()
            val d = (n.contentDescription?.toString() ?: "").trim().lowercase()
            // علامات خاصة بمشغّل الفيديو فقط (حتى ما ينكتم الصوت بسبب إعلانات الصفحة الرئيسية)
            if (n.isVisibleToUser && (id.contains("ad_progress") || id.contains("ad_countdown") ||
                id.contains("skip_ad") || id.contains("player_learn_more") || id.contains("ad_player_overlay") ||
                id.contains("ad_text_overlay"))
            ) adShown = true
            if (skip == null && n.isVisibleToUser &&
                (id.contains("skip_ad_button") || id == "skip_button" || id.contains("modern_skip_ad") ||
                    t in ytSkipTexts || d in ytSkipTexts)
            ) skip = n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        if (skip != null && now - lastYtClick > 1000) {
            lastYtClick = now
            clickNode(skip)
        }
        if (Prefs.mute(this)) {
            if (adShown && !ytMuted) { setMute(true); ytMuted = true }
            else if (!adShown && ytMuted) { setMute(false); ytMuted = false }
        }
    }

    private fun setMute(on: Boolean) {
        try {
            getSystemService(AudioManager::class.java).adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0
            )
        } catch (_: Exception) {
        }
    }

    private fun isActivity(pkg: String, cls: String): Boolean {
        val key = "$pkg/$cls"
        activityCache[key]?.let { return it }
        val r = try {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (_: Exception) {
            false
        }
        activityCache[key] = r
        return r
    }

    // ---------------- الغطاء ----------------
    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    @SuppressLint("SetTextI18n")
    private fun showCover(pkg: String) {
        adPkg = pkg
        shownAt = SystemClock.uptimeMillis()
        lastBack = 0L
        if (cover != null) return
        coveredCount.incrementAndGet()

        val white = Prefs.whiteCover(this)
        val bgc = if (white) Color.WHITE else Color.BLACK
        val fg = if (white) Color.DKGRAY else Color.LTGRAY

        val root = FrameLayout(this).apply { setBackgroundColor(bgc) }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        center.addView(TextView(this).apply {
            text = "🚫"
            textSize = 54f
            gravity = Gravity.CENTER
        })
        center.addView(TextView(this).apply {
            text = "تم حجب إعلان\nجاري الإغلاق تلقائياً…"
            textSize = 18f
            setTextColor(fg)
            gravity = Gravity.CENTER
        })
        root.addView(center, FrameLayout.LayoutParams(-1, -1))

        val close = TextView(this).apply {
            text = "✕"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(if (white) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (white) 0x22000000 else 0x44FFFFFF)
            }
            setOnClickListener { pressBack() }
            setOnLongClickListener {
                // يخلي المستخدم يشوف الإعلان (مثلاً حتى ياخذ مكافأة)
                adPkg?.let { p -> suppressUntil[p] = SystemClock.uptimeMillis() + 60_000 }
                hideCover()
                true
            }
        }
        val s = dp(64f)
        root.addView(close, FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(40f)
            marginEnd = dp(16f)
        })

        root.addView(TextView(this).apply {
            text = "دوس ✕ للرجوع للعبة • ضغطة مطوّلة على ✕ تخلي الإعلان يبين (للمكافأة)"
            textSize = 12f
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(dp(16f), 0, dp(16f), dp(32f))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // يمنع الضغط يوصل للإعلان تحت
        root.isClickable = true

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(root, lp)
            cover = root
            coverParams = lp
        } catch (_: Exception) {
            return
        }

        if (Prefs.mute(this)) {
            try {
                getSystemService(AudioManager::class.java)
                    .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                muted = true
            } catch (_: Exception) {
            }
        }
        h.removeCallbacks(tick)
        h.postDelayed(tick, 400)
    }

    private fun hideCover() {
        h.removeCallbacks(tick)
        cover?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        cover = null
        coverParams = null
        adPkg = null
        if (muted) {
            muted = false
            try {
                getSystemService(AudioManager::class.java)
                    .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {
            }
        }
    }

    private fun pressBack() {
        lastBack = SystemClock.uptimeMillis()
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ---------------- محاولة الإغلاق التلقائي ----------------
    private val tick = object : Runnable {
        override fun run() {
            if (cover == null) return
            val now = SystemClock.uptimeMillis()
            if (now - shownAt > 90_000) {
                hideCover()
                return
            }
            val root = findRoot(adPkg)
            val node = root?.let { findClose(it) }
            if (node != null) {
                if (now - lastClick > 1200) {
                    lastClick = now
                    clickNode(node)
                }
            } else if (Prefs.autoBack(this@AdCloserService) && now - shownAt > 1500 && now - lastBack > 2500) {
                pressBack()
            }
            h.postDelayed(this, 600)
        }
    }

    private fun findRoot(pkg: String?): AccessibilityNodeInfo? {
        if (pkg == null) return null
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() == pkg) return r
            }
        } catch (_: Exception) {
        }
        val r = rootInActiveWindow
        return if (r?.packageName?.toString() == pkg) r else null
    }

    private val closeWords = listOf("close", "skip", "dismiss", "إغلاق", "اغلاق", "أغلق", "اغلق", "تخطي", "تخطّي", "تخطى")
    private val badWords = listOf("install", "download", "تثبيت", "تحميل", "open", "فتح", "learn", "more")
    private val xMarks = setOf("x", "✕", "✖", "×", "╳", "⨯", "✗")

    private fun isClose(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val t = (n.text?.toString() ?: "").trim().lowercase()
        val d = (n.contentDescription?.toString() ?: "").trim().lowercase()
        val id = (n.viewIdResourceName ?: "").lowercase()
        for (s in listOf(t, d)) {
            if (s.isEmpty() || s.length > 24) continue
            if (s in xMarks) return true
            if (badWords.any { s.contains(it) }) continue
            if (closeWords.any { s.contains(it) }) return true
        }
        val idTail = id.substringAfter(":id/", id)
        if (idTail.isNotEmpty() && (idTail.contains("close") || idTail.contains("skip"))) return true
        return false
    }

    private fun findClose(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen < 500) {
            val n = q.removeFirst()
            seen++
            if (isClose(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 5) {
            if (n.isClickable && n.isEnabled) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            n = n.parent
            hops++
        }
        // ما ينضغط عادي → نسوي لمسة على مكان الزر (نخلي الغطاء ما يمسك اللمسة لحظة)
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return
        tapThroughCover(r.exactCenterX(), r.exactCenterY())
    }

    private fun tapThroughCover(x: Float, y: Float) {
        val c = cover
        val lp = coverParams
        if (c == null || lp == null) {
            val g0 = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 60))
                .build()
            dispatchGesture(g0, null, null)
            return
        }
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(c, lp) } catch (_: Exception) { }
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        h.postDelayed({
            dispatchGesture(g, null, null)
            h.postDelayed({
                val c2 = cover
                val lp2 = coverParams
                if (c2 != null && lp2 != null) {
                    lp2.flags = lp2.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    try { wm.updateViewLayout(c2, lp2) } catch (_: Exception) { }
                }
            }, 250)
        }, 50)
    }
}
