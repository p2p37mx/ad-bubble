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
        "mopub.com", "adform.net", "smartadserver.com", "yieldmo.com", "verve.com", "pubnative.net",
        // إضافات: شبكات ألعاب وقياس إعلانات
        "mtgglobals.com", "liftoff.io", "vungle.io", "ironsrc.net", "kayzen.io", "aarki.net",
        "digitalturbine.com", "adtimaserver.vn", "admost.com", "yandexadexchange.com",
        "moatads.com", "doubleverify.com", "adsafeprotected.com", "googletagservices.com",
        "adsappier.com", "appodeal.com", "appodealx.com", "bidmachine.io", "mobfox.com",
        "adtiming.com", "toponad.com", "anythinktech.com", "tradplusad.com", "ad-brix.com",
        "mobvista.com", "rayjump.com", "hyprmx.com", "ogury.co", "presage.io", "verve.io",
        "adsymptotic.com", "unrulymedia.com", "adswizz.com", "spotxchange.com", "springserve.com",
        "applovin.net", "bigossp.com", "pangle-ads.com", "inmobicdn.net", "adcolony.io",
        // وساطة إعلانات Unity LevelPlay و Moloco (شفناها بلعبة Save the Doge)
        "mediation.unity3d.com", "moloco.com", "ads.unity3d.com", "ads-api.unity3d.com"
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
        "com.pubnative.", "net.pubnative.",
        "com.unity3d.mediation.", "com.ironsource.", "com.applovin.", "com.mbridge.",
        "com.bytedance.sdk.", "com.pangle.", "com.liftoff.", "com.digitalturbine.",
        "com.appodeal.", "com.bidmachine.", "io.bidmachine.", "com.hyprmx.", "com.ogury.",
        "com.verve.", "com.kidoz.", "com.adjoe.", "com.google.android.gms.ads"
    )

    private val AD_WORDS = listOf(
        "adactivity", "adsactivity", "interstitial", "rewarded", "fullscreenad", "adunit",
        "advideo", "videoad", "adview", "adplayer", "playableactivity", "adwebview",
        "landingpage", "endcard", "mraid", "vast"
    )

    @Volatile
    private var customScreens: Set<String> = emptySet()

    fun loadScreens(c: Context) {
        customScreens = Prefs.adScreens(c)
    }

    /** هل هاي الشاشة مال إعلان؟ pkg = اسم حزمة اللعبة */
    fun isAdActivity(pkg: String, cls: String): Boolean {
        if (cls in customScreens) return true
        if (AD_ACTIVITY_PREFIXES.any { cls.startsWith(it) }) return true
        // كلاس من مكتبة خارجية (مو من كود اللعبة نفسها) واسمه يدل على إعلان
        val gameRoot = pkg.split('.').take(2).joinToString(".")
        if (cls.startsWith(gameRoot)) return false
        if (cls.startsWith("android.") || cls.startsWith("com.android.")) return false
        val low = cls.lowercase()
        if (low.contains(".ads.") || low.contains(".ad.")) return true
        val simple = low.substringAfterLast('.')
        return AD_WORDS.any { simple.contains(it) }
    }
}
