package com.zephyr.browser

/**
 * Channel for "open this URL from a panel" flows (bookmarks / history →
 * MainActivity).
 *
 * The panels launch MainActivity with an ACTION_VIEW intent. With
 * launchMode="singleTask" + REORDER_TO_FRONT the delivery of onNewIntent is
 * normally guaranteed, but some OEM shells / recent-apps states still lose it
 * (user report: «в Закладках тапаю по закладке — она не открывается, раньше
 * работало»). Three independent channels now exist, in priority order:
 *
 *  1. v1.7.21 IN-PROCESS SINK (primary): MainActivity registers itself here
 *     for as long as the instance is alive. The panel hands the URL STRAIGHT
 *     into the running activity — same process, zero intent system involved
 *     in carrying the payload. The intent that follows is then only a
 *     bring-to-front signal with NO data attached, so there is nothing left
 *     to lose.
 *  2. the ACTION_VIEW intent (fallback while MainActivity is dead): consumed
 *     in onNewIntent's ACTION_VIEW branch;
 *  3. the pending record (last resort): MainActivity.onResume() finds a
 *     FRESH pending record (<8 s) and opens it anyway.
 */
object PendingOpen {
    @Volatile var url: String? = null
    @Volatile var newTab: Boolean = true
    @Volatile var at: Long = 0L

    /** v1.7.21: set by MainActivity in onCreate, cleared in onDestroy. The
     *  lambda returns true when it applied the URL (the caller then sends a
     *  payload-free bring-to-front intent) and false when the activity could
     *  not take it (caller falls back to the full ACTION_VIEW intent). */
    @Volatile var sink: ((url: String, newTab: Boolean) -> Boolean)? = null

    private const val FRESH_MS = 8000L

    fun put(url: String, newTab: Boolean) {
        this.url = url
        this.newTab = newTab
        this.at = android.os.SystemClock.uptimeMillis()
    }

    /** Returns the pending URL if fresh, and clears it. Null otherwise. */
    fun take(): Pair<String, Boolean>? {
        val u = url ?: return null
        url = null
        return if (android.os.SystemClock.uptimeMillis() - at <= FRESH_MS) u to newTab else null
    }

    /** Normal path consumed the intent — drop the backup so onResume no-ops. */
    fun clear() { url = null }

    /**
     * v1.7.21: deliver the URL directly into the live MainActivity (same
     * process — the panel and the browser always share one process). Never
     * throws: any failure inside the sink degrades to "not delivered" and the
     * caller keeps using the classic intent path.
     */
    fun deliver(url: String, newTab: Boolean): Boolean {
        val s = sink ?: return false
        return try { s(url, newTab) } catch (_: Throwable) { false }
    }
}
