import Foundation
import Combine
import WidgetKit

// MARK: - Supporting Types

struct WeekSummary: Identifiable {
    let id = UUID()
    let weekStart: Int64
    let label: String
    let hours: Double
    let earnings: Double
    let shiftCount: Int
}

struct PayCycleOption: Identifiable {
    let id = UUID()
    let cycleStart: Int64
    let cycleEnd: Int64
    let employer: String
    let label: String
    let shiftCount: Int
    let isCurrent: Bool
}

struct WeekDayEntry {
    let dayOffset: Int
    let startH: Int
    let startM: Int
    let endH: Int
    let endM: Int
}

// MARK: - DashboardViewModel

@MainActor
final class DashboardViewModel: ObservableObject {
    // MARK: Published state
    @Published var shifts: [Shift] = []
    @Published var jobs: [Job] = []
    @Published var userName: String = ""
    @Published var memberSince: String = ""
    @Published var isLoading: Bool = false
    @Published var syncError: String?
    @Published var themeMode: String = "system"
    @Published var remindersEnabled: Bool = true
    @Published var defaultReminderMinutes: Int = 30
    @Published var defaultCompany: String = ""
    @Published var defaultRate: Double = 0.0
    @Published var payAdjustments: [PayAdjustment] = []

    private let service = FirebaseService.shared
    private var cancellables = Set<AnyCancellable>()
    private var loadedForUserId: String?
    private var hasRescheduledReminders = false

    init() {
        updateWidgetData(shifts: [])

        // Bind Combine subjects to @Published
        service.shiftsSubject
            .receive(on: DispatchQueue.main)
            .sink { [weak self] shifts in
                guard let self = self else { return }
                self.shifts = shifts
                self.updateWidgetData(shifts: shifts)
                if !self.hasRescheduledReminders && !shifts.isEmpty {
                    self.hasRescheduledReminders = true
                    NotificationService.shared.rescheduleAllReminders(shifts: shifts)
                }
            }
            .store(in: &cancellables)

        service.jobsSubject
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.jobs = $0 }
            .store(in: &cancellables)

        service.profileSubject
            .receive(on: DispatchQueue.main)
            .sink { [weak self] profile in
                guard let self = self, let profile = profile else { return }
                self.userName = profile.fullName
                if profile.createdAt > 0 {
                    let date = Date(timeIntervalSince1970: Double(profile.createdAt) / 1000.0)
                    let fmt = DateFormatter()
                    fmt.dateFormat = "MMMM yyyy"
                    self.memberSince = fmt.string(from: date)
                }
            }
            .store(in: &cancellables)

        service.settingsSubject
            .receive(on: DispatchQueue.main)
            .sink { [weak self] settings in
                guard let self = self, let settings = settings else { return }
                self.defaultCompany = settings.defaultCompany
                self.defaultRate = settings.defaultRate
                self.themeMode = settings.themeMode
                self.remindersEnabled = settings.remindersEnabled
                self.defaultReminderMinutes = settings.defaultReminderMinutes
            }
            .store(in: &cancellables)

