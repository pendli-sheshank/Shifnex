package com.schedulo.shared.logic

import com.schedulo.shared.model.Job
import com.schedulo.shared.model.PayFrequency
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PayCycleCalculatorTest {

    private val tz = TimeZone.currentSystemDefault()

    private fun millisAt(year: Int, month: Month, day: Int, hour: Int = 9): Long =
        LocalDateTime(year, month, day, hour, 0).toInstant(tz).toEpochMilliseconds()

    private fun startOfDay(year: Int, month: Month, day: Int): Long =
        LocalDate(year, month, day).atStartOfDayIn(tz).toEpochMilliseconds()

    private fun job(
        frequency: String = PayFrequency.WEEKLY_VALUE,
        startDay: String? = "Monday",
        anchor: Long? = null
    ) = Job(
        id = "j1",
        title = "Cafe",
        weeklyCycleStartDay = startDay,
        payFrequency = frequency,
        payCycleAnchorMillis = anchor
    )

    // --- PayFrequency parsing ---

    @Test
    fun unknownFrequencyFallsBackToWeekly() {
        // A payroll read must never fail on an unexpected stored value; weekly is the
        // pre-existing behaviour so it is the safe landing spot.
        assertEquals(PayFrequency.WEEKLY, PayFrequency.from(null))
        assertEquals(PayFrequency.WEEKLY, PayFrequency.from(""))
        assertEquals(PayFrequency.WEEKLY, PayFrequency.from("FORTNIGHTLY"))
    }

    @Test
    fun frequencyParsingIsCaseInsensitive() {
        assertEquals(PayFrequency.BIWEEKLY, PayFrequency.from("biweekly"))
        assertEquals(PayFrequency.MONTHLY, PayFrequency.from("Monthly"))
    }

    @Test
    fun validityMatchesTheValuesTheRulesAccept() {
        assertTrue(PayFrequency.isValid("WEEKLY"))
        assertTrue(PayFrequency.isValid("BIWEEKLY"))
        assertTrue(PayFrequency.isValid("MONTHLY"))
        assertFalse(PayFrequency.isValid("YEARLY"))
        assertFalse(PayFrequency.isValid(null))
    }

    // --- WEEKLY: must be byte-identical to the pre-existing behaviour ---

    @Test
    fun weeklyCycleAnchorsToTheJobsStartDay() {
        // Thu 2026-07-09 with Friday cycles belongs to the Fri Jul 3 – Thu Jul 9 period.
        val cycle = payCycleFor(job(startDay = "Friday"), millisAt(2026, Month.JULY, 9))
        assertEquals(startOfDay(2026, Month.JULY, 3), cycle.startMillis)
        assertEquals(startOfDay(2026, Month.JULY, 10), cycle.endMillis)
        assertEquals(7, cycle.lengthInDays(tz))
    }

    @Test
    fun weeklyCycleStartIsInclusiveAndEndIsExclusive() {
        val cycle = payCycleFor(job(startDay = "Friday"), millisAt(2026, Month.JULY, 10))
        assertTrue(cycle.contains(cycle.startMillis))
        assertFalse(cycle.contains(cycle.endMillis))
        assertTrue(cycle.contains(cycle.lastInstantMillis))
    }

    @Test
    fun weeklyCycleOnTheStartDayBeginsThatDay() {
        // A shift on the cycle start day belongs to the cycle it opens, not the prior one.
        val cycle = payCycleFor(job(startDay = "Friday"), millisAt(2026, Month.JULY, 10, hour = 0))
        assertEquals(startOfDay(2026, Month.JULY, 10), cycle.startMillis)
    }

    @Test
    fun weeklyDefaultsToMondayWhenTheDayIsMissingOrJunk() {
        val expected = startOfDay(2026, Month.JULY, 6) // Monday
        assertEquals(expected, payCycleFor(job(startDay = null), millisAt(2026, Month.JULY, 11)).startMillis)
        assertEquals(expected, payCycleFor(job(startDay = "NotADay"), millisAt(2026, Month.JULY, 11)).startMillis)
    }

    // --- BIWEEKLY ---

    @Test
    fun biweeklyCycleIsFourteenDaysLong() {
        val anchor = startOfDay(2026, Month.JULY, 3) // a Friday
        val cycle = payCycleFor(
            job(PayFrequency.BIWEEKLY_VALUE, "Friday", anchor),
            millisAt(2026, Month.JULY, 9)
        )
        assertEquals(startOfDay(2026, Month.JULY, 3), cycle.startMillis)
        assertEquals(startOfDay(2026, Month.JULY, 17), cycle.endMillis)
        assertEquals(14, cycle.lengthInDays(tz))
    }

    @Test
    fun biweeklyKeepsTheAnchorsFortnightNotTheNearestWeekday() {
        // This is the whole reason an anchor exists. Fri Jul 10 is a cycle start day,
        // but with an anchor on Jul 3 the fortnight runs Jul 3 – Jul 16, so Jul 10 is
        // mid-period. A weekday-only rule would wrongly open a new period here.
        val anchor = startOfDay(2026, Month.JULY, 3)
        val cycle = payCycleFor(
            job(PayFrequency.BIWEEKLY_VALUE, "Friday", anchor),
            millisAt(2026, Month.JULY, 10)
        )
        assertEquals(startOfDay(2026, Month.JULY, 3), cycle.startMillis)
    }

    @Test
    fun biweeklyOpensANewPeriodOnTheAlternatingWeek() {
        val anchor = startOfDay(2026, Month.JULY, 3)
        val cycle = payCycleFor(
            job(PayFrequency.BIWEEKLY_VALUE, "Friday", anchor),
            millisAt(2026, Month.JULY, 17)
        )
        assertEquals(startOfDay(2026, Month.JULY, 17), cycle.startMillis)
        assertEquals(startOfDay(2026, Month.JULY, 31), cycle.endMillis)
    }

    @Test
    fun biweeklyWorksBackwardsBeforeTheAnchor() {
        // Shifts logged before the anchor date must still land on a real boundary.
        val anchor = startOfDay(2026, Month.JULY, 3)
        val cycle = payCycleFor(
            job(PayFrequency.BIWEEKLY_VALUE, "Friday", anchor),
            millisAt(2026, Month.JUNE, 25)
        )
        assertEquals(startOfDay(2026, Month.JUNE, 19), cycle.startMillis)
        assertEquals(startOfDay(2026, Month.JULY, 3), cycle.endMillis)
    }

    @Test
    fun biweeklyCyclesTileWithoutGapsOrOverlaps() {
        // Every day across a quarter must fall in exactly one period, and consecutive
        // periods must abut exactly — a gap or overlap would lose or double-count pay.
        val theJob = job(PayFrequency.BIWEEKLY_VALUE, "Friday", startOfDay(2026, Month.JULY, 3))
        var previous: PayCycle? = null
        var day = LocalDate(2026, Month.MAY, 1)
        val last = LocalDate(2026, Month.AUGUST, 31)
        while (day <= last) {
            val millis = day.atStartOfDayIn(tz).toEpochMilliseconds()
            val cycle = payCycleFor(theJob, millis)
            assertTrue(cycle.contains(millis), "day $day not inside its own cycle")
            if (previous != null && cycle.startMillis != previous.startMillis) {
                assertEquals(previous.endMillis, cycle.startMillis, "gap or overlap before $day")
            }
            previous = cycle
            day = day.plus(1, DateTimeUnit.DAY)
        }
    }

    @Test
    fun biweeklyWithoutAnAnchorIsDeterministic() {
        // Two devices with no stored anchor must agree on the boundary, otherwise the
        // same job shows different pay periods on phone and tablet.
        val noAnchor = job(PayFrequency.BIWEEKLY_VALUE, "Friday", null)
        val first = payCycleFor(noAnchor, millisAt(2026, Month.JULY, 9))
        val second = payCycleFor(noAnchor, millisAt(2026, Month.JULY, 9))
        assertEquals(first.startMillis, second.startMillis)
        assertEquals(14, first.lengthInDays(tz))
    }

    @Test
    fun aMidPeriodAnchorIsNormalizedToTheStartDay() {
        // If a user picks a Wednesday while the job pays Friday-to-Friday, boundaries
        // must still land on Fridays rather than drifting onto Wednesdays.
        val midPeriod = startOfDay(2026, Month.JULY, 8) // a Wednesday
        val cycle = payCycleFor(
            job(PayFrequency.BIWEEKLY_VALUE, "Friday", midPeriod),
            millisAt(2026, Month.JULY, 9)
        )
        assertEquals(startOfDay(2026, Month.JULY, 3), cycle.startMillis)
    }

    @Test
    fun defaultAnchorIsTheMostRecentStartDay() {
        val now = millisAt(2026, Month.JULY, 9) // Thursday
        assertEquals(
            startOfDay(2026, Month.JULY, 3), // the Friday before
            defaultBiweeklyAnchorMillis("Friday", now, tz)
        )
    }

    // --- MONTHLY ---

    @Test
    fun monthlyCycleIsTheCalendarMonth() {
        val cycle = payCycleFor(job(PayFrequency.MONTHLY_VALUE), millisAt(2026, Month.JULY, 17))
        assertEquals(startOfDay(2026, Month.JULY, 1), cycle.startMillis)
        assertEquals(startOfDay(2026, Month.AUGUST, 1), cycle.endMillis)
        assertEquals(31, cycle.lengthInDays(tz))
    }

    @Test
    fun monthlyHandlesShortMonths() {
        val february = payCycleFor(job(PayFrequency.MONTHLY_VALUE), millisAt(2026, Month.FEBRUARY, 14))
        assertEquals(28, february.lengthInDays(tz))
        val april = payCycleFor(job(PayFrequency.MONTHLY_VALUE), millisAt(2026, Month.APRIL, 14))
        assertEquals(30, april.lengthInDays(tz))
    }

    @Test
    fun monthlySpansTheYearBoundary() {
        val cycle = payCycleFor(job(PayFrequency.MONTHLY_VALUE), millisAt(2026, Month.DECEMBER, 31, hour = 23))
        assertEquals(startOfDay(2026, Month.DECEMBER, 1), cycle.startMillis)
        assertEquals(startOfDay(2027, Month.JANUARY, 1), cycle.endMillis)
    }

    @Test
    fun monthlyIgnoresTheWeeklyStartDay() {
        // An employer paying monthly pays for the month; the weekday is irrelevant.
        val friday = payCycleFor(job(PayFrequency.MONTHLY_VALUE, "Friday"), millisAt(2026, Month.JULY, 17))
        val sunday = payCycleFor(job(PayFrequency.MONTHLY_VALUE, "Sunday"), millisAt(2026, Month.JULY, 17))
        assertEquals(friday.startMillis, sunday.startMillis)
        assertEquals(friday.endMillis, sunday.endMillis)
    }

    // --- paging by whole cycles ---

    @Test
    fun offsetZeroIsTheCurrentCycle() {
        val theJob = job(startDay = "Friday")
        val now = millisAt(2026, Month.JULY, 9)
        assertEquals(payCycleFor(theJob, now).startMillis, payCycleAtOffset(theJob, now, 0).startMillis)
    }

    @Test
    fun weeklyPagingStepsSevenDays() {
        val theJob = job(startDay = "Friday")
        val now = millisAt(2026, Month.JULY, 9)
        assertEquals(startOfDay(2026, Month.JUNE, 26), payCycleAtOffset(theJob, now, -1).startMillis)
        assertEquals(startOfDay(2026, Month.JULY, 10), payCycleAtOffset(theJob, now, 1).startMillis)
    }

    @Test
    fun biweeklyPagingStepsAFullFortnight() {
        // A seven-day step would land mid-period and show the same cycle twice.
        val theJob = job(PayFrequency.BIWEEKLY_VALUE, "Friday", startOfDay(2026, Month.JULY, 3))
        val now = millisAt(2026, Month.JULY, 9)
        assertEquals(startOfDay(2026, Month.JUNE, 19), payCycleAtOffset(theJob, now, -1).startMillis)
        assertEquals(startOfDay(2026, Month.JULY, 17), payCycleAtOffset(theJob, now, 1).startMillis)
    }

    @Test
    fun monthlyPagingStepsWholeMonths() {
        // A seven-day step would stay inside the same month entirely.
        val theJob = job(PayFrequency.MONTHLY_VALUE)
        val now = millisAt(2026, Month.JULY, 17)
        assertEquals(startOfDay(2026, Month.JUNE, 1), payCycleAtOffset(theJob, now, -1).startMillis)
        assertEquals(startOfDay(2026, Month.AUGUST, 1), payCycleAtOffset(theJob, now, 1).startMillis)
    }

    @Test
    fun monthlyPagingHandlesShortMonthsWithoutDrifting() {
        // Stepping back from the 31st must not skip a month via day clamping.
        val theJob = job(PayFrequency.MONTHLY_VALUE)
        val now = millisAt(2026, Month.MARCH, 31)
        assertEquals(startOfDay(2026, Month.FEBRUARY, 1), payCycleAtOffset(theJob, now, -1).startMillis)
        assertEquals(startOfDay(2026, Month.JANUARY, 1), payCycleAtOffset(theJob, now, -2).startMillis)
    }

    // --- goal pro-rating ---
    @Test
    fun aWeeklyGoalIsUnchangedOnAWeeklyCycle() {
        val cycle = payCycleFor(job(), millisAt(2026, Month.JULY, 9))
        assertEquals(20.0, proRateWeeklyGoal(20.0, cycle, tz), 0.001)
    }

    @Test
    fun aWeeklyGoalDoublesOnABiweeklyCycle() {
        // Without this, a fortnightly job reports half the progress actually made.
        val cycle = payCycleFor(
            job(PayFrequency.BIWEEKLY_VALUE, "Friday", startOfDay(2026, Month.JULY, 3)),
            millisAt(2026, Month.JULY, 9)
        )
        assertEquals(40.0, proRateWeeklyGoal(20.0, cycle, tz), 0.001)
    }

    @Test
    fun aWeeklyGoalScalesByRealDaysOnAMonthlyCycle() {
        // July has 31 days, so the target is 31/7 weeks' worth — not a flat 4x.
        val cycle = payCycleFor(job(PayFrequency.MONTHLY_VALUE), millisAt(2026, Month.JULY, 17))
        assertEquals(20.0 * 31.0 / 7.0, proRateWeeklyGoal(20.0, cycle, tz), 0.001)
    }
}
