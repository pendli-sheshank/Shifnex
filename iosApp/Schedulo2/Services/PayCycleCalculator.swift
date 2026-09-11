import Foundation

/// Pay-cycle boundary math for iOS.
///
/// This mirrors `shared/src/commonMain/kotlin/com/schedulo/shared/logic/PayCycleCalculator.kt`
/// case for case, and that Kotlin file's test suite is the written spec for this one —
/// iOS does not yet import the KMP framework, so the two implementations are kept in
/// deliberate lockstep rather than shared. Change both together.
///
/// All arithmetic goes through `Calendar`, never raw `TimeInterval` addition: adding
/// "14 days" across a daylight-saving change is not `14 * 86_400`, and a pay period that
/// drifts by an hour can move a midnight shift into the wrong cycle.

enum PayFrequency: String, CaseIterable {
    case weekly = "WEEKLY"
    case biweekly = "BIWEEKLY"
    case monthly = "MONTHLY"

    /// Unknown or missing values fall back to weekly rather than failing a payroll read.
    static func from(_ raw: String?) -> PayFrequency {
        guard let raw = raw, let parsed = PayFrequency(rawValue: raw.uppercased()) else {
            return .weekly
        }
        return parsed
    }

    static func isValid(_ raw: String?) -> Bool {
        guard let raw = raw else { return false }
        return PayFrequency(rawValue: raw.uppercased()) != nil
    }

    static var allValues: [String] { PayFrequency.allCases.map { $0.rawValue } }

    var label: String {
        switch self {
        case .weekly: return "Weekly"
        case .biweekly: return "Biweekly"
        case .monthly: return "Monthly"
        }
    }

    static func label(_ raw: String?) -> String { from(raw).label }
}

/// A half-open pay period: `start` inclusive, `end` exclusive.
struct PayCycle {
    let start: Date
    let end: Date

    /// The last instant actually inside the cycle. Pay statements show this as the
    /// cycle's end date — never the exclusive boundary, which is the next cycle's start.
    var lastInstant: Date { end.addingTimeInterval(-1) }

    func lengthInDays(calendar: Calendar = .current) -> Int {
        calendar.dateComponents([.day], from: start, to: end).day ?? 7
    }

    func contains(_ date: Date) -> Bool { date >= start && date < end }

    var startMillis: Int64 { Int64(start.timeIntervalSince1970 * 1000) }
    var endMillis: Int64 { Int64(end.timeIntervalSince1970 * 1000) }
}

/// Weekday number matching `Calendar`'s convention (1 = Sunday … 7 = Saturday).
func payCycleWeekdayNumber(for name: String?) -> Int {
    switch (name ?? "monday").lowercased() {
    case "sunday": return 1
    case "monday": return 2
    case "tuesday": return 3
    case "wednesday": return 4
    case "thursday": return 5
    case "friday": return 6
    case "saturday": return 7
    default: return 2
    }
}

private func walkBackToWeekStart(_ date: Date, _ startDayName: String?, _ calendar: Calendar) -> Date {
    let target = payCycleWeekdayNumber(for: startDayName)
    var cursor = calendar.startOfDay(for: date)
    while calendar.component(.weekday, from: cursor) != target {
        cursor = calendar.date(byAdding: .day, value: -1, to: cursor)!
    }
    return cursor
}

private func walkForwardToWeekStart(_ date: Date, _ startDayName: String?, _ calendar: Calendar) -> Date {
    let target = payCycleWeekdayNumber(for: startDayName)
    var cursor = calendar.startOfDay(for: date)
    while calendar.component(.weekday, from: cursor) != target {
        cursor = calendar.date(byAdding: .day, value: 1, to: cursor)!
    }
    return cursor
}

