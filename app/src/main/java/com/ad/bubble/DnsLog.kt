package com.ad.bubble

/** سجل آخر المواقع اللي طلبتها التطبيقات (حتى المستخدم يحجب موقع إعلان بدوسة) */
object DnsLog {
    class Entry(val domain: String, val uid: Int, @Volatile var blocked: Boolean, @Volatile var time: Long)

    private const val MAX = 400
    private val map = LinkedHashMap<String, Entry>()

    fun add(domain: String, uid: Int, blocked: Boolean) {
        val key = "$uid|$domain"
        synchronized(map) {
            val old = map.remove(key)
            val e = old ?: Entry(domain, uid, blocked, 0)
            e.blocked = blocked
            e.time = System.currentTimeMillis()
            map[key] = e
            while (map.size > MAX) {
                val first = map.keys.iterator().next()
                map.remove(first)
            }
        }
    }

    /** الأحدث أول */
    fun recent(): List<Entry> = synchronized(map) { map.values.toList().asReversed() }

    fun clear() = synchronized(map) { map.clear() }
}
