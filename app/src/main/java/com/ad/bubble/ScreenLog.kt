package com.ad.bubble

/** سجل آخر الشاشات اللي انفتحت (حتى نعرف اسم شاشة الإعلان بلعبة معيّنة) */
object ScreenLog {
    class Entry(val pkg: String, val cls: String, val ad: Boolean)

    private const val MAX = 150
    private val map = LinkedHashMap<String, Entry>()

    fun add(pkg: String, cls: String, ad: Boolean) {
        val key = "$pkg/$cls"
        synchronized(map) {
            map.remove(key)
            map[key] = Entry(pkg, cls, ad)
            while (map.size > MAX) map.remove(map.keys.iterator().next())
        }
    }

    fun recent(): List<Entry> = synchronized(map) { map.values.toList().asReversed() }
    fun clear() = synchronized(map) { map.clear() }
}
