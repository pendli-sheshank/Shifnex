package com.schedulo.shared.model

import kotlinx.datetime.*

// Maps a stored week-start day name ("Friday") to its DayOfWeek anchor.
// Payroll weeks are per-job fiscal weeks, not calendar weeks — always anchor
// week math to this instead of hardcoding Monday.
fun weekStartDayOfWeek(name: String?): DayOfWeek = when (name?.lowercase() ?: "monday") {
    "sunday" -> DayOfWeek.SUNDAY
    "monday" -> DayOfWeek.MONDAY
    "tuesday" -> DayOfWeek.TUESDAY
    "wednesday" -> DayOfWeek.WEDNESDAY
    "thursday" -> DayOfWeek.THURSDAY
    "friday" -> DayOfWeek.FRIDAY
    "saturday" -> DayOfWeek.SATURDAY
    else -> DayOfWeek.MONDAY
}

// Week-start day for views that aggregate across jobs (dashboard week card,
// widgets, unfiltered insights): the most common weeklyCycleStartDay among
// non-gig jobs, ties broken by earliest day (Sunday first), Monday when there
// are no non-gig jobs. Deterministic regardless of Firestore result order.
fun resolveGlobalWeekStartDay(jobs: List<Job>): String {
    val dayOrder = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    val counts = jobs.asSequence()
        .filter { !it.isGigWork }
        .map { job ->
            dayOrder.firstOrNull { it.equals(job.weeklyCycleStartDay ?: "Monday", ignoreCase = true) } ?: "Monday"
        }
        .groupingBy { it }
        .eachCount()
    if (counts.isEmpty()) return "Monday"
    val maxCount = counts.values.max()
    return dayOrder.first { counts[it] == maxCount }
}

/**
 * How often an employer pays out.
 *
 * Stored as a plain string on [Job] to match the other Firestore-backed fields, with
 * the enum used only in logic. `WEEKLY` is the default so every job created before pay
 * frequency existed keeps exactly the cycle boundaries it already had.
 */
enum class PayFrequency {
    WEEKLY,
    BIWEEKLY,
    MONTHLY;

    companion object {
        const val WEEKLY_VALUE = "WEEKLY"
        const val BIWEEKLY_VALUE = "BIWEEKLY"
        const val MONTHLY_VALUE = "MONTHLY"

        val ALL_VALUES = listOf(WEEKLY_VALUE, BIWEEKLY_VALUE, MONTHLY_VALUE)

        /** Unknown or missing values fall back to weekly rather than failing a payroll read. */
        fun from(raw: String?): PayFrequency = when (raw?.uppercase()) {
            BIWEEKLY_VALUE -> BIWEEKLY
            MONTHLY_VALUE -> MONTHLY
            else -> WEEKLY
        }

        fun isValid(raw: String?): Boolean = raw != null && raw.uppercase() in ALL_VALUES

        /** User-facing label for the pay-frequency picker on both platforms. */
        fun label(raw: String?): String = when (from(raw)) {
            WEEKLY -> "Weekly"
            BIWEEKLY -> "Biweekly"
            MONTHLY -> "Monthly"
        }
    }
}

data class Job(
    var id: String = "",
    var userId: String = "",
    var title: String = "",
    var isGigWork: Boolean = false,
    var defaultHourlyRate: Double = 15.0,
    var goalHours: Double = 20.0,
    var goalType: String = "Hours",
    var weeklyCycleStartDay: String? = "Monday",
    // How long a pay period is. Weekly by default so existing jobs are unaffected.
    var payFrequency: String = PayFrequency.WEEKLY_VALUE,
    // Start of one known pay period, used only by BIWEEKLY to decide which of the two
    // alternating weeks begins a period. Null on every job predating this field.
    var payCycleAnchorMillis: Long? = null,
    var overtimeThresholdHours: Double = 40.0,
    var overtimeMultiplier: Double = 1.5,
    var bonusAmount: Double = 0.0,
    var bonusReason: String = ""
) {
    fun getStartOfCurrentCycle(targetMillis: Long = Clock.System.now().toEpochMilliseconds()): Long {
        val instant = Instant.fromEpochMilliseconds(targetMillis)
        var date = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date

        val targetDay = weekStartDayOfWeek(weeklyCycleStartDay)

        while (date.dayOfWeek != targetDay) {
            date = date.minus(1, DateTimeUnit.DAY)
        }

        val startOfDay = date.atStartOfDayIn(TimeZone.currentSystemDefault())
        return startOfDay.toEpochMilliseconds()
    }
}
