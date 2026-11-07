package com.ad.bubble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val EXTRA_START_VPN = "start_vpn"
        private const val REQ_VPN = 7
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var vpnBtn: Button
    private lateinit var bubbleBtn: Button
    private lateinit var accBtn: Button
    private lateinit var domainsEdit: EditText

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_START_VPN, false) == true) {
            i.removeExtra(EXTRA_START_VPN)
            startVpn()
        }
    }

    override fun onResume() {
        super.onResume()
        tick()
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacksAndMessages(null)
    }

    private fun tick() {
        refresh()
        h.postDelayed({ tick() }, 1000)
    }

    // ---------------- الواجهة ----------------
    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18f), dp(16f), dp(18f), dp(32f))
        }

        col.addView(TextView(this).apply {
            text = "🚫 فقاعة الإعلانات"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "تمنع إعلانات الألعاب من الظهور. وإذا طلع إعلان، يتغطى بشاشة سودة مع ✕ ويتسكّر."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6f), 0, dp(12f))
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            setBackgroundColor(Color.parseColor("#F3F4F6"))
        }
        col.addView(status)

        vpnBtn = button("① تشغيل حجب الإعلانات") { if (AdVpnService.running) AdVpnService.stop(this) else startVpn() }
        col.addView(vpnBtn)
        col.addView(hint("هذا الأساس: الإعلان ما يتحمّل أصلاً. راح يطلع مفتاح 🔑 فوق بالشاشة — طبيعي، هو VPN محلي داخل الموبايل بس وما يبطّئ النت."))

        bubbleBtn = button("② إظهار الفقاعة") { toggleBubble() }
        col.addView(bubbleBtn)
        col.addView(hint("الفقاعة: دوسة = تشغيل/إطفاء الحجب • سحب = تحريك • ضغطة مطوّلة = إخفاء."))

        accBtn = button("③ تفعيل الشاشة السودة لغلق الإعلانات") { openAccessibility() }
        col.addView(accBtn)
        col.addView(hint("من الإعدادات اختار «فقاعة الإعلانات» وفعّلها. إذا قال «إعداد مقيّد»: ادخل معلومات التطبيق ← النقاط الثلاث ← السماح بالإعدادات المقيدة، وارجع فعّلها."))

        col.addView(section("الإعدادات"))
        col.addView(switch("الشاشة السودة شغّالة", Prefs.closer(this)) { Prefs.setCloser(this, it) })
        col.addView(switch("شاشة بيضة بدل السودة", Prefs.whiteCover(this)) { Prefs.setWhiteCover(this, it) })
        col.addView(switch("كتم الصوت وقت الإعلان", Prefs.mute(this)) { Prefs.setMute(this, it) })
        col.addView(switch("رجوع تلقائي إذا ما لگى زر الإغلاق", Prefs.autoBack(this)) { Prefs.setAutoBack(this, it) })
        col.addView(switch("يوتيوب: تخطي الإعلان تلقائياً + كتم صوته", Prefs.ytSkip(this)) { Prefs.setYtSkip(this, it) })
        col.addView(hint("إعلانات يوتيوب تجي من نفس سيرفرات الفيديو فما تنحجب، بس تنتخطى أول ما يطلع زر «تخطي». يحتاج الشاشة السودة (③) مفعّلة."))

        col.addView(section("لعبة بعدها تطلع بيها إعلانات؟"))
        col.addView(button("📋 آخر المواقع اللي طلبتها التطبيقات") { showRecent() })
        col.addView(hint("افتح اللعبة لين يطلع الإعلان، وارجع هنا. دوّر على اسم اللعبة، وأي موقع بيه كلمة ad أو ads أو اسم شركة إعلانات، دوس عليه حتى ينحجب."))

        col.addView(section("استثناءات"))
        col.addView(button("التطبيقات المستثناة (تبقى بيها الإعلانات)") { pickExcluded() })
        col.addView(hint("مثلاً لعبة تريد بيها إعلان المكافأة (تشوف إعلان وتاخذ جوائز) — استثنيها، أو طفّي الفقاعة لحظة."))

        col.addView(section("دومينات إعلانات إضافية"))
        domainsEdit = EditText(this).apply {
            setText(Prefs.customDomains(this@MainActivity))
            hint = "كل دومين بسطر، مثلاً: ads.example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_LTR
        }
        col.addView(domainsEdit)
        col.addView(button("حفظ الدومينات") {
            Prefs.setCustomDomains(this, domainsEdit.text.toString())
            Blocklist.load(this)
            Toast.makeText(this, "انحفظت ✅", Toast.LENGTH_SHORT).show()
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) }
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.GRAY)
        setPadding(dp(4f), dp(4f), dp(4f), 0)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(22f), 0, dp(4f))
    }

    private fun switch(t: String, on: Boolean, change: (Boolean) -> Unit) = Switch(this).apply {
        text = t
        textSize = 15f
        isChecked = on
        setPadding(0, dp(8f), 0, dp(8f))
        setOnCheckedChangeListener { _, v -> change(v) }
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val vpn = AdVpnService.running
        val acc = AdCloserService.connected
        status.text =
            "الحجب: " + (if (vpn) "شغّال ✅" else "طافي ⛔") + "\n" +
            "طلبات إعلانات انحجبت: " + AdVpnService.blockedCount.get() + "\n" +
            "طلبات نت مرّت: " + AdVpnService.answeredCount.get() + " • فشلت: " + AdVpnService.failedCount.get() + "\n" +
            "الشاشة السودة: " + (if (acc) "مفعّلة ✅" else "مو مفعّلة") +
            (if (acc) " (غطّت " + AdCloserService.coveredCount.get() + " إعلان)" else "") + "\n" +
            "الفقاعة: " + (if (BubbleService.running) "ظاهرة" else "مخفية")
        vpnBtn.text = if (vpn) "① إيقاف حجب الإعلانات" else "① تشغيل حجب الإعلانات"
        bubbleBtn.text = if (BubbleService.running) "② إخفاء الفقاعة" else "② إظهار الفقاعة"
        accBtn.text = if (acc) "③ الشاشة السودة مفعّلة ✅ (فتح الإعدادات)" else "③ تفعيل الشاشة السودة لغلق الإعلانات"
    }

    // ---------------- الأفعال ----------------
    private fun startVpn() {
        val i = VpnService.prepare(this)
        if (i != null) {
            try {
                startActivityForResult(i, REQ_VPN)
            } catch (_: Exception) {
                Toast.makeText(this, "ما گدرت أفتح طلب الـ VPN", Toast.LENGTH_LONG).show()
            }
        } else {
            AdVpnService.start(this)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) AdVpnService.start(this)
    }

    private fun toggleBubble() {
        if (BubbleService.running) {
            startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_STOP))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "اسمح للتطبيق «بالظهور فوق التطبيقات» وارجع", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        startForegroundService(Intent(this, BubbleService::class.java))
    }

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
        }
    }

    private fun appName(uid: Int, cache: HashMap<Int, String>): String {
        if (uid < 0) return "تطبيق غير معروف"
        return cache.getOrPut(uid) {
            val pm = packageManager
            val pkg = try { pm.getPackagesForUid(uid)?.firstOrNull() } catch (_: Exception) { null }
            if (pkg == null) "تطبيق نظام ($uid)"
            else try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        }
    }

    private fun showRecent() {
        val list = DnsLog.recent().take(250)
        if (list.isEmpty()) {
            Toast.makeText(this, "ماكو شي بعد — شغّل الحجب وافتح اللعبة", Toast.LENGTH_LONG).show()
            return
        }
        val cache = HashMap<Int, String>()
        val items = list.map {
            (if (it.blocked) "🚫 " else "✅ ") + it.domain + "\n      " + appName(it.uid, cache)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("دوس على موقع حتى تحجبه (🚫 = محجوب)")
            .setItems(items) { _, w -> confirmBlock(list[w].domain) }
            .setNeutralButton("مسح السجل") { _, _ -> DnsLog.clear() }
            .setNegativeButton("سكّر", null)
            .show()
    }

    private fun confirmBlock(d: String) {
        val parts = d.split('.')
        val options = ArrayList<String>()
        options.add(d)
        if (parts.size > 2) options.add(parts.takeLast(2).joinToString("."))
        val labels = options.mapIndexed { i, o ->
            if (i == 0) "حجب هذا الموقع بس:\n$o" else "حجب الموقع وكل فروعه:\n$o"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("حجب؟ (إذا اللعبة خربت، امسحه من الدومينات الإضافية)")
            .setItems(labels) { _, w ->
                Prefs.addDomain(this, options[w])
                Blocklist.load(this)
                domainsEdit.setText(Prefs.customDomains(this))
                Toast.makeText(this, "انحجب ✅ سكّر اللعبة وافتحها من جديد", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun pickExcluded() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) return
        val current = Prefs.excluded(this)
        val checked = BooleanArray(apps.size) { apps[it].first in current }
        AlertDialog.Builder(this)
            .setTitle("اختار التطبيقات المستثناة")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, w, v -> checked[w] = v }
            .setPositiveButton("حفظ") { _, _ ->
                val set = apps.indices.filter { checked[it] }.map { apps[it].first }.toSet()
                Prefs.setExcluded(this, set)
                if (AdVpnService.running) {
                    // نعيد تشغيل الحجب حتى ينطبق الاستثناء
                    AdVpnService.stop(this)
                    h.postDelayed({ AdVpnService.start(this) }, 800)
                }
                Toast.makeText(this, "انحفظ ✅ (${set.size} تطبيق)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
