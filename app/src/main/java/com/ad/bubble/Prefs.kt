package com.ad.bubble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("ad_bubble", Context.MODE_PRIVATE)

    /** التطبيقات المستثناة (تبقى بيها الإعلانات، مثلاً لإعلانات المكافأة) */
    fun excluded(c: Context): Set<String> = sp(c).getStringSet("excluded", emptySet()) ?: emptySet()
    fun setExcluded(c: Context, v: Set<String>) = sp(c).edit().putStringSet("excluded", HashSet(v)).apply()

    fun closer(c: Context) = sp(c).getBoolean("closer", true)
    fun setCloser(c: Context, v: Boolean) = sp(c).edit().putBoolean("closer", v).apply()

    fun mute(c: Context) = sp(c).getBoolean("mute", true)
    fun setMute(c: Context, v: Boolean) = sp(c).edit().putBoolean("mute", v).apply()

    fun whiteCover(c: Context) = sp(c).getBoolean("white", false)
    fun setWhiteCover(c: Context, v: Boolean) = sp(c).edit().putBoolean("white", v).apply()

    fun autoBack(c: Context) = sp(c).getBoolean("autoBack", true)
    fun setAutoBack(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoBack", v).apply()

    /** الشكل الكارتوني (تگدر ترجع للشاشة السودة القديمة من الإعدادات) */
    fun cartoon(c: Context) = sp(c).getBoolean("cartoon", true)
    fun setCartoon(c: Context, v: Boolean) = sp(c).edit().putBoolean("cartoon", v).apply()

    /** كل إعلان حيوان مختلف */
    fun nextAnimal(c: Context): Int {
        val n = (sp(c).getInt("animal", -1) + 1) % CartoonView.ANIMALS
        sp(c).edit().putInt("animal", n).apply()
        return n
    }

    /** العد من 1 لـ 10 (إذا طافي يرجع العد التنازلي القديم) */
    fun countUp(c: Context) = sp(c).getBoolean("countUp", true)
    fun setCountUp(c: Context, v: Boolean) = sp(c).edit().putBoolean("countUp", v).apply()

    fun ytSkip(c: Context) = sp(c).getBoolean("ytSkip", true)
    fun setYtSkip(c: Context, v: Boolean) = sp(c).edit().putBoolean("ytSkip", v).apply()

    /** يضيف دومين لقائمة الحجب الإضافية */
    fun addDomain(c: Context, d: String) {
        val cur = customDomains(c).lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (d in cur) return
        setCustomDomains(c, (cur + d).joinToString("\n"))
    }

    fun adScreens(c: Context): Set<String> = sp(c).getStringSet("adScreens", emptySet()) ?: emptySet()
    fun addAdScreen(c: Context, cls: String) {
        val cur = HashSet(adScreens(c)); cur.add(cls)
        sp(c).edit().putStringSet("adScreens", cur).apply()
    }

    fun customDomains(c: Context) = sp(c).getString("domains", "") ?: ""
    fun setCustomDomains(c: Context, v: String) = sp(c).edit().putString("domains", v).apply()

    fun bubbleX(c: Context) = sp(c).getInt("bx", 0)
    fun bubbleY(c: Context) = sp(c).getInt("by", 300)
    fun setBubblePos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("bx", x).putInt("by", y).apply()

    fun channel(c: Context, id: String, name: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW))
        }
    }
}