        service.payAdjustmentsSubject
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.payAdjustments = $0 }
            .store(in: &cancellables)
    }

    // MARK: - Data Loading

    func loadShifts() {
        guard let uid = service.currentUserId else {
            isLoading = false
            syncError = "Please sign in to access your data."
            return
        }
        guard loadedForUserId != uid else { return }
        loadedForUserId = uid
        isLoading = true
        syncError = nil
        service.startAllListeners()
        // Loading will complete when Firestore listeners fire
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.isLoading = false
        }
    }

    func loadJobs() {
        service.listenToJobs()
    }

    func loadSettings() {
        service.listenToSettings()
        service.listenToProfile()
    }

    func refreshData() {
        loadedForUserId = nil
        loadShifts()
    }

    func clearSyncError() {
        syncError = nil
    }

    func reset() {
        loadedForUserId = nil
        service.removeAllListeners()
        shifts = []
        jobs = []
        payAdjustments = []
        defaultCompany = ""
        defaultRate = 0.0
        userName = ""
        memberSince = ""
        isLoading = false
        syncError = nil
        themeMode = "system"
        updateWidgetData(shifts: [])
    }

    // MARK: - Widget Data

    private func updateWidgetData(shifts: [Shift]) {
        guard let defaults = UserDefaults(suiteName: "group.com.schedulo2.shared") else { return }
        let now = Date()
        let upcoming = shifts
            .filter { $0.startDate > now }
            .sorted { $0.startTime < $1.startTime }

        if let next = upcoming.first {
            defaults.set(next.company, forKey: "nextShiftCompany")
            defaults.set(next.role, forKey: "nextShiftRole")
            defaults.set(Int(next.startTime), forKey: "nextShiftStart")
            defaults.set(Int(next.endTime), forKey: "nextShiftEnd")
        } else {
            defaults.removeObject(forKey: "nextShiftCompany")
            defaults.removeObject(forKey: "nextShiftRole")
            defaults.set(0, forKey: "nextShiftStart")
            defaults.set(0, forKey: "nextShiftEnd")
        }

        // Widget totals use the same fiscal pay week as the app, not the
        // device locale's calendar week.
        let weekStart = startOfWeek(containing: now, weekStartDay: resolveGlobalWeekStartDay())
        let weekShifts = shifts.filter { $0.startDate >= weekStart && $0.startDate <= now.addingTimeInterval(7 * 86400) }
        defaults.set(weekShifts.reduce(0.0) { $0 + $1.totalEarned }, forKey: "weeklyEarnings")
        defaults.set(weekShifts.reduce(0.0) { $0 + $1.durationHours }, forKey: "weeklyHours")
        defaults.set(weekShifts.count, forKey: "weeklyShiftCount")
        defaults.synchronize()

        WidgetCenter.shared.reloadAllTimelines()
    }

    // MARK: - Shift CRUD

    func addShift(company: String, startTime: Int64, endTime: Int64, hourlyRate: Double, isGig: Bool, customEarned: Double, reminderBeforeMinutes: Int, notes: String = "", bonusApplied: Bool = false, bonusAmount: Double = 0.0) {
        guard let uid = service.currentUserId else {
            syncError = "Please sign in to save shifts."
            return
        }
        let shift = Shift(
            id: UUID().uuidString,
            userId: uid,
            company: company,
            role: "",
            startTime: startTime,
            endTime: endTime,
            hourlyRate: hourlyRate,
            isGig: isGig,
            customEarned: customEarned,
            reminderBeforeMinutes: reminderBeforeMinutes,
            isPaid: isGig,
            notes: notes,
            bonusApplied: bonusApplied,
            bonusAmount: bonusAmount
        )
        shifts = (shifts + [shift]).sorted { $0.startTime > $1.startTime }
        service.addShift(shift)

        if shift.reminderBeforeMinutes > 0 {
            NotificationService.shared.scheduleReminder(shift: shift)
        }

        if CalendarService.shared.calendarSyncEnabled {
            CalendarService.shared.syncShiftToCalendar(shift: shift)
        }
    }

    func updateShift(shiftId: String, company: String, startTime: Int64, endTime: Int64, hourlyRate: Double, isGig: Bool, customEarned: Double, reminderBeforeMinutes: Int, notes: String = "", bonusApplied: Bool = false, bonusAmount: Double = 0.0) {
        guard let existing = shifts.first(where: { $0.id == shiftId }) else { return }
        var updated = existing
        updated.company = company
        updated.role = ""
        updated.startTime = startTime
        updated.endTime = endTime
        updated.hourlyRate = hourlyRate
        updated.isGig = isGig
        updated.customEarned = customEarned
        updated.reminderBeforeMinutes = reminderBeforeMinutes
        updated.isPaid = isGig ? true : existing.isPaid
        updated.notes = notes
        updated.bonusApplied = bonusApplied
        updated.bonusAmount = bonusAmount

        shifts = shifts.map { $0.id == shiftId ? updated : $0 }.sorted { $0.startTime > $1.startTime }
        service.updateShift(updated)

        NotificationService.shared.cancelReminder(shiftId: shiftId)
        if updated.reminderBeforeMinutes > 0 {
            NotificationService.shared.scheduleReminder(shift: updated)
        }

        if CalendarService.shared.calendarSyncEnabled {
            CalendarService.shared.syncShiftToCalendar(shift: updated)
        }
    }

    func deleteShift(shiftId: String) {
        shifts = shifts.filter { $0.id != shiftId }
        service.deleteShift(shiftId)
        NotificationService.shared.cancelReminder(shiftId: shiftId)

        if CalendarService.shared.calendarSyncEnabled {
            CalendarService.shared.removeShiftFromCalendar(shiftId: shiftId)
        }
    }

    func toggleShiftPaidStatus(shiftId: String, isPaid: Bool) {
        shifts = shifts.map { shift in
            guard shift.id == shiftId else { return shift }
            var updated = shift
            updated.isPaid = isPaid
            return updated
        }
        service.toggleShiftPaid(shiftId, isPaid: isPaid, allShifts: shifts)
    }

    func markCycleAsPaid(shiftIds: [String], isPaid: Bool) {
        let idSet = Set(shiftIds)
        shifts = shifts.map { shift in
            guard idSet.contains(shift.id) else { return shift }
            var updated = shift
            updated.isPaid = isPaid
            return updated
        }
        service.markCycleAsPaid(shiftIds: shiftIds, isPaid: isPaid, allShifts: shifts)
    }

    // MARK: - Pay Adjustment CRUD

    func addPayAdjustment(cycleKey: String, employer: String, type: String, amount: Double, notes: String) {
        guard let uid = service.currentUserId else {
            syncError = "Please sign in to add adjustments."
            return
        }
        let adjustment = PayAdjustment(
            id: UUID().uuidString,
            userId: uid,
            cycleKey: cycleKey,
            employer: employer,
            type: type,
            amount: amount,
            notes: notes,
            createdAt: Int64(Date().timeIntervalSince1970 * 1000)
        )
        payAdjustments.append(adjustment)
        service.addPayAdjustment(adjustment)
    }

    func deletePayAdjustment(_ adjustmentId: String) {
        payAdjustments = payAdjustments.filter { $0.id != adjustmentId }
        service.deletePayAdjustment(adjustmentId)
    }

    func getAdjustmentsForCycle(cycleKey: String) -> [PayAdjustment] {
        payAdjustments.filter { $0.cycleKey == cycleKey }
    }

    // MARK: - Job CRUD

    func addJob(title: String, isGigWork: Bool, defaultHourlyRate: Double, goalHours: Double, goalType: String, weeklyCycleStartDay: String = "Monday", payFrequency: String = PayFrequency.weekly.rawValue, payCycleAnchorMillis: Int64? = nil, overtimeThresholdHours: Double = 40.0, overtimeMultiplier: Double = 1.5, bonusAmount: Double = 0.0, bonusReason: String = "") {
        guard let uid = service.currentUserId else {
            syncError = "Please sign in to add employers."
            return
        }
        let job = Job(
            id: UUID().uuidString,
            userId: uid,
            title: title,
            isGigWork: isGigWork,
            defaultHourlyRate: defaultHourlyRate,
            goalHours: goalHours,
            goalType: goalType,
            weeklyCycleStartDay: weeklyCycleStartDay,
            payFrequency: Self.normalizePayFrequency(payFrequency),
            payCycleAnchorMillis: Self.resolveAnchor(payFrequency, payCycleAnchorMillis, weeklyCycleStartDay),
            overtimeThresholdHours: overtimeThresholdHours,
            overtimeMultiplier: overtimeMultiplier,
            bonusAmount: bonusAmount,
            bonusReason: bonusReason
        )
        jobs.append(job)
        service.addJob(job)
    }

    private static func normalizePayFrequency(_ raw: String?) -> String {
        PayFrequency.isValid(raw) ? raw!.uppercased() : PayFrequency.weekly.rawValue
    }

    // Biweekly is the only frequency that needs an anchor, and it needs a real one:
    // leaving it nil would let the boundary depend on when it was first evaluated.
    // Persist the most recent cycle start day so the user can see and correct it.
    private static func resolveAnchor(_ frequency: String?, _ supplied: Int64?, _ weeklyCycleStartDay: String?) -> Int64? {
        guard PayFrequency.from(frequency) == .biweekly else { return nil }
        return supplied ?? defaultBiweeklyAnchorMillis(weeklyCycleStartDay: weeklyCycleStartDay)
    }

    func updateJob(jobId: String, title: String, isGigWork: Bool, defaultHourlyRate: Double, goalHours: Double, goalType: String, weeklyCycleStartDay: String, payFrequency: String = PayFrequency.weekly.rawValue, payCycleAnchorMillis: Int64? = nil, overtimeThresholdHours: Double = 40.0, overtimeMultiplier: Double = 1.5, bonusAmount: Double = 0.0, bonusReason: String = "") {
        guard let existing = jobs.first(where: { $0.id == jobId }) else { return }
        var updated = existing
        updated.title = title
        updated.isGigWork = isGigWork
        updated.defaultHourlyRate = defaultHourlyRate
        updated.goalHours = goalHours
        updated.goalType = goalType
        updated.weeklyCycleStartDay = weeklyCycleStartDay
        updated.payFrequency = Self.normalizePayFrequency(payFrequency)
        // Keep the job's existing anchor when one is already stored, so editing an
        // unrelated field can't silently shift every past pay period by a week.
        updated.payCycleAnchorMillis = Self.resolveAnchor(
            payFrequency,
            payCycleAnchorMillis ?? existing.payCycleAnchorMillis,
            weeklyCycleStartDay
        )
        updated.overtimeThresholdHours = overtimeThresholdHours
        updated.overtimeMultiplier = overtimeMultiplier
        updated.bonusAmount = bonusAmount
        updated.bonusReason = bonusReason

        jobs = jobs.map { $0.id == jobId ? updated : $0 }
        service.updateJob(updated)
    }

    func deleteJob(jobId: String) {
        jobs = jobs.filter { $0.id != jobId }
        service.deleteJob(jobId)
    }

    // MARK: - Conflict Detection

    func detectConflicts(startTime: Int64, endTime: Int64, excludeShiftId: String? = nil) -> [Shift] {
        shifts.filter { shift in
            shift.id != excludeShiftId &&
            shift.startTime < endTime && shift.endTime > startTime
        }
    }

    // MARK: - Overtime Calculation

    static func calculateEarningsWithOvertime(shifts: [Shift], job: Job) -> (regular: Double, overtime: Double) {
        if job.isGigWork {
            return (shifts.reduce(0) { $0 + $1.totalEarned }, 0.0)
        }
        // Each shift is priced at its own stored hourlyRate (the rate in effect
        // when it was worked) so editing the job's defaultHourlyRate never
        // re-prices past cycles. Hours past the overtime threshold are split
        // chronologically.
        let threshold = job.overtimeThresholdHours
        let multiplier = job.overtimeMultiplier
        var hoursSoFar = 0.0
        var regularEarnings = 0.0
        var overtimeEarnings = 0.0
        for shift in shifts.sorted(by: { $0.startTime < $1.startTime }) {
            let hours = shift.durationHours
            let regularPortion = min(max(threshold - hoursSoFar, 0.0), hours)
            regularEarnings += regularPortion * shift.hourlyRate
            overtimeEarnings += (hours - regularPortion) * shift.hourlyRate * multiplier
            hoursSoFar += hours
        }
        return (regularEarnings, overtimeEarnings)
    }

    // MARK: - Report Generation

    func generateFormattedReport(weekStartMillis: Int64, employer: String?) -> String {
        let weekEndMillis = weekStartMillis + 7 * 24 * 60 * 60 * 1000
        let filtered = shifts.filter { shift in
            shift.startTime >= weekStartMillis && shift.startTime < weekEndMillis &&
            (employer == nil || employer == "All" || shift.company.caseInsensitiveCompare(employer!) == .orderedSame)
        }.sorted { $0.startTime < $1.startTime }

        let weekFmt = DateFormatter(); weekFmt.dateFormat = "MMM dd"
        let dayFmt = DateFormatter(); dayFmt.dateFormat = "EEEE (M/dd)"
        let timeFmt = DateFormatter(); timeFmt.dateFormat = "h:mm a"

        var sb = ""
        let startDate = Date(timeIntervalSince1970: Double(weekStartMillis) / 1000)
        let endDate = Date(timeIntervalSince1970: Double(weekEndMillis - 1000) / 1000)
        sb += "Schedule: \(weekFmt.string(from: startDate)) – \(weekFmt.string(from: endDate))\n"
        if let emp = employer, emp != "All" { sb += "Employer: \(emp)\n" }
        sb += "\n"

        var totalHours = 0.0
        var totalEarnings = 0.0
        for shift in filtered {
            let day = dayFmt.string(from: shift.startDate)
            let start = timeFmt.string(from: shift.startDate)
            let end = timeFmt.string(from: shift.endDate)
            let hrs = shift.durationHours
            totalHours += hrs
            totalEarnings += shift.totalEarned
            sb += "\(day): \(start) – \(end) (\(String(format: "%.1f", hrs)) hrs) $\(String(format: "%.2f", shift.totalEarned))\n"
            if !shift.notes.trimmingCharacters(in: .whitespaces).isEmpty {
                sb += "  Notes: \(shift.notes)\n"
            }
        }
        sb += "\nTotal \(String(format: "%.1f", totalHours)) hours · $\(String(format: "%.2f", totalEarnings))\n"
        if filtered.contains(where: { $0.isPaid }) {
            let paidCount = filtered.filter { $0.isPaid }.count
            sb += "Paid: \(paidCount)/\(filtered.count) shifts\n"
        }
        return sb
    }

    func generateCycleReport(cycleStart: Int64, cycleEnd: Int64, employer: String, job: Job?) -> String {
        let filtered = shifts.filter { shift in
            shift.startTime >= cycleStart && shift.startTime < cycleEnd &&
            shift.company.caseInsensitiveCompare(employer) == .orderedSame
        }.sorted { $0.startTime < $1.startTime }

        let weekFmt = DateFormatter(); weekFmt.dateFormat = "MMM dd, yyyy"
        let dayFmt = DateFormatter(); dayFmt.dateFormat = "EEEE (M/dd)"
        let timeFmt = DateFormatter(); timeFmt.dateFormat = "h:mm a"

        var sb = "TIMESHEET REPORT\n"
        sb += "Employer: \(employer)\n"
        let startDate = Date(timeIntervalSince1970: Double(cycleStart) / 1000)
        let endDate = Date(timeIntervalSince1970: Double(cycleEnd - 1000) / 1000)
        sb += "Pay Period: \(weekFmt.string(from: startDate)) – \(weekFmt.string(from: endDate))\n"
        if let j = job { sb += "Cycle Start Day: \(j.weeklyCycleStartDay ?? "Monday")\n" }
        sb += String(repeating: "\u{2500}", count: 40) + "\n"

        var totalHours = 0.0
        var totalEarnings = 0.0
        for shift in filtered {
            let day = dayFmt.string(from: shift.startDate)
            let start = timeFmt.string(from: shift.startDate)
            let end = timeFmt.string(from: shift.endDate)
            let hrs = shift.durationHours
            totalHours += hrs
            totalEarnings += shift.totalEarned
            let status = shift.isPaid ? " [PAID]" : ""
            sb += "\(day): \(start) – \(end) (\(String(format: "%.1f", hrs)) hrs)\(status)\n"
            if !shift.notes.trimmingCharacters(in: .whitespaces).isEmpty {
                sb += "  Notes: \(shift.notes)\n"
            }
        }
        sb += String(repeating: "\u{2500}", count: 40) + "\n"

        if let j = job, !j.isGigWork {
            let (regular, overtime) = Self.calculateEarningsWithOvertime(shifts: filtered, job: j)
            let regularHours = min(totalHours, j.overtimeThresholdHours)
            let overtimeHours = max(totalHours - regularHours, 0.0)
            // Rates shown are derived from the shifts' stored rates, not the
            // job's current defaultHourlyRate, so historical reports stay stable.
            let regularRate = regularHours > 0 ? regular / regularHours : j.defaultHourlyRate
            sb += "Regular: \(String(format: "%.1f", regularHours)) hrs × $\(String(format: "%.2f", regularRate)) = $\(String(format: "%.2f", regular))\n"
            if overtimeHours > 0 {
                sb += "Overtime: \(String(format: "%.1f", overtimeHours)) hrs × $\(String(format: "%.2f", overtime / overtimeHours)) = $\(String(format: "%.2f", overtime))\n"
            }
            sb += "TOTAL: \(String(format: "%.1f", totalHours)) hours · $\(String(format: "%.2f", regular + overtime))\n"
        } else {
            sb += "TOTAL: \(String(format: "%.1f", totalHours)) hours · $\(String(format: "%.2f", totalEarnings))\n"
        }

        let paidCount = filtered.filter { $0.isPaid }.count
        sb += "Payment Status: \(paidCount)/\(filtered.count) shifts paid\n"
        return sb
    }

    func generateCsvReport(weekStart: Int64, employer: String) -> String {
        let weekEnd = weekStart + 7 * 24 * 60 * 60 * 1000
        let dateFmt = DateFormatter(); dateFmt.dateFormat = "yyyy-MM-dd"
        let timeFmt = DateFormatter(); timeFmt.dateFormat = "HH:mm"
        let filtered = shifts.filter { shift in
            shift.startTime >= weekStart && shift.startTime < weekEnd &&
            (employer == "All" || shift.company.caseInsensitiveCompare(employer) == .orderedSame)
        }.sorted { $0.startTime < $1.startTime }

        var sb = "Date,Company,Start,End,Hours,Rate,Earned,Gig,Paid,Notes\n"
        for s in filtered {
            let notes = s.notes.replacingOccurrences(of: ",", with: ";").replacingOccurrences(of: "\n", with: " ")
            sb += "\(dateFmt.string(from: s.startDate)),\(s.company),\(timeFmt.string(from: s.startDate)),\(timeFmt.string(from: s.endDate)),\(String(format: "%.2f", s.durationHours)),\(s.hourlyRate),\(String(format: "%.2f", s.totalEarned)),\(s.isGig),\(s.isPaid),\(notes)\n"
        }
        return sb
    }

    func generateCycleCsvReport(cycleStart: Int64, cycleEnd: Int64, employer: String, job: Job?) -> String {
        let dateFmt = DateFormatter(); dateFmt.dateFormat = "yyyy-MM-dd"
        let timeFmt = DateFormatter(); timeFmt.dateFormat = "HH:mm"
        let filtered = shifts.filter { shift in
            shift.startTime >= cycleStart && shift.startTime < cycleEnd &&
            shift.company.caseInsensitiveCompare(employer) == .orderedSame
        }.sorted { $0.startTime < $1.startTime }

        var sb = "Date,Company,Start,End,Hours,Rate,Earned,Gig,Paid,Notes\n"
        for s in filtered {
            let notes = s.notes.replacingOccurrences(of: ",", with: ";").replacingOccurrences(of: "\n", with: " ")
            sb += "\(dateFmt.string(from: s.startDate)),\(s.company),\(timeFmt.string(from: s.startDate)),\(timeFmt.string(from: s.endDate)),\(String(format: "%.2f", s.durationHours)),\(s.hourlyRate),\(String(format: "%.2f", s.totalEarned)),\(s.isGig),\(s.isPaid),\(notes)\n"
        }
        return sb
    }

    // MARK: - Available Weeks

    func getAvailableWeeks() -> [(weekStart: Int64, label: String)] {
        let calendar = Calendar.current
        let now = Date()
        let weekFmt = DateFormatter(); weekFmt.dateFormat = "MMM dd"
        var weeks = Set<Int64>()

        let anchor = startOfWeek(containing: now, weekStartDay: resolveGlobalWeekStartDay())
        for offset in -8...4 {
            guard let weekStart = calendar.date(byAdding: .weekOfYear, value: offset, to: anchor) else { continue }
            weeks.insert(Int64(calendar.startOfDay(for: weekStart).timeIntervalSince1970 * 1000))
        }

        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        return weeks.sorted().reversed().map { start in
            let end = start + 7 * 24 * 60 * 60 * 1000
            let startDate = Date(timeIntervalSince1970: Double(start) / 1000)
            let endDate = Date(timeIntervalSince1970: Double(end - 1000) / 1000)
            let isCurrent = nowMs >= start && nowMs < end
            let label = "\(weekFmt.string(from: startDate)) – \(weekFmt.string(from: endDate))" + (isCurrent ? " (Current)" : "")
            return (start, label)
        }
    }

    // MARK: - Available Pay Cycles

    func getAvailablePayCycles() -> [PayCycleOption] {
        let now = Date()
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let weekFmt = DateFormatter(); weekFmt.dateFormat = "MMM dd"

        var cycles: [PayCycleOption] = []

        for job in jobs {
            let jobShifts = shifts.filter { $0.company.caseInsensitiveCompare(job.title) == .orderedSame && !$0.isGig }
            if jobShifts.isEmpty { continue }

            var seenCycles = Set<Int64>()
            for shift in jobShifts {
                let (start, end) = getCycleStartAndEnd(forShiftStartTime: shift.startTime, jobs: jobs)
                if seenCycles.insert(start).inserted {
                    let shiftsInCycle = jobShifts.filter { $0.startTime >= start && $0.startTime < end }.count
                    let isCurrent = nowMs >= start && nowMs < end
                    let startDate = Date(timeIntervalSince1970: Double(start) / 1000)
                    let endDate = Date(timeIntervalSince1970: Double(end - 1000) / 1000)
                    let label = "\(job.title): \(weekFmt.string(from: startDate)) – \(weekFmt.string(from: endDate))" + (isCurrent ? " (Current)" : "")
                    cycles.append(PayCycleOption(cycleStart: start, cycleEnd: end, employer: job.title, label: label, shiftCount: shiftsInCycle, isCurrent: isCurrent))
                }
            }

            // Add current cycle if not seen
            let currentCycle = payCycle(for: job, at: now)
            let currentCycleStart = currentCycle.startMillis
            let currentCycleEnd = currentCycle.endMillis
            if seenCycles.insert(currentCycleStart).inserted {
                let startDate = Date(timeIntervalSince1970: Double(currentCycleStart) / 1000)
                let endDate = Date(timeIntervalSince1970: Double(currentCycleEnd - 1000) / 1000)
                let label = "\(job.title): \(weekFmt.string(from: startDate)) – \(weekFmt.string(from: endDate)) (Current)"
                cycles.append(PayCycleOption(cycleStart: currentCycleStart, cycleEnd: currentCycleEnd, employer: job.title, label: label, shiftCount: 0, isCurrent: true))
            }
        }

        return cycles.sorted { lhs, rhs in
            if lhs.cycleStart != rhs.cycleStart { return lhs.cycleStart > rhs.cycleStart }
            return lhs.employer < rhs.employer
        }
    }

    // The pay period a shift belongs to, honouring the employer's pay frequency.
    // Delegates to the single boundary implementation in PayCycleCalculator.swift.
    private func getCycleStartAndEnd(forShiftStartTime startTime: Int64, jobs: [Job]) -> (Int64, Int64) {
        let shiftDate = Date(timeIntervalSince1970: Double(startTime) / 1000)

        // Find matching job by company name (already matched externally, but look up cycle start day)
        let job = jobs.first { $0.title.caseInsensitiveCompare(
            shifts.first(where: { $0.startTime == startTime })?.company ?? "") == .orderedSame }
            ?? Job(weeklyCycleStartDay: "Monday")

        let cycle = payCycle(for: job, at: shiftDate)
        return (cycle.startMillis, cycle.endMillis)
    }

    // MARK: - Fiscal week start

    // Payroll weeks are per-job fiscal weeks (job.weeklyCycleStartDay), never
    // calendar/locale weeks. For views that aggregate across jobs, use the most
    // common weeklyCycleStartDay among non-gig jobs, ties broken by earliest
    // day (Sunday first), Monday when there are no non-gig jobs. Deterministic
    // regardless of Firestore result order.
    func resolveGlobalWeekStartDay() -> String {
        let dayOrder = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
        var counts: [String: Int] = [:]
        for job in jobs where !job.isGigWork {
            let raw = job.weeklyCycleStartDay ?? "Monday"
            let day = dayOrder.first { $0.caseInsensitiveCompare(raw) == .orderedSame } ?? "Monday"
            counts[day, default: 0] += 1
        }
        guard let maxCount = counts.values.max() else { return "Monday" }
        return dayOrder.first { counts[$0] == maxCount } ?? "Monday"
    }

    // Start-of-day of the most recent weekStartDay on or before the given date.
    // Deliberately always weekly: this backs the cross-job aggregate views (dashboard
    // "this week" card, widget, unfiltered insights), which summarise a week rather
    // than any single employer's pay period.
    func startOfWeek(containing date: Date, weekStartDay: String) -> Date {
        let weeklyJob = Job(weeklyCycleStartDay: weekStartDay, payFrequency: PayFrequency.weekly.rawValue)
        return payCycle(for: weeklyJob, at: date).start
    }

    // MARK: - Insights

    func getWeeklyEarningsSummary(weeks: Int = 8) -> [WeekSummary] {
        let calendar = Calendar.current
        let now = Date()
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let completedShifts = shifts.filter { $0.startTime < nowMs }
        let weekFmt = DateFormatter(); weekFmt.dateFormat = "MMM dd"

        // Fiscal pay weeks anchored to the jobs' weeklyCycleStartDay (e.g.
        // "Friday" = Fri–Thu cycles), not calendar weeks.
        let anchor = startOfWeek(containing: now, weekStartDay: resolveGlobalWeekStartDay())
        return (0..<weeks).map { offset in
            guard var weekStartDate = calendar.date(byAdding: .weekOfYear, value: -offset, to: anchor) else {
                return WeekSummary(weekStart: 0, label: "", hours: 0, earnings: 0, shiftCount: 0)
            }
            weekStartDate = calendar.startOfDay(for: weekStartDate)
            let weekStart = Int64(weekStartDate.timeIntervalSince1970 * 1000)
            let weekEnd = weekStart + 7 * 24 * 60 * 60 * 1000

            let weekShifts = completedShifts.filter { $0.startTime >= weekStart && $0.startTime < weekEnd }
            return WeekSummary(
                weekStart: weekStart,
                label: weekFmt.string(from: weekStartDate),
                hours: weekShifts.reduce(0) { $0 + $1.durationHours },
                earnings: weekShifts.reduce(0) { $0 + $1.totalEarned },
                shiftCount: weekShifts.count
            )
        }.reversed()
    }

    func getEarningsByEmployer() -> [(employer: String, earnings: Double)] {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let completed = shifts.filter { $0.startTime < nowMs }
        var dict: [String: Double] = [:]
        for s in completed {
            dict[s.company, default: 0] += s.totalEarned
        }
        return dict.sorted { $0.value > $1.value }.map { ($0.key, $0.value) }
    }

    // MARK: - Week Plan

    func addWeekPlanWithMinutes(company: String, hourlyRate: Double, isGig: Bool, customEarned: Double, reminderMinutes: Int, weekStartMillis: Int64, dayEntries: [WeekDayEntry]) {
        let calendar = Calendar.current
        let dateFmt = DateFormatter(); dateFmt.dateFormat = "yyyyMMdd"

        for entry in dayEntries {
            let dayMillis = weekStartMillis + Int64(entry.dayOffset) * 24 * 60 * 60 * 1000
            let dayDate = Date(timeIntervalSince1970: Double(dayMillis) / 1000)
            let dateKey = dateFmt.string(from: dayDate)

            let alreadyExists = shifts.contains { shift in
                shift.company.caseInsensitiveCompare(company) == .orderedSame &&
                dateFmt.string(from: shift.startDate) == dateKey
            }
            if alreadyExists { continue }

            var startComps = calendar.dateComponents([.year, .month, .day], from: dayDate)
            startComps.hour = entry.startH
            startComps.minute = entry.startM
            startComps.second = 0

            var endComps = calendar.dateComponents([.year, .month, .day], from: dayDate)
            endComps.hour = entry.endH
            endComps.minute = entry.endM
            endComps.second = 0

            let startDate = calendar.date(from: startComps)!
            var endDate = calendar.date(from: endComps)!
            if endDate <= startDate { endDate = endDate.addingTimeInterval(86400) }

            let startMs = Int64(startDate.timeIntervalSince1970 * 1000)
            let endMs = Int64(endDate.timeIntervalSince1970 * 1000)

            addShift(company: company, startTime: startMs, endTime: endMs, hourlyRate: hourlyRate, isGig: isGig, customEarned: customEarned, reminderBeforeMinutes: reminderMinutes)
        }
    }

    // MARK: - Settings

    func updateUserName(_ newName: String) {
        Task {
            try? await service.updateUserName(newName)
        }
    }

    func setThemeMode(_ mode: String) {
        themeMode = mode
        service.updateSettings(["themeMode": mode])
    }

    func setRemindersEnabled(_ enabled: Bool) {
        remindersEnabled = enabled
        service.updateSettings(["remindersEnabled": enabled])
    }

    func setDefaultReminderMinutes(_ minutes: Int) {
        defaultReminderMinutes = minutes
        service.updateSettings(["defaultReminderMinutes": minutes])
    }

    func saveSettings(company: String, rate: Double) {
        defaultCompany = company
        defaultRate = rate
        service.saveSettings(company: company, rate: rate)
    }
}
