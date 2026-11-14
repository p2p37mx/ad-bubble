package com.ad.bubble

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * يراقب تشغيل يوتيوب / يوتيوب ميوزك من "جلسة الوسائط" (نفس المعلومات اللي توصل للسيارة)،
 * فيشتغل حتى لو الشاشة مطفية أو الموبايل مربوط بـ Android Auto.
 * إذا الإعلان شغّال → يكتم الصوت، وإذا خلص → يرجّعه.
 */
class MediaWatchService : NotificationListenerService() {

    companion object {
        val YT = setOf("com.google.android.youtube", "com.google.android.apps.youtube.music")
        @Volatile var connected = false
        @Volatile var lastInfo = ""
        @Volatile var adNow = false

        private val AD_WORDS = listOf(
            "advertisement", "sponsored", "إعلان", "اعلان", "ممول", "ad ·", "ad •", "ad -", "ad ⋅",
            "video will play after ad", "سيتم تشغيل الفيديو بعد الإعلان"
        )

        fun looksLikeAd(pkg: String, md: MediaMetadata?, st: PlaybackState?): Boolean {
            val parts = ArrayList<String>()
            if (md != null) {
                for (k in listOf(
                    MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION
                )) md.getString(k)?.let { parts.add(it.lowercase()) }
            }
            val txt = parts.joinToString(" | ")
            if (txt == "ad" || parts.any { it.trim() == "ad" }) return true
            if (AD_WORDS.any { txt.contains(it) }) return true
            // يوتيوب ميوزك: وقت الإعلان يقفل التقديم/التأخير، والإعلان قصير
            if (!pkg.endsWith("music")) return false
            val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val canSeek = st != null && (st.actions and PlaybackState.ACTION_SEEK_TO) != 0L
            return st != null && dur in 1_000L..65_000L && !canSeek
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private val callbacks = HashMap<MediaController, MediaController.Callback>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        h.post { attach(list ?: emptyList()) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        try {
            val m = getSystemService(MediaSessionManager::class.java)
            msm = m
            val me = ComponentName(this, MediaWatchService::class.java)
            m.addOnActiveSessionsChangedListener(sessionsListener, me, h)
            attach(m.getActiveSessions(me))
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        detachAll()
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) { }
        setAd(false)
        super.onListenerDisconnected()
    }

    private fun detachAll() {
        for ((c, cb) in callbacks) try { c.unregisterCallback(cb) } catch (_: Exception) { }
        callbacks.clear()
    }

    private fun attach(list: List<MediaController>) {
        detachAll()
        for (c in list) {
            if (c.packageName !in YT) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = check()
                override fun onPlaybackStateChanged(state: PlaybackState?) = check()
                override fun onSessionDestroyed() = check()
            }
            try {
                c.registerCallback(cb, h)
                callbacks[c] = cb
            } catch (_: Exception) {
            }
        }
        check()
    }

    private fun check() {
        var ad = false
        val sb = StringBuilder()
        for (c in callbacks.keys) {
            val st = try { c.playbackState } catch (_: Exception) { null }
            val md = try { c.metadata } catch (_: Exception) { null }
            val playing = st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING
            val isAd = playing && looksLikeAd(c.packageName, md, st)
            if (isAd) ad = true
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "-"
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "-"
            val dur = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000
            val seek = st != null && (st.actions and PlaybackState.ACTION_SEEK_TO) != 0L
            sb.append(if (c.packageName.endsWith("music")) "YT Music" else "YouTube")
                .append(": ").append(title).append(" | ").append(artist)
                .append(" | ").append(dur).append("ث | تقديم=").append(if (seek) "اي" else "لا")
                .append(if (isAd) " | 🔇 إعلان" else "").append('\n')
        }
        lastInfo = sb.toString().trim()
        setAd(ad && Prefs.carMute(this))
    }

    private fun setAd(on: Boolean) {
        if (adNow == on) return
        adNow = on
        AdMute.set(this, "media", on)
    }
}
