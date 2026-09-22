package com.schedulo.shared.logic

import com.schedulo.shared.model.Job
import com.schedulo.shared.model.PayFrequency
import com.schedulo.shared.model.weekStartDayOfWeek
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * The canonical pay-cycle boundary calculation for every platform.
 *
 * A pay cycle is the period an employer pays out in one go. Its length depends on the
 * job's [PayFrequency], and its start is anchored per job — never to a locale calendar
 * week. A wrong boundary silently misstates someone's pay, so this is the single place
 * the math lives and the only place it is tested.
 *
 * All arithmetic goes through [LocalDate], never raw millisecond addition: adding
 * "14 days" across a daylight-saving change is not 14 * 86_400_000 ms, and a pay period
 * that drifts by an hour can move a midnight shift into the wrong cycle.
 */

/** A half-open pay period: [startMillis] inclusive, [endMillis] exclusive. */
data class PayCycle(
    val startMillis: Long,
    val endMillis: Long
) {
    /**
     * The last instant actually inside the cycle. Pay statements show this as the
     * cycle's end date — never the exclusive boundary, which is the next cycle's start.
     */
    val lastInstantMillis: Long get() = endMillis - 1

    /** Cycle length in whole days; 7, 14, or 28–31 depending on frequency and month. */
    fun lengthInDays(timeZone: TimeZone = TimeZone.currentSystemDefault()): Int {
        val start = Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(timeZone).date
        val end = Instant.fromEpochMilliseconds(endMillis).toLocalDateTime(timeZone).date
        return start.daysUntil(end)
    }

    fun contains(millis: Long): Boolean = millis in startMillis until endMillis
}

/**
 * The pay cycle containing [targetMillis] for [job].
 *
 * Weekly and biweekly cycles start on the job's `weeklyCycleStartDay`. Monthly cycles
 * are calendar months and ignore that day entirely — an employer paying monthly pays for
 * the month, not for a period that happens to begin on a Tuesday.
 */
fun payCycleFor(
    job: Job,
    targetMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): PayCycle {
    val date = Instant.fromEpochMilliseconds(targetMillis).toLocalDateTime(timeZone).date

    return when (PayFrequency.from(job.payFrequency)) {
        PayFrequency.MONTHLY -> {
            val first = LocalDate(date.year, date.month, 1)
            val next = first.plus(1, DateTimeUnit.MONTH)
            PayCycle(first.atStartOfDayIn(timeZone).toEpochMilliseconds(), next.atStartOfDayIn(timeZone).toEpochMilliseconds())
        }

        PayFrequency.BIWEEKLY -> {
            // Walking back to the start weekday only narrows this to "some Friday" —
            // a fortnight has two of them. The anchor says which one, so step in
            // 14-day strides from it rather than trusting the nearest weekday.
            val anchor = biweeklyAnchorDate(job, timeZone)
            var start = anchor
            while (start > date) start = start.minus(14, DateTimeUnit.DAY)
            while (start.plus(14, DateTimeUnit.DAY) <= date) start = start.plus(14, DateTimeUnit.DAY)
            val end = start.plus(14, DateTimeUnit.DAY)
            PayCycle(start.atStartOfDayIn(timeZone).toEpochMilliseconds(), end.atStartOfDayIn(timeZone).toEpochMilliseconds())
        }

        PayFrequency.WEEKLY -> {
            val start = walkBackToWeekStart(date, job.weeklyCycleStartDay)
            val end = start.plus(7, DateTimeUnit.DAY)
            PayCycle(start.atStartOfDayIn(timeZone).toEpochMilliseconds(), end.atStartOfDayIn(timeZone).toEpochMilliseconds())
        }
    }
}

/** Step back one day at a time until the weekday matches the job's cycle start day. */
private fun walkBackToWeekStart(date: LocalDate, startDayName: String?): LocalDate {
    val targetDay = weekStartDayOfWeek(startDayName)
    var cursor = date
    while (cursor.dayOfWeek != targetDay) {
        cursor = cursor.minus(1, DateTimeUnit.DAY)
    }
    return cursor
}

/**
 * The pay cycle [offset] whole periods away from the one containing [targetMillis].
 * Negative values step backwards.
 *
 * Paging must move by real periods, not by a fixed seven days: on a monthly job a
 * seven-day step lands inside the same cycle, and on a biweekly job it lands on the
 * wrong fortnight.
 */
fun payCycleAtOffset(
    job: Job,
    targetMillis: Long,
    offset: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): PayCycle {
    val current = payCycleFor(job, targetMillis, timeZone)
    if (offset == 0) return current

    val startDate = Instant.fromEpochMilliseconds(current.startMillis).toLocalDateTime(timeZone).date
    val shifted = when (PayFrequency.from(job.payFrequency)) {
        PayFrequency.MONTHLY -> startDate.plus(offset, DateTimeUnit.MONTH)
        PayFrequency.BIWEEKLY -> startDate.plus(offset * 14, DateTimeUnit.DAY)
        PayFrequency.WEEKLY -> startDate.plus(offset * 7, DateTimeUnit.DAY)
    }
    return payCycleFor(job, shifted.atStartOfDayIn(timeZone).toEpochMilliseconds(), timeZone)
}

/**
 * The reference period start for a biweekly job.
 *
 * When the job has no stored anchor — every job predating pay-frequency support — fall
 * back to the most recent cycle-start weekday on or before the epoch-aligned reference.
 * Callers that let a user pick a frequency should persist a real anchor so the choice
 * doesn't depend on when the fallback happened to be evaluated.
 */
fun biweeklyAnchorDate(job: Job, timeZone: TimeZone = TimeZone.currentSystemDefault()): LocalDate {
    val stored = job.payCycleAnchorMillis
    if (stored != null && stored > 0) {
        val anchorDate = Instant.fromEpochMilliseconds(stored).toLocalDateTime(timeZone).date
        // Normalize to the job's start weekday so a mid-period anchor still produces
        // boundaries on the right day.
        return walkBackToWeekStart(anchorDate, job.weeklyCycleStartDay)
    }
    // A fixed, timezone-independent fallback: the first matching weekday on or after the
    // Unix epoch. Deterministic, so two devices agree without having stored an anchor.
    return walkForwardToWeekStart(LocalDate(1970, 1, 1), job.weeklyCycleStartDay)
}

private fun walkForwardToWeekStart(date: LocalDate, startDayName: String?): LocalDate {
    val targetDay = weekStartDayOfWeek(startDayName)
    var cursor = date
    while (cursor.dayOfWeek != targetDay) {
        cursor = cursor.plus(1, DateTimeUnit.DAY)
    }
    return cursor
}

/**
 * The default anchor to persist when a job is switched to biweekly: the most recent
 * cycle-start weekday on or before [nowMillis]. Shown to the user as "current period
 * started …" so they can correct it if their employer is on the opposite fortnight.
 */
fun defaultBiweeklyAnchorMillis(
    weeklyCycleStartDay: String?,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): Long {
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date
    return walkBackToWeekStart(today, weeklyCycleStartDay).atStartOfDayIn(timeZone).toEpochMilliseconds()
}

/**
 * Scales a weekly target to the length of [cycle].
 *
 * `goalHours` is stored and entered as a weekly number. Comparing it directly against a
 * fortnight's or a month's totals would report roughly half or a quarter of real
 * progress, so the target is pro-rated by the cycle's actual day count — which also
 * handles months of differing length.
 */
fun proRateWeeklyGoal(
    weeklyGoal: Double,
    cycle: PayCycle,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): Double = weeklyGoal * (cycle.lengthInDays(timeZone) / 7.0)