/// The reference period start for a biweekly job.
///
/// When the job has no stored anchor — every job predating pay-frequency support — fall
/// back to the first matching weekday on or after the Unix epoch. That is deterministic,
/// so two devices agree without either having stored an anchor.
func biweeklyAnchorDate(for job: Job, calendar: Calendar = .current) -> Date {
    if let stored = job.payCycleAnchorMillis, stored > 0 {
        let anchor = Date(timeIntervalSince1970: TimeInterval(stored) / 1000.0)
        // Normalize to the job's start weekday so a mid-period anchor still produces
        // boundaries on the right day.
        return walkBackToWeekStart(anchor, job.weeklyCycleStartDay, calendar)
    }
    return walkForwardToWeekStart(Date(timeIntervalSince1970: 0), job.weeklyCycleStartDay, calendar)
}

/// The pay cycle containing `date` for `job`.
func payCycle(for job: Job, at date: Date, calendar: Calendar = .current) -> PayCycle {
    let day = calendar.startOfDay(for: date)

    switch PayFrequency.from(job.payFrequency) {
    case .monthly:
        // An employer paying monthly pays for the month; the weekday is irrelevant.
        let comps = calendar.dateComponents([.year, .month], from: day)
        let first = calendar.date(from: comps)!
        let next = calendar.date(byAdding: .month, value: 1, to: first)!
        return PayCycle(start: first, end: next)

    case .biweekly:
        // Walking back to the start weekday only narrows this to "some Friday" — a
        // fortnight has two of them. The anchor says which one, so step in 14-day
        // strides from it rather than trusting the nearest weekday.
        let anchor = biweeklyAnchorDate(for: job, calendar: calendar)
        var start = anchor
        while start > day {
            start = calendar.date(byAdding: .day, value: -14, to: start)!
        }
        while let next = calendar.date(byAdding: .day, value: 14, to: start), next <= day {
            start = next
        }
        let end = calendar.date(byAdding: .day, value: 14, to: start)!
        return PayCycle(start: start, end: end)

    case .weekly:
        let start = walkBackToWeekStart(day, job.weeklyCycleStartDay, calendar)
        let end = calendar.date(byAdding: .day, value: 7, to: start)!
        return PayCycle(start: start, end: end)
    }
}

/// The pay cycle `offset` whole periods away from the one containing `date`.
///
/// Paging must move by real periods, not a fixed seven days: on a monthly job a
/// seven-day step lands inside the same cycle, and on a biweekly job it lands on the
/// wrong fortnight.
func payCycle(for job: Job, at date: Date, offset: Int, calendar: Calendar = .current) -> PayCycle {
    let current = payCycle(for: job, at: date, calendar: calendar)
    if offset == 0 { return current }

    let shifted: Date
    switch PayFrequency.from(job.payFrequency) {
    case .monthly:
        shifted = calendar.date(byAdding: .month, value: offset, to: current.start)!
    case .biweekly:
        shifted = calendar.date(byAdding: .day, value: offset * 14, to: current.start)!
    case .weekly:
        shifted = calendar.date(byAdding: .day, value: offset * 7, to: current.start)!
    }
    return payCycle(for: job, at: shifted, calendar: calendar)
}

/// The default anchor to persist when a job is switched to biweekly: the most recent
/// cycle-start weekday on or before `now`.
func defaultBiweeklyAnchorMillis(
    weeklyCycleStartDay: String?,
    now: Date = Date(),
    calendar: Calendar = .current
) -> Int64 {
    let start = walkBackToWeekStart(now, weeklyCycleStartDay, calendar)
    return Int64(start.timeIntervalSince1970 * 1000)
}

/// Scales a weekly target to the length of `cycle`.
///
/// `goalHours` is stored and entered as a weekly number. Comparing it directly against a
/// fortnight's or a month's totals would report roughly half or a quarter of real
/// progress, so the target is pro-rated by the cycle's actual day count — which also
/// handles months of differing length.
func proRateWeeklyGoal(_ weeklyGoal: Double, cycle: PayCycle, calendar: Calendar = .current) -> Double {
    weeklyGoal * (Double(cycle.lengthInDays(calendar: calendar)) / 7.0)
}
