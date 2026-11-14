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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * إذا طلع إعلان شاشة كاملة (رغم الحجب) يغطيه بشاشة كارتونية (أو سودة/بيضة) مع ✕
 * ويحاول يدوس زر الإغلاق/التخطي مال الإعلان بنفسه أول ما يطلع.
 */
class AdCloserService : AccessibilityService() {

    companion object {
        @Volatile
        var connected = false
        val coveredCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val STORE_PKGS = setOf(
            "com.android.vending", "com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser",
            "com.huawei.appmarket", "com.xiaomi.market", "com.opera.browser", "com.microsoft.emmx",
            "com.heytap.browser", "com.mi.globalbrowser", "com.android.browser"
        )
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var cover: FrameLayout? = null
    private var coverParams: WindowManager.LayoutParams? = null
    private var cartoon: CartoonView? = null
    private var plainCount: TextView? = null
    private var xBtn: TextView? = null
    private var skipBtn: TextView? = null
    private var adClose: AccessibilityNodeInfo? = null
    private var adSkip: AccessibilityNodeInfo? = null
    private var blindMode = false
    private var guardUntil = 0L
    private var lastBlind = 0L
    private var blindSide = 0
    private var adPkg: String? = null
    private var shownAt = 0L
    private var adStartedAt = 0L
    private var lastBack = 0L
    private var lastClick = 0L
    private var clickedAt = 0L
    private var xTapAt = 0L
    private var adSecs = -1
    private var adSecsAt = 0L
    private var muted = false
    /** الألعاب اللي بيها إعلان مفتوح هسه (حتى لو طلعت ورجعت) */
    private val adActive = HashMap<String, Long>()
    private val suppressUntil = HashMap<String, Long>()
    private val activityCache = HashMap<String, Boolean>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Blocklist.loadScreens(this)
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

    private fun now() = SystemClock.uptimeMillis()

    private fun canCover(pkg: String): Boolean =
        Prefs.closer(this) && pkg !in Prefs.excluded(this) && now() > (suppressUntil[pkg] ?: 0L)

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        // يوتيوب: تخطي الإعلان تلقائياً + كتم صوته
        if (pkg in YT_PKGS) {
            if (Prefs.ytSkip(this)) ytCheck(pkg)
        } else if (ytMuted && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            setMute("yt", false)
            ytMuted = false
        }

        // إذا ضغطنا شي وفتح المتجر/المتصفح بالغلط → نرجع فوراً
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg in STORE_PKGS &&
            (now() - clickedAt < 4000 || now() < guardUntil)
        ) {
            clickedAt = 0L
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        if (!Prefs.closer(this)) {
            hideCover()
            return
        }

        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = e.className?.toString() ?: return
            val isAd = Blocklist.isAdActivity(pkg, cls)
            val isAct = isAd || isActivity(pkg, cls)
            if (isAct) ScreenLog.add(pkg, cls, isAd)
            when {
                isAd -> {
                    if (pkg !in adActive) adActive[pkg] = now()
                    if (canCover(pkg)) showCover(pkg)
                }
                isAct && pkg in adActive -> {
                    // رجعت شاشة اللعبة نفسها → الإعلان تسكّر
                    adActive.remove(pkg)
                    if (adPkg == pkg) hideCover()
                }
                isAct -> {
                    // تطبيق ثاني (مثلاً الشاشة الرئيسية) → نشيل الغطاء بس نتذكر الإعلان
                    if (cover != null && adPkg != pkg) hideCover()
                }
                pkg in adActive && cover == null && canCover(pkg) -> showCover(pkg)
            }
        } else if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // رجع للعبة والإعلان بعده مفتوح
            val since = adActive[pkg]
            if (since != null && cover == null && now() - since < 5 * 60_000 && canCover(pkg)) showCover(pkg)
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
            if (adShown && !ytMuted) { setMute("yt", true); ytMuted = true }
            else if (!adShown && ytMuted) { setMute("yt", false); ytMuted = false }
        }
    }

    private fun setMute(src: String, on: Boolean) = AdMute.set(this, src, on)

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

    private val hintText = "الإعلان يتسكّر وحده ونرجع للعبة"

    @SuppressLint("SetTextI18n")
    private fun showCover(pkg: String) {
        if (cover != null) return
        adPkg = pkg
        shownAt = now()
        adStartedAt = adActive[pkg] ?: shownAt
        lastBack = 0L
        adSecs = -1
        coveredCount.incrementAndGet()

        val root = FrameLayout(this)
        if (Prefs.cartoon(this)) {
            val cv = CartoonView(this).apply {
                animal = Prefs.nextAnimal(this@AdCloserService)
                countUp = Prefs.countUp(this@AdCloserService)
                onDrag = { pts -> replayDrag(pts) }
            }
            root.addView(cv, FrameLayout.LayoutParams(-1, -1))
            cartoon = cv
        } else {
            val white = Prefs.whiteCover(this)
            root.setBackgroundColor(if (white) Color.WHITE else Color.BLACK)
            val fg = if (white) Color.DKGRAY else Color.LTGRAY
            val center = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            center.addView(TextView(this).apply {
                text = "🚫"; textSize = 54f; gravity = Gravity.CENTER
            })
            val count = TextView(this).apply {
                text = "تم حجب إعلان\nجاري الإغلاق تلقائياً…"
                textSize = 18f; setTextColor(fg); gravity = Gravity.CENTER
            }
            center.addView(count)
            plainCount = count
            root.addView(center, FrameLayout.LayoutParams(-1, -1))
            root.addView(TextView(this).apply {
                text = hintText; textSize = 12f; setTextColor(fg); gravity = Gravity.CENTER
                setPadding(dp(16f), 0, dp(16f), dp(32f))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }

        // ✕ مالتنا: مخفية لين يطلع زر الإغلاق الحقيقي بالإعلان، وبعدها تصير واضحة وتنضغط
        val close = roundBtn("✕").apply { setOnClickListener { onXTap() } }
        val s = dp(60f)
        root.addView(close, FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.LEFT).apply {
            topMargin = dp(40f)
            leftMargin = dp(16f)
        })
        xBtn = close
        // سهم التجاوز: يطلع باليمين إذا الإعلان بيه زر تجاوز
        val skip = roundBtn("⏭").apply { setOnClickListener { onSkipTap() } }
        root.addView(skip, FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = dp(40f)
            rightMargin = dp(16f)
        })
        skipBtn = skip
        adClose = null
        adSkip = null
        blindMode = false
        lastBlind = 0L
        escStep = 0
        lastRelaunch = 0L
        setBtn(close, false)
        setBtn(skip, false, hide = true)

        // يمنع اللمس يوصل للإعلان تحت (حتى ما ينفتح المتجر بالغلط)
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
            cartoon = null
            plainCount = null
            xBtn = null
            skipBtn = null
            return
        }

        if (Prefs.mute(this) && !muted) {
            setMute("cover", true)
            muted = true
        }
        h.removeCallbacks(tick)
        h.postDelayed(tick, 300)
    }

    private fun hideCover() {
        h.removeCallbacks(tick)
        cover?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        cover = null
        coverParams = null
        cartoon = null
        plainCount = null
        xBtn = null
        skipBtn = null
        adClose = null
        adSkip = null
        adPkg = null
        if (muted) {
            muted = false
            setMute("cover", false)
        }
    }

    private fun roundBtn(label: String) = TextView(this).apply {
        text = label
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(0xFF1E293B.toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xEEFFFFFF.toInt())
            setStroke(dp(3f), 0xFF1E293B.toInt())
        }
        elevation = dp(6f).toFloat()
    }

    /** on = واضح وينضغط، off = باهت (شبه مخفي) */
    private fun setBtn(v: TextView?, on: Boolean, hide: Boolean = false) {
        v ?: return
        v.visibility = if (hide && !on) android.view.View.INVISIBLE else android.view.View.VISIBLE
        v.alpha = if (on) 1f else 0.12f
        v.scaleX = if (on) 1f else 0.85f
        v.scaleY = if (on) 1f else 0.85f
        v.elevation = if (on) dp(8f).toFloat() else 0f
    }

    /**
     * ✕ مالتنا: تشتغل بس لما يطلع زر الإغلاق الحقيقي بالإعلان.
     * قبلها الدوس عليها ما يكشف الإعلان، بس الحيوان ينطّ ويگول "استنى شوية".
     */
    private fun onXTap() {
        cartoon?.poke()
        val c = adClose
        if (c == null) {
            plainCount?.text = "استنى شوية ⏳\nالإعلان يتسكّر وحده"
            return
        }
        val t = now()
        if (t - xTapAt < 800) return
        xTapAt = t
        lastClick = t
        clickNode(c)
    }

    /** سهم التجاوز مالتنا: يدوس زر التجاوز بالإعلان */
    private fun onSkipTap() {
        val t = now()
        if (t - xTapAt < 800) return
        xTapAt = t
        val k = adSkip
        if (k != null) {
            lastClick = t
            clickNode(k)
        } else if (blindMode) {
            blindTap()
        } else {
            cartoon?.poke()
        }
    }

    // ---------------- بعد 30 ثانية: محاولات بالخلفية ----------------
    private var escStep = 0
    private var lastRelaunch = 0L
    private val cornerPts = arrayOf(
        floatArrayOf(0.95f, 0.04f), floatArrayOf(0.05f, 0.04f),
        floatArrayOf(0.92f, 0.07f), floatArrayOf(0.08f, 0.07f),
        floatArrayOf(0.95f, 0.025f), floatArrayOf(0.05f, 0.025f),
        floatArrayOf(0.94f, 0.10f), floatArrayOf(0.06f, 0.10f),
        floatArrayOf(0.90f, 0.05f), floatArrayOf(0.10f, 0.05f)
    )

    /**
     * سلّم محاولات: زوايا الشاشة كلها ← زر المتجر (نفتحه ونرجع، غالباً يطلع ✕ بعدها) ←
     * أزرار الإعلان وحدة وحدة ← رجوع ← وبالأخير نرجّع اللعبة للواجهة.
     * الغطاء يبقى طول الوقت، فالطفل ما يشوف شي.
     */
    private fun escalate(r: Scan?, sinceAd: Long) {
        val t = now()
        lastBlind = t
        val pkg = adPkg ?: return
        // آخر حل: نرجّع شاشة اللعبة نفسها فوق الإعلان
        if (sinceAd > 55_000 && t - lastRelaunch > 15_000) {
            lastRelaunch = t
            bringGameFront(pkg)
            return
        }
        val dm = resources.displayMetrics
        when (escStep % 4) {
            0, 1 -> {
                // زاويتين بكل خطوة
                for (k in 0 until 2) {
                    val pt = cornerPts[(escStep / 4 * 2 + k + escStep) % cornerPts.size]
                    val x = dm.widthPixels * pt[0]
                    val y = dm.heightPixels * pt[1]
                    h.postDelayed({ if (cover != null) { clickedAt = now(); tapThroughCover(x, y) } }, k * 450L)
                }
            }
            2 -> {
                val st = r?.store
                val list = r?.clickables ?: emptyList()
                when {
                    st != null && escStep % 8 == 2 -> {
                        guardUntil = t + 6000
                        clickNode(st)
                    }
                    list.isNotEmpty() -> {
                        guardUntil = t + 6000
                        val n = list[(escStep / 4) % list.size]
                        clickNode(n)
                    }
                    else -> pressBack()
                }
            }
            else -> pressBack()
        }
        escStep++
    }

    private fun bringGameFront(pkg: String) {
        try {
            val li = packageManager.getLaunchIntentForPackage(pkg) ?: return
            li.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            startActivity(li)
        } catch (_: Exception) {
        }
    }

    /** إذا الإعلان ما يبين أزراره (مرسوم داخل لعبة/ويب)، ندوس أماكن الأزرار المعتادة بالزوايا */
    private fun blindTap() {
        val dm = resources.displayMetrics
        val x = if (blindSide % 2 == 0) dm.widthPixels * 0.07f else dm.widthPixels * 0.93f
        val y = dm.heightPixels * 0.055f
        blindSide++
        lastBlind = now()
        clickedAt = now()
        tapThroughCover(x, y)
    }

    private fun pressBack() {
        lastBack = now()
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ---------------- الإغلاق التلقائي ----------------
    private val tick = object : Runnable {
        override fun run() {
            if (cover == null) return
            val t = now()
            val root = findRoot(adPkg)
            val r = root?.let { scan(it) }
            val rSecs = r?.seconds
            val rClose = r?.close
            val rSkip = r?.skip ?: r?.corner

            // العد (من 1 لـ 10 يتكرر، أو العد التنازلي القديم)
            if (rSecs != null) {
                adSecs = rSecs
                adSecsAt = t
            } else if (adSecs > 0 && t - adSecsAt >= 1000) {
                adSecs -= 1
                adSecsAt = t
            }
            val shown: Int
            val exact: Boolean
            if (adSecs >= 0) {
                shown = adSecs; exact = true
            } else {
                shown = maxOf(0, 30 - ((t - adStartedAt) / 1000).toInt()); exact = false
            }
            cartoon?.let { it.seconds = shown; it.exact = exact }
            plainCount?.let { if (t - xTapAt > 3000) it.text = "تم حجب إعلان\nنرجع للعبة بعد ~$shown ثانية" }

            // حالة الأزرار
            adClose = rClose
            adSkip = rSkip
            val elapsed = t - shownAt
            if (!blindMode && rClose == null && rSkip == null && t - adStartedAt > 30_000) blindMode = true
            setBtn(xBtn, rClose != null)
            cartoon?.dragHint = rClose == null && rSkip == null && elapsed > 4000
            setBtn(skipBtn, rSkip != null || blindMode, hide = true)

            // الإغلاق التلقائي
            if (rClose != null && t - lastClick > 1200) {
                lastClick = t
                clickNode(rClose)
            } else if (rSkip != null && elapsed > 2500 && t - lastClick > 1800) {
                lastClick = t
                clickNode(rSkip)
            } else if (rClose == null && rSkip == null) {
                val sinceAd = t - adStartedAt
                if (sinceAd >= 30_000 && t > guardUntil - 4000) {
                    // انتهى وقت الإعلان: نجرب كل شي بالخلفية لين يتسكّر
                    if (t - lastBlind > 1500) escalate(r, sinceAd)
                } else if (!blindMode && Prefs.autoBack(this@AdCloserService) && elapsed > 1500 && t - lastBack > 3000) {
                    pressBack()
                }
            }
            h.postDelayed(this, 500)
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

    private val closeWords = listOf("close", "dismiss", "إغلاق", "اغلاق", "أغلق", "اغلق", "سكّر", "سكر")
    private val skipWords = listOf("skip", "تخطي", "تخطّي", "تخطى", "تجاوز", "next", "التالي")
    private val skipMarks = setOf("›", "»", ">", "⏭", "→", "▶▶", "▶|", "⏩", ">>", "▸▸")
    private val badWords = listOf(
        "install", "download", "تثبيت", "تحميل", "open", "فتح", "learn", "more", "adchoices", "ad choices",
        "info", "option", "privacy", "report", "feedback", "sound", "mute", "volume", "الصوت", "كتم", "خيارات"
    )
    private val xMarks = setOf("x", "✕", "✖", "×", "╳", "⨯", "✗")
    private val numRe = Regex("""^(\d{1,2})$""")
    private val secRe = Regex("""(\d{1,2})\s*(s|sec|secs|seconds|second|ث|ثانية|ثواني|ثوان)\b""", RegexOption.IGNORE_CASE)
    private val inRe = Regex("""(?:in|بعد)\s*(\d{1,2})""", RegexOption.IGNORE_CASE)

    private class Scan(
        val close: AccessibilityNodeInfo?,
        val skip: AccessibilityNodeInfo?,
        val corner: AccessibilityNodeInfo?,
        val seconds: Int?,
        val store: AccessibilityNodeInfo? = null,
        val clickables: List<AccessibilityNodeInfo> = emptyList()
    )

    private val storeWords = listOf(
        "install", "download", "get it", "play now", "open store", "المتجر", "تثبيت", "تحميل", "نزّل", "العب الآن"
    )

    /** يدوّر بالإعلان على: زر إغلاق واضح، زر صغير بالزاوية (سهم/✕ بلا اسم)، ورقم العد التنازلي */
    private fun scan(root: AccessibilityNodeInfo): Scan {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val shh = dm.heightPixels
        val minS = dp(14f)
        val maxS = dp(96f)
        var close: AccessibilityNodeInfo? = null
        var skip: AccessibilityNodeInfo? = null
        var store: AccessibilityNodeInfo? = null
        val clickables = ArrayList<AccessibilityNodeInfo>()
        var corner: AccessibilityNodeInfo? = null
        var cornerScore = Int.MAX_VALUE
        var secs: Int? = null
        val rect = Rect()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen < 800) {
            val n = q.removeFirst()
            seen++
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            if (!n.isVisibleToUser) continue
            val t = (n.text?.toString() ?: "").trim().lowercase()
            val d = (n.contentDescription?.toString() ?: "").trim().lowercase()
            val id = (n.viewIdResourceName ?: "").substringAfter(":id/").lowercase()
            val words = "$t $d $id"
            val bad = badWords.any { words.contains(it) }

            // رقم العد التنازلي
            if (secs == null) {
                for (s in listOf(t, d)) {
                    if (s.isEmpty() || s.length > 30) continue
                    val m = numRe.find(s) ?: secRe.find(s) ?: inRe.find(s)
                    val v = m?.groupValues?.get(1)?.toIntOrNull()
                    if (v != null && v in 1..90) { secs = v; break }
                }
            }

            if (n.isClickable && clickables.size < 30) clickables.add(n)
            if (bad) {
                if (store == null && n.isClickable && storeWords.any { words.contains(it) }) store = n
                continue
            }
            // زر إغلاق واضح
            var isClose = false
            var isSkip = false
            for (s in listOf(t, d)) {
                if (s.isEmpty() || s.length > 24) continue
                if (s in xMarks || closeWords.any { s.contains(it) }) isClose = true
                if (s in skipMarks || skipWords.any { s.contains(it) }) isSkip = true
            }
            if (id.contains("close") || id.contains("dismiss") || id.contains("exit")) isClose = true
            if (id.contains("skip")) isSkip = true
            if (isClose) { if (close == null) close = n; continue }
            if (isSkip) { if (skip == null) skip = n; continue }
            // زر صغير بالزاوية الفوگانية (السهم أو ✕ بلا اسم)
            n.getBoundsInScreen(rect)
            val w = rect.width()
            val hh = rect.height()
            if (w < minS || hh < minS || w > maxS || hh > maxS) continue
            if (t.length > 3) continue
            val cls = n.className?.toString() ?: ""
            val tappable = n.isClickable || cls.endsWith("ImageView") || cls.endsWith("ImageButton") || cls.endsWith("Button")
            if (!tappable) continue
            val cx = rect.centerX()
            val cy = rect.centerY()
            if (cy > shh * 0.22f) continue
            val dx = minOf(cx, sw - cx)
            if (dx > sw * 0.25f) continue
            val score = dx + cy
            if (score < cornerScore) { cornerScore = score; corner = n }
        }
        return Scan(close, skip, corner, secs, store, clickables)
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        clickedAt = now()
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 3) {
            if (n.isClickable && n.isEnabled) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            n = n.parent
            hops++
        }
        // ما ينضغط عادي → لمسة على مكان الزر (الغطاء ما يمسك اللمسة لحظة)
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return
        tapThroughCover(r.exactCenterX(), r.exactCenterY())
    }

    /**
     * سحب البالون → نعيد نفس الحركة على الإعلان اللي ورا (بأسفل الشاشة، بعيد عن زر التثبيت)
     * حتى الإعلانات اللي "لازم تلعبها" تتحرك لين يطلع زر التجاوز/الإغلاق.
     */
    private fun replayDrag(pts: List<FloatArray>) {
        val c = cover ?: return
        val lp = coverParams ?: return
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val hgt = dm.heightPixels.toFloat()
        val baseY = hgt * 0.68f
        val y0 = pts[0][1]
        val path = Path()
        for ((i, q) in pts.withIndex()) {
            val x = q[0].coerceIn(w * 0.05f, w * 0.95f)
            val y = (baseY + (q[1] - y0) * 0.5f).coerceIn(hgt * 0.45f, hgt * 0.9f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val maxDur = GestureDescription.getMaxGestureDuration()
        val dur = pts.last()[2].toLong().coerceIn(80L, minOf(maxDur, 15_000L))
        val g = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build()
        } catch (_: Exception) {
            return
        }
        guardUntil = now() + dur + 5000
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(c, lp) } catch (_: Exception) { }
        val restore = Runnable {
            val c2 = cover
            val lp2 = coverParams
            if (c2 != null && lp2 != null) {
                lp2.flags = lp2.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { wm.updateViewLayout(c2, lp2) } catch (_: Exception) { }
            }
        }
        h.postDelayed({
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { h.post(restore) }
                override fun onCancelled(gestureDescription: GestureDescription?) { h.post(restore) }
            }, null)
            if (!ok) h.post(restore)
            // احتياط لو ما رجع الـ callback
            h.postDelayed(restore, dur + 800)
        }, 40)
    }

    private fun tapThroughCover(x: Float, y: Float) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 60))
            .build()
        val c = cover
        val lp = coverParams
        if (c == null || lp == null) {
            dispatchGesture(g, null, null)
            return
        }
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(c, lp) } catch (_: Exception) { }
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
