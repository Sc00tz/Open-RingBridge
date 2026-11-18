package io.github.sc00tz.ringbridge.data

import kotlin.math.max
import kotlin.math.min

/**
 * Finding readings the ring stamped with a late clock. A port of core/clockfix.py.
 *
 * When the ring's battery runs flat its clock stops, and after charging it carries on from where it stopped,
 * so later records are stamped too early until a sync sets the clock. The giveaway: right after a sync sets the
 * clock the ring keeps recording, but the records before the sync ended long ago. A ring taken off and put back
 * on just before syncing looks the same, so a found fix is only a proposal until the person confirms it.
 */
object ClockFix {
    const val MIN_LAG_SECONDS = 30 * 60L
    const val FOLLOW_SECONDS = 7 * 60L
    const val CHARGE_GAP_SECONDS = 45 * 60L
    const val BIN_SECONDS = 30 * 60L
    const val DEFAULT_CADENCE = 300.0

    data class Event(val setTs: Long, val newestBefore: Long?)
    data class Fix(val fromTs: Long, val toTs: Long, val offset: Long, val firstAfter: Long, val chargeGap: Pair<Long, Long>?)

    fun coverage(sampleTs: List<Long>, binStarts: List<Long>): List<Pair<Long, Long>> =
        (sampleTs.map { it to it } + binStarts.map { it to it + BIN_SECONDS }).sortedWith(compareBy({ it.first }, { it.second }))

    /** The longest stretch with nothing recorded between low and high. */
    fun longestGap(spans: List<Pair<Long, Long>>, low: Long, high: Long): Pair<Long, Long>? {
        var best: Pair<Long, Long>? = null
        var reach: Long? = null
        for ((start, end) in spans) {
            if (end <= low || start > high) continue
            val r = reach
            if (r != null && start - r >= CHARGE_GAP_SECONDS && (best == null || start - r > best.second - best.first)) best = r to start
            reach = if (r == null) end else max(r, end)
        }
        return best
    }

    fun cadence(sampleTs: List<Long>, before: Long): Double {
        val recent = sampleTs.filter { it in (before - 7200)..before }.sorted()
        val gaps = recent.zipWithNext { a, b -> (b - a).toDouble() }.filter { it > 0 && it <= 600 }
        return if (gaps.size >= 3) Derived.median(gaps) else DEFAULT_CADENCE
    }

    fun detect(event: Event, previousSetTs: Long?, sampleTs: List<Long>, binStarts: List<Long>): Fix? {
        val newest = event.newestBefore ?: return null
        if (previousSetTs == null || newest <= previousSetTs) return null
        if (event.setTs - newest < MIN_LAG_SECONDS) return null
        val firstAfter = sampleTs.filter { it in event.setTs..(event.setTs + FOLLOW_SECONDS) }.minOrNull() ?: return null
        // The record after `newest` came one cadence later in real time, and the ring's clock can't be ahead of now.
        val raw = min(firstAfter - newest - cadence(sampleTs, newest), (event.setTs - newest).toDouble())
        val offset = Derived.pyRound(raw / 60).toLong() * 60
        if (offset < MIN_LAG_SECONDS || offset >= event.setTs - previousSetTs) return null
        val gap = longestGap(coverage(sampleTs, binStarts), previousSetTs, newest)
        val fromTs = (gap?.first ?: previousSetTs) + 1  // the last record before the gap was stamped correctly
        return Fix(fromTs, newest, offset, firstAfter, gap)
    }

    fun offsetFor(ts: Long, fixes: List<StoredFix>): Long = fixes.firstOrNull { ts in it.fromTs..it.toTs }?.offset ?: 0L

    data class StoredFix(val id: Long, val eventTs: Long, val fromTs: Long, val toTs: Long, val offset: Long, val status: String)
}
