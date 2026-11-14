package com.ad.bubble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * نافذة فيديو صغيرة (مثل PiP) تطفو فوگ كل التطبيقات وتبقى تشتغل لما تطلع من يوتيوب.
 * تستخدم مشغّل يوتيوب الرسمي المضمّن.
 */
class MiniPlayerService : Service() {

    companion object {
        const val EXTRA_ID = "id"
        const val ACTION_STOP = "com.ad.bubble.MINI_STOP"
        @Volatile var running = false
        private val ID_RE = Regex("""(?:youtu\.be/|v=|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})""")
        fun videoId(text: String): String? = ID_RE.find(text)?.groupValues?.get(1)
    }

    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var web: WebView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var big = false
    private var siteMode = false
    private var videoId = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(14, notification())
        val id = intent?.getStringExtra(EXTRA_ID)
        if (id != null) {
            videoId = id
            siteMode = false
            if (root == null) show()
            load()
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        web?.let { try { it.loadUrl("about:blank"); it.destroy() } catch (_: Exception) { } }
        root?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        root = null
        web = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        Prefs.channel(this, "mini", "المشغّل العائم")
        val stop = PendingIntent.getService(
            this, 5, Intent(this, MiniPlayerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, "mini")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("المشغّل العائم شغّال 🎬")
            .setContentText("الفيديو يكمل فوگ التطبيقات")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "سكّر", stop).build())
            .build()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    private fun sizeW() = if (big) (resources.displayMetrics.widthPixels * 0.95f).toInt() else dp(260f)
    private fun sizeH() = sizeW() * 9 / 16 + dp(34f)

    private fun barBtn(t: String, click: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10f), 0, dp(10f), 0)
        setOnClickListener { click() }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun show() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(12f).toFloat()
            }
            clipToOutline = true
            elevation = dp(10f).toFloat()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1F2937.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = TextView(this).apply {
            text = "⠿ اسحب"
            textSize = 13f
            setTextColor(0xFFD1D5DB.toInt())
            setPadding(dp(10f), 0, 0, 0)
        }
        bar.addView(handle, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(barBtn("⤢") { big = !big; resize() }, LinearLayout.LayoutParams(-2, -1))
        bar.addView(barBtn("🌐") { siteMode = !siteMode; load() }, LinearLayout.LayoutParams(-2, -1))
        bar.addView(barBtn("✕") { stopSelf() }, LinearLayout.LayoutParams(-2, -1))
        col.addView(bar, LinearLayout.LayoutParams(-1, dp(34f)))

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            setBackgroundColor(Color.BLACK)
        }
        col.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f))

        val p = WindowManager.LayoutParams(
            sizeW(), sizeH(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12f)
            y = dp(120f)
        }

        // سحب النافذة من الشريط
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = p.x; sy = p.y }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - dx) + abs(e.rawY - dy) > 6) {
                        p.x = sx + (e.rawX - dx).toInt()
                        p.y = sy + (e.rawY - dy).toInt()
                        try { wm.updateViewLayout(col, p) } catch (_: Exception) { }
                    }
                }
            }
            false
        }

        try {
            wm.addView(col, p)
        } catch (_: Exception) {
            stopSelf()
            return
        }
        root = col
        web = wv
        lp = p
    }

    private fun resize() {
        val p = lp ?: return
        val r = root ?: return
        p.width = sizeW()
        p.height = sizeH()
        if (big) p.x = (resources.displayMetrics.widthPixels - p.width) / 2
        try { wm.updateViewLayout(r, p) } catch (_: Exception) { }
    }

    private fun load() {
        val wv = web ?: return
        if (siteMode) {
            wv.loadUrl("https://m.youtube.com/watch?v=$videoId")
            return
        }
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style>
            </head><body>
            <iframe src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&modestbranding=1"
              allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen
              referrerpolicy="strict-origin-when-cross-origin"></iframe>
            </body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL("https://p2p37mx.github.io/", html, "text/html", "utf-8", null)
    }
}
