package com.ad.bubble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/** الفقاعة الطايرة: دوسة = تشغيل/إيقاف الحجب، سحب = تحريك، ضغطة مطوّلة = إخفاء الفقاعة */
class BubbleService : Service() {

    companion object {
        const val ACTION_STOP = "com.ad.bubble.BUBBLE_STOP"
        private const val CHANNEL = "bubble"
        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }

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
        startForeground(12, notification())
        if (bubble == null) show()
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        bubble = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        Prefs.channel(this, CHANNEL, "الفقاعة")
        val stop = PendingIntent.getService(
            this, 3, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("فقاعة الإعلانات ظاهرة")
            .setContentText("دوس على الفقاعة حتى تشغّل أو تطفي الحجب")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "إخفاء الفقاعة", stop).build())
            .build()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        val size = dp(56f)
        val v = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = bg
            elevation = dp(6f).toFloat()
        }
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.bubbleX(this@BubbleService)
            y = Prefs.bubbleY(this@BubbleService)
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val longPress = Runnable {
            longFired = true
            stopSelf()
        }

        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    moved = false; longFired = false
                    h.postDelayed(longPress, 800)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        h.removeCallbacks(longPress)
                    }
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(longPress)
                    if (moved) Prefs.setBubblePos(this, p.x, p.y)
                    else if (!longFired && e.actionMasked == MotionEvent.ACTION_UP) toggle()
                }
            }
            true
        }

        try {
            wm.addView(v, p)
        } catch (_: Exception) {
            stopSelf()
            return
        }
        bubble = v
        lp = p
        refresh()
    }

    private fun toggle() {
        if (AdVpnService.running) {
            AdVpnService.stop(this)
        } else {
            if (VpnService.prepare(this) != null) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_START_VPN, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                try { AdVpnService.start(this) } catch (_: Exception) { }
            }
        }
        h.postDelayed({ paint() }, 500)
    }

    private fun paint() {
        val v = bubble ?: return
        if (AdVpnService.running) {
            bg.setColor(Color.parseColor("#16A34A"))
            bg.setStroke(dp(2f), Color.WHITE)
            v.text = "🚫\n" + AdVpnService.blockedCount.get()
            v.textSize = 11f
        } else {
            bg.setColor(Color.parseColor("#6B7280"))
            bg.setStroke(dp(2f), Color.parseColor("#D1D5DB"))
            v.text = "AD"
            v.textSize = 16f
        }
        v.background = bg
        v.invalidate()
    }

    private fun refresh() {
        paint()
        h.postDelayed({ refresh() }, 1000)
    }
}
