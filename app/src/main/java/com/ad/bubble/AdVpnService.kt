package com.ad.bubble

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.system.OsConstants
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * VPN محلي (ما يطلع برا الموبايل) يمسك بس طلبات DNS:
 * إذا الطلب لشبكة إعلانات → يرد "ما موجود" فالإعلان ما يتحمّل أصلاً.
 * باقي الطلبات تنرسل للـ DNS الطبيعي. الإنترنت نفسه ما يمر من هنا.
 */
class AdVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.ad.bubble.VPN_START"
        const val ACTION_STOP = "com.ad.bubble.VPN_STOP"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 11
        private const val TUN_ADDR = "10.111.222.1"
        private const val DNS_FAKE = "10.111.222.2"
        private val FALLBACK = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

        @Volatile
        var running = false
        val blockedCount = AtomicInteger(0)
        val answeredCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)

        fun start(c: Context) {
            val i = Intent(c, AdVpnService::class.java).setAction(ACTION_START)
            c.startForegroundService(i)
        }

        fun stop(c: Context) {
            try {
                c.startService(Intent(c, AdVpnService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
            }
        }
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var out: FileOutputStream? = null
    private val writeLock = Any()
    private var pool: ExecutorService? = null
    private var reader: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification())
        if (tun == null) establish()
        return START_STICKY
    }

    override fun onRevoke() {
        shutdown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun notification(): Notification {
        Prefs.channel(this, CHANNEL, "حجب الإعلانات")
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AdVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("حجب الإعلانات شغّال 🚫")
            .setContentText("الإعلانات ما تتحمّل بالألعاب والتطبيقات")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "إيقاف", stop).build())
            .build()
    }

    private fun establish() {
        Blocklist.load(this)
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress(TUN_ADDR, 32)
            .addRoute(DNS_FAKE, 32)
            .addDnsServer(DNS_FAKE)
        if (Build.VERSION.SDK_INT >= 29) {
            b.setBlocking(true)
            b.setMetered(false)
        }
        for (p in Prefs.excluded(this)) {
            try { b.addDisallowedApplication(p) } catch (_: Exception) { }
        }
        try { b.addDisallowedApplication(packageName) } catch (_: Exception) { }

        val fd = try { b.establish() } catch (e: Exception) { null }
        if (fd == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        tun = fd
        out = FileOutputStream(fd.fileDescriptor)
        pool = Executors.newFixedThreadPool(16)
        dnsCacheAt = 0L
        running = true
        reader = Thread({ loop(fd) }, "dns-tun").also { it.start() }
    }

    private fun shutdown() {
        running = false
        val t = tun
        tun = null
        out = null
        try { t?.close() } catch (_: Exception) { }
        pool?.shutdownNow()
        pool = null
        reader = null
    }

    // ---------------- قراءة الحزم ----------------
    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running && tun === fd) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                if (!running || tun !== fd) break
                sleepQuiet()
                continue
            }
            if (n <= 0) {
                sleepQuiet()
                continue
            }
            try {
                handle(buf.copyOf(n))
            } catch (_: Exception) {
            }
        }
    }

    private fun sleepQuiet() {
        try { Thread.sleep(20) } catch (_: InterruptedException) { }
    }

    private fun handle(p: ByteArray) {
        if (p.size < 28) return
        if ((p[0].toInt() shr 4) != 4) return
        val ihl = (p[0].toInt() and 0x0f) * 4
        if (p[9].toInt() != 17) return // UDP فقط
        val total = minOf(u16(p, 2), p.size)
        if (total < ihl + 8) return
        val sport = u16(p, ihl)
        val dport = u16(p, ihl + 2)
        if (dport != 53) return
        val client = p.copyOfRange(12, 16)
        val server = p.copyOfRange(16, 20)
        val dns = p.copyOfRange(ihl + 8, total)
        if (dns.size < 12) return

        val q = question(dns)
        if (q != null && Blocklist.isBlocked(q.first)) {
            blockedCount.incrementAndGet()
            write(ipUdp(server, client, 53, sport, blockedReply(dns, q.second)))
            logQuery(q.first, client, server, sport, true)
            return
        }
        pool?.execute {
            if (q != null) logQuery(q.first, client, server, sport, false)
            forward(dns, client, server, sport)
        }
    }

    private fun logQuery(domain: String, client: ByteArray, server: ByteArray, sport: Int, blocked: Boolean) {
        val task = Runnable {
            var uid = -1
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val cm = getSystemService(ConnectivityManager::class.java)
                    uid = cm.getConnectionOwnerUid(
                        OsConstants.IPPROTO_UDP,
                        InetSocketAddress(InetAddress.getByAddress(client), sport),
                        InetSocketAddress(InetAddress.getByAddress(server), 53)
                    )
                } catch (_: Exception) {
                }
            }
            DnsLog.add(domain.lowercase(), uid, blocked)
        }
        if (blocked) {
            try { pool?.execute(task) } catch (_: Exception) { }
        } else {
            task.run()
        }
    }

    /** يرجّع اسم الدومين ونهاية قسم السؤال */
    private fun question(d: ByteArray): Pair<String, Int>? {
        var i = 12
        val sb = StringBuilder()
        while (i < d.size) {
            val len = d[i].toInt() and 0xff
            if (len == 0) { i++; break }
            if (len and 0xC0 != 0) return null
            if (i + 1 + len > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, i + 1, len, Charsets.ISO_8859_1))
            i += 1 + len
        }
        if (i + 4 > d.size) return null
        return sb.toString() to (i + 4)
    }

    /** رد NXDOMAIN (الدومين ما موجود) */
    private fun blockedReply(d: ByteArray, qEnd: Int): ByteArray {
        val r = d.copyOf(qEnd)
        r[2] = (0x80 or (d[2].toInt() and 0x01)).toByte()
        r[3] = 0x83.toByte()
        r[4] = 0; r[5] = 1
        for (k in 6..11) r[k] = 0
        return r
    }

    // ---------------- إرسال الطلب للـ DNS الحقيقي ----------------
    @Volatile private var dnsCache: List<InetAddress> = emptyList()
    @Volatile private var dnsCacheAt = 0L
    @Volatile private var dnsNet: Network? = null

    /** سيرفرات DNS مال الشبكة الحقيقية (واي فاي أو بيانات) + احتياط */
    private fun upstreams(): List<InetAddress> {
        val now = SystemClock.elapsedRealtime()
        if (now - dnsCacheAt < 10_000 && dnsCache.isNotEmpty()) return dnsCache
        val list = ArrayList<InetAddress>()
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            // تطبيقنا مستثنى من الـ VPN، فهنا ترجع الشبكة الحقيقية
            var net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) net = null
            dnsNet = net
            val lp = net?.let { cm.getLinkProperties(it) }
            lp?.dnsServers?.forEach { a ->
                val h = a.hostAddress ?: ""
                if (!h.startsWith("10.111.222.")) list.add(a)
            }
        } catch (_: Exception) {
        }
        for (f in FALLBACK) {
            try { list.add(InetAddress.getByName(f)) } catch (_: Exception) { }
        }
        dnsCache = list.distinct()
        dnsCacheAt = now
        return dnsCache
    }

    private fun forward(q: ByteArray, client: ByteArray, server: ByteArray, cport: Int) {
        val ups = upstreams()
        // UDP: نجرب أول 3 سيرفرات بوقت قصير
        for ((i, up) in ups.take(4).withIndex()) {
            val r = udpQuery(q, up, if (i == 0) 2500 else 2000)
            if (r != null) {
                answeredCount.incrementAndGet()
                write(ipUdp(server, client, 53, cport, r))
                return
            }
        }
        // احتياط: DNS عبر TCP (إذا الـ UDP مسدود)
        for (up in ups.take(3)) {
            val r = tcpQuery(q, up)
            if (r != null) {
                answeredCount.incrementAndGet()
                write(ipUdp(server, client, 53, cport, r))
                return
            }
        }
        failedCount.incrementAndGet()
        dnsCacheAt = 0L
        // نرد SERVFAIL بسرعة بدل ما التطبيق يبقى ينتظر
        write(ipUdp(server, client, 53, cport, servfail(q)))
    }

    private fun udpQuery(q: ByteArray, up: InetAddress, timeout: Int): ByteArray? = try {
        DatagramSocket().use { s ->
            protect(s)
            try { dnsNet?.bindSocket(s) } catch (_: Exception) { }
            s.soTimeout = timeout
            s.send(DatagramPacket(q, q.size, up, 53))
            val rb = ByteArray(4096)
            val rp = DatagramPacket(rb, rb.size)
            s.receive(rp)
            if (rp.length >= 12 && rb[0] == q[0] && rb[1] == q[1]) rb.copyOf(rp.length) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun tcpQuery(q: ByteArray, up: InetAddress): ByteArray? = try {
        Socket().use { s ->
            protect(s)
            try { dnsNet?.bindSocket(s) } catch (_: Exception) { }
            s.connect(InetSocketAddress(up, 53), 3000)
            s.soTimeout = 3000
            val o = s.getOutputStream()
            o.write(byteArrayOf((q.size shr 8).toByte(), q.size.toByte()))
            o.write(q)
            o.flush()
            val inp = DataInputStream(s.getInputStream())
            val len = inp.readUnsignedShort()
            val r = ByteArray(len)
            inp.readFully(r)
            if (len >= 12) r else null
        }
    } catch (_: Exception) {
        null
    }

    private fun servfail(q: ByteArray): ByteArray {
        val end = question(q)?.second ?: 12
        val r = q.copyOf(end)
        r[2] = (0x80 or (q[2].toInt() and 0x01)).toByte()
        r[3] = 0x82.toByte()
        if (end == 12) { r[4] = 0; r[5] = 0 } else { r[4] = 0; r[5] = 1 }
        for (k in 6..11) r[k] = 0
        return r
    }

    private fun write(pkt: ByteArray) {
        synchronized(writeLock) {
            try { out?.write(pkt) } catch (_: Exception) { }
        }
    }

    // ---------------- بناء حزمة IPv4/UDP ----------------
    private fun ipUdp(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
        val len = 28 + payload.size
        val p = ByteArray(len)
        p[0] = 0x45
        put16(p, 2, len)
        p[6] = 0x40
        p[8] = 64
        p[9] = 17
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, sport)
        put16(p, 22, dport)
        put16(p, 24, 8 + payload.size)
        // checksum الـ UDP = 0 (مسموح بـ IPv4)
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = v.toByte()
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len) {
            sum += u16(b, i)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum.inv() and 0xffff).toInt()
    }
}
