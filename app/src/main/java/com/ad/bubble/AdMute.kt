package com.ad.bubble

import android.content.Context
import android.media.AudioManager

/**
 * كتم الصوت المشترك: كل جهة (الغطاء، يوتيوب بالشاشة، يوتيوب بالسيارة) تطلب كتم،
 * والصوت يرجع بس لما كلهم يخلصون.
 */
object AdMute {
    private val active = HashSet<String>()
    private var savedVol = -1

    @Synchronized
    fun set(c: Context, src: String, on: Boolean) {
        val was = active.isNotEmpty()
        if (on) active.add(src) else active.remove(src)
        val now = active.isNotEmpty()
        if (was == now) return
        val am = c.getSystemService(AudioManager::class.java) ?: return
        try {
            if (now) {
                savedVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                // بعض الأجهزة/السيارات تتجاهل الكتم، فنصفّر الصوت هم
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                }
            } else {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                if (savedVol > 0 && am.getStreamVolume(AudioManager.STREAM_MUSIC) < savedVol) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0)
                }
                savedVol = -1
            }
        } catch (_: Exception) {
        }
    }
}
