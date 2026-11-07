package com.ad.bubble

import android.content.Context

/** قائمة شبكات الإعلانات اللي تستخدمها الألعاب، وأسماء شاشات الإعلانات */
object Blocklist {

    private val DOMAINS = hashSetOf(
        // Google AdMob / Ad Manager
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "adservice.google.com", "imasdk.googleapis.com", "admob.com",
        // Unity Ads
        "unityads.unity3d.com", "unityads.unitychina.cn",
        // AppLovin / MAX
        "applovin.com", "applvn.com",
        // ironSource / LevelPlay
        "supersonicads.com", "ironsrc.mobi", "ironsrc.com",
        // Vungle / Liftoff
        "vungle.com", "liftoff-creatives.io",
        // AdColony / Chartboost / InMobi / Tapjoy / StartApp
        "adcolony.com", "chartboost.com", "inmobi.com", "tapjoy.com", "tapjoyads.com",
        "startappservice.com",
        // Facebook Audience Network (الإعلانات فقط، مو فيسبوك)
        "an.facebook.com",
        // Pangle / Mintegral / Fyber / Smaato / Moloco / Appnext / myTarget / Bigo
        "pangle.io", "pangleglobal.com", "mintegral.com", "mintegral.net", "rayjump.com",
        "fyber.com", "inner-active.mobi", "smaato.net", "adsmoloco.com", "appnext.com",
        "ads.mytarget.ru", "bigossp.com",
        // Yandex ads
        "an.yandex.ru", "yandexadexchange.net",
        // شبكات عامة
        "amazon-adsystem.com", "adnxs.com", "rubiconproject.com", "pubmatic.com",
        "openx.net", "criteo.com", "criteo.net", "adsrvr.org", "taboola.com", "outbrain.com",
        "mopub.com", "adform.net", "smartadserver.com", "yieldmo.com", "verve.com", "pubnative.net"
    )

    @Volatile
    private var extra: Set<String> = emptySet()

    fun load(c: Context) {
        extra = Prefs.customDomains(c).lines()
            .map { it.trim().lowercase().removePrefix("*.").removePrefix(".").trimEnd('.') }
            .filter { it.contains('.') && !it.contains(' ') }
            .toSet()
    }

    fun isBlocked(name: String): Boolean {
        var d = name.lowercase().trimEnd('.')
        while (true) {
            if (d in DOMAINS || d in extra) return true
            val i = d.indexOf('.')
            if (i < 0) return false
            d = d.substring(i + 1)
        }
    }

    private val AD_ACTIVITY_PREFIXES = listOf(
        "com.google.android.gms.ads.",
        "com.unity3d.services.ads.", "com.unity3d.ads.",
        "com.applovin.adview.", "com.applovin.impl.adview.", "com.applovin.mediation.", "com.applovin.sdk.",
        "com.ironsource.sdk.", "com.ironsource.mediationsdk.",
        "com.vungle.", "com.adcolony.", "com.facebook.ads.",
        "com.mbridge.msdk.", "com.mintegral.",
        "com.bytedance.sdk.openadsdk.", "com.chartboost.", "com.inmobi.ads.",
        "com.yandex.mobile.ads.", "com.fyber.", "com.tapjoy.", "com.startapp.",
        "com.smaato.", "com.my.target.", "sg.bigo.ads.", "com.moloco.", "com.appnext.",
        "com.pubnative.", "net.pubnative."
    )

    fun isAdActivity(cls: String): Boolean = AD_ACTIVITY_PREFIXES.any { cls.startsWith(it) }
}
