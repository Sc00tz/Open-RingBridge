package dev.ringbridge

import dev.ringbridge.db.SleepSession

/**
 * Computes a 0–100 readiness score from the latest available health data.
 *
 * Weights (Oura-inspired):
 *   HRV   40% — higher is better; scaled 20ms → 90ms
 *   Resting HR  30% — lower is better; scaled 40bpm → 100bpm
 *   Sleep 30% — combination of duration and deep sleep %
 */
object ReadinessEngine {

    data class Score(
        val total: Int,       // 0–100; -1 = not enough data
        val label: String,    // "Optimal" / "Good" / "Fair" / "Low" / "—"
    )

    fun compute(
        hrv: Double?,         // ms — latest HRV reading
        restingHr: Double?,   // bpm — lowest HR in last 24h
        sleep: SleepSession?, // last sleep session
    ): Score {
        var weightedSum = 0.0
        var totalWeight = 0.0

        // ── HRV (40%) ─────────────────────────────────────────────────────────
        if (hrv != null && hrv > 0) {
            val s = ((hrv - 20.0) / (90.0 - 20.0)).coerceIn(0.0, 1.0) * 100.0
            weightedSum += s * 0.40
            totalWeight += 0.40
        }

        // ── Resting HR (30%) ──────────────────────────────────────────────────
        if (restingHr != null && restingHr > 0) {
            val s = ((100.0 - restingHr) / (100.0 - 40.0)).coerceIn(0.0, 1.0) * 100.0
            weightedSum += s * 0.30
            totalWeight += 0.30
        }

        // ── Sleep (30%) ───────────────────────────────────────────────────────
        if (sleep != null && sleep.totalSleepMs > 0) {
            val durationH = sleep.totalSleepMs / 3_600_000.0
            val durationScore = (durationH / 7.0).coerceIn(0.0, 1.0) * 100.0
            val deepPct = sleep.deepSleepMs.toDouble() / sleep.totalSleepMs
            val deepScore = (deepPct / 0.20).coerceIn(0.0, 1.0) * 100.0
            val sleepScore = durationScore * 0.6 + deepScore * 0.4
            weightedSum += sleepScore * 0.30
            totalWeight += 0.30
        }

        if (totalWeight == 0.0) return Score(-1, "—")

        // Normalize so a missing input doesn't artificially drag the score down
        val score = (weightedSum / totalWeight).toInt().coerceIn(0, 100)

        val label = when {
            score >= 85 -> "Optimal"
            score >= 70 -> "Good"
            score >= 50 -> "Fair"
            else        -> "Low"
        }

        return Score(score, label)
    }
}
