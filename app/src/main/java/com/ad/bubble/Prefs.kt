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

    fun ytSkip(c: Context) = sp(c).getBoolean("ytSkip", true)
    fun setYtSkip(c: Context, v: Boolean) = sp(c).edit().putBoolean("ytSkip", v).apply()

    /** يضيف دومين لقائمة الحجب الإضافية */
    fun addDomain(c: Context, d: String) {
        val cur = customDomains(c).lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (d in cur) return
        setCustomDomains(c, (cur + d).joinToString("\n"))
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
