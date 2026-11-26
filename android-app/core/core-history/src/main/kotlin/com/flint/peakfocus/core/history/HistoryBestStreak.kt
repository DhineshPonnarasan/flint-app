package com.flint.peakfocus.core.history

import java.time.LocalDate

/**
 * Durable compaction checkpoint for the historical best streak.
 *
 * Retention drops old event rows, so the best run can otherwise shrink after a cleanup. The
 * checkpoint records what the dropped history proved — never a live counter:
 *
 *  - [bestRunDays] is the longest run ever proven by real recorded/recovered days.
 *  - [tailDays]/[tailEndDay] describe the *open* run ending on the last day before the oldest
 *    retained day, so a later contiguous day can extend it instead of restarting at zero.
 *
 * A checkpoint is only ever written by compaction, from the same recorded-zone aggregation the
 * UI reads, and is validated before any event is pruned. Reading it can therefore only restore
 * a best that the underlying history once proved.
 */
data class BestStreakCheckpoint(
    val bestRunDays: Int = 0,
    val tailDays: Int = 0,
    val tailEndDay: Long? = null,
) {
    /**
     * Best run visible with this checkpoint: the closed floor, or the open tail joined to the
     * run that starts at the oldest retained streak day.
     */
    fun bestWith(days: Set<LocalDate>, historyStart: LocalDate): Int {
        if (bestRunDays <= 0 && tailDays <= 0) return 0
        val tailEnd = tailEndDay ?: return bestRunDays
        val oldest = days.asSequence().filter { it >= historyStart }.minOrNull()
            ?: return bestRunDays
        if (tailEnd + 1 != oldest.toEpochDay()) return bestRunDays
        var head = 0
        var cursor = oldest
        while (cursor >= historyStart && cursor in days) {
            head++
            cursor = cursor.plusDays(1)
        }
        return maxOf(bestRunDays, tailDays + head)
    }
}

/** Canonical best-streak arithmetic shared by aggregation and compaction. */
object HistoryBestStreak {
    /** Upper bound used to reject a corrupt checkpoint (about two centuries of days). */
    const val MAX_PLAUSIBLE_DAYS = 200 * 366

    /** Longest consecutive run in [days] at or after [historyStart]. */
    fun bestRun(days: Set<LocalDate>, historyStart: LocalDate): Int {
        val sorted = days.asSequence().filter { it >= historyStart }.sorted().toList()
        if (sorted.isEmpty()) return 0
        var best = 1
        var run = 1
        for (index in 1 until sorted.size) {
            if (sorted[index] == sorted[index - 1].plusDays(1)) {
                run++
                best = maxOf(best, run)
            } else {
                run = 1
            }
        }
        return best
    }

    /**
     * Current streak, extended across the compaction boundary when the walk ran out of retained
     * days exactly at the checkpoint's open tail (rather than stopping at a real gap).
     */
    fun currentStreak(days: Set<LocalDate>, today: LocalDate, historyStart: LocalDate,
                      checkpoint: BestStreakCheckpoint): Int {
        var cursor = if (today in days) today else today.minusDays(1)
        var streak = 0
        while (cursor >= historyStart && cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        val tailEnd = checkpoint.tailEndDay
        if (streak > 0 && tailEnd != null && tailEnd == cursor.toEpochDay()) {
            streak += checkpoint.tailDays
        }
        return streak
    }

    /**
     * Checkpoint to persist *before* pruning, given the full day set and the oldest day that
     * survives. Days before [historyStart] never count, matching the aggregator.
     */
    fun checkpoint(previous: BestStreakCheckpoint, days: Set<LocalDate>, historyStart: LocalDate,
                   oldestRetained: LocalDate?): BestStreakCheckpoint {
        val proven = maxOf(previous.bestRunDays, bestRun(days, historyStart))
        if (oldestRetained == null) return BestStreakCheckpoint(proven, 0, null)
        // The boundary did not move (nothing was pruned): keep the existing open tail so the
        // current streak still crosses it.
        val previousEnd = previous.tailEndDay
        if (previousEnd != null && previousEnd + 1 == oldestRetained.toEpochDay()) {
            return BestStreakCheckpoint(proven, previous.tailDays, previousEnd)
        }
        val tailEnd = oldestRetained.minusDays(1)
        if (tailEnd < historyStart || tailEnd !in days) return BestStreakCheckpoint(proven, 0, null)
        var run = 0
        var cursor = tailEnd
        while (cursor >= historyStart && cursor in days) {
            run++
            cursor = cursor.minusDays(1)
        }
        // Join the previous open tail when it ends exactly where this run begins.
        val joined = if (previousEnd != null && previousEnd == cursor.toEpochDay()) {
            previous.tailDays + run
        } else {
            run
        }
        return BestStreakCheckpoint(maxOf(proven, joined), joined, tailEnd.toEpochDay())
    }

    /** A stored checkpoint is trusted only within plausible bounds. */
    fun sanitized(bestRunDays: Int, tailDays: Int, tailEndDay: Long?): BestStreakCheckpoint {
        if (bestRunDays !in 0..MAX_PLAUSIBLE_DAYS) return BestStreakCheckpoint()
        if (tailDays !in 0..MAX_PLAUSIBLE_DAYS) return BestStreakCheckpoint()
        val day = tailEndDay?.takeIf { it in -100_000L..200_000L }
        if (bestRunDays == 0 && tailDays == 0) return BestStreakCheckpoint()
        if (tailDays == 0 || day == null) return BestStreakCheckpoint(bestRunDays)
        return BestStreakCheckpoint(bestRunDays, tailDays, day)
    }
}
