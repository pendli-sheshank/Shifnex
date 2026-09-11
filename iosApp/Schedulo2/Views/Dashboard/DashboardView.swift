import SwiftUI

struct DashboardView: View {
    @EnvironmentObject var authViewModel: AuthViewModel
    @EnvironmentObject var dashboardViewModel: DashboardViewModel
    @EnvironmentObject var teamViewModel: TeamViewModel
    @StateObject private var connectivityManager = ConnectivityManager()

    var onEditShift: (String) -> Void = { _ in }
    var onNavigateToProfile: () -> Void = {}
    var onNavigateToPay: () -> Void = {}
    var onNavigateToInsights: () -> Void = {}

    @State private var weekOffset = 0
    @State private var showWeekPicker = false
    @State private var scrollOffset: CGFloat = 0
    @State private var statusBarStyle: UIStatusBarStyle = .lightContent

    private var greetingName: String {
        // Prefer the profile first name; otherwise derive a friendly name from
        // the email handle by dropping trailing digits ("sheshank336" -> "Sheshank").
        let name = dashboardViewModel.userName
        var raw: String
        if !name.isEmpty {
            raw = name.trimmingCharacters(in: .whitespaces).components(separatedBy: " ").first ?? ""
        } else {
            let prefix = authViewModel.currentUserEmail.components(separatedBy: "@").first ?? ""
            let letters = String(prefix.prefix(while: { $0.isLetter }))
            raw = letters.isEmpty ? prefix : letters
        }
        guard !raw.isEmpty else { return "there" }
        return raw.prefix(1).uppercased() + raw.dropFirst()
    }

    private var displayInitials: String {
        let name = dashboardViewModel.userName
        if !name.isEmpty {
            let parts = name.trimmingCharacters(in: .whitespaces).components(separatedBy: " ")
            if parts.count >= 2, let first = parts.first?.first, let last = parts.last?.first {
                return "\(first)\(last)".uppercased()
            }
            return String(name.prefix(2)).uppercased()
        }
        let email = authViewModel.currentUserEmail
        let prefix = email.components(separatedBy: "@").first ?? ""
        return prefix.count >= 2 ? String(prefix.prefix(2)).uppercased() : prefix.uppercased().isEmpty ? "U" : prefix.uppercased()
    }

    // Fiscal pay week (per-job weeklyCycleStartDay), not a calendar week —
    // e.g. a Friday start groups Fri–Thu into one payroll cycle.
    private func weekStart(for offset: Int) -> Date {
        let cal = Calendar.current
        let anchor = dashboardViewModel.startOfWeek(
            containing: Date(),
            weekStartDay: dashboardViewModel.resolveGlobalWeekStartDay()
        )
        return cal.date(byAdding: .weekOfYear, value: offset, to: anchor) ?? anchor
    }

    private func weekRangeLabel(for offset: Int) -> String {
        let start = weekStart(for: offset)
        let end = Calendar.current.date(byAdding: .day, value: 6, to: start) ?? start
        let fmt = DateFormatter()
        fmt.dateFormat = "MMM dd"
        return "\(fmt.string(from: start)) - \(fmt.string(from: end))"
    }

    private var weekShifts: [Shift] {
        let start = weekStart(for: weekOffset)
        let end = Calendar.current.date(byAdding: .day, value: 7, to: start) ?? start
        return dashboardViewModel.shifts.filter { $0.startDate >= start && $0.startDate < end }
    }

    private var completedWeekShifts: [Shift] {
        weekShifts.filter { $0.startDate < Date() }
    }

    var body: some View {
        ZStack(alignment: .top) {
            ScrollView {
                LazyVStack(spacing: 16) {
                    // Header + week picker as one block so the date chip reads
                    // as part of the greeting instead of floating above the card.
                    VStack(spacing: 6) {
                        headerSection
                        weekPickerSection
                    }

                    // Error banner
                    if let error = dashboardViewModel.syncError {
                        errorBanner(error)
                    }

                    // Loading with skeletal loader
                    if dashboardViewModel.isLoading {
                        SkeletalLoader()
                            .springScale()
                    } else {
                        // Earnings card (completed shifts only)
                        earningsCard

                        // Projected money from future shifts — separate card so it's
                        // never mistaken for money already earned.
                        if weekOffset == 0 {
                            upcomingEarningsCard
                        }

                        // Employer Goals
                        if !dashboardViewModel.jobs.isEmpty {
                            Text("Employer Goals")
                                .font(.system(size: 17, weight: .bold))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 16)

                            ForEach(dashboardViewModel.jobs, id: \.id) { job in
                                Button(action: onNavigateToInsights) {
                                    JobGoalTrackerCard(job: job, shifts: dashboardViewModel.shifts, weekOffset: weekOffset)
                                }
                                .buttonStyle(.plain)
                                .padding(.horizontal, 16)
                            }
                        }

                        // Upcoming shifts
                        if weekOffset == 0 {
                            upcomingShiftsSection
                        }
                    }

                    Spacer().frame(height: 80)
                }
            }
            .refreshable {
                dashboardViewModel.refreshData()
            }

            // Connectivity indicator overlay
            VStack {
                ConnectivityIndicator(connectivityManager: connectivityManager)
                Spacer()
            }
        }
    }

    // MARK: - Header

    private var headerSection: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Hi, \(greetingName)")
                    .font(.system(size: 22, weight: .bold))
                    .tracking(-0.5)

                Text(weekRangeLabel(for: weekOffset) + (weekOffset == 0 ? " - This Week" : ""))
                    .font(.system(size: 14))
                    .foregroundColor(.secondary)
            }

            Spacer()

            HStack(spacing: 4) {
                Button(action: {
                    dashboardViewModel.reset()
                    teamViewModel.removeAllListeners()
                    authViewModel.logout()
                }) {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                        .font(.system(size: 18))
                        .foregroundColor(.secondary)
                }

                Button(action: onNavigateToProfile) {
                    ZStack {
                        Circle()
                            .fill(
                                LinearGradient(
                                    colors: [.primaryGreen, .secondaryGreen],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                            .frame(width: 44, height: 44)
                        Text(displayInitials)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                    }
                }
                .scaleButtonStyle()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
    }

    // MARK: - Week Picker

    private var weekPickerSection: some View {
        Menu {
            ForEach([0, -1, -2, -3, -4], id: \.self) { offset in
                Button(action: { weekOffset = offset }) {
                    HStack {
                        Text(weekRangeLabel(for: offset) + (offset == 0 ? " (Current)" : ""))
                        if offset == weekOffset {
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "calendar")
                    .font(.system(size: 16))
                    .foregroundColor(.primaryGreen)
                Text(weekRangeLabel(for: weekOffset) + (weekOffset == 0 ? " (Current)" : ""))
                    .font(.system(size: 13, weight: .semibold))
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(UIColor.secondarySystemBackground).opacity(0.6))
            )
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Earnings Card

    private var earningsCard: some View {
        let totalEarned = completedWeekShifts.reduce(0.0) { $0 + $1.totalEarned }
        let totalHours = completedWeekShifts.reduce(0.0) { $0 + $1.durationHours }
        let shiftCount = weekShifts.count
        let avgPerShift = shiftCount > 0 ? totalEarned / Double(shiftCount) : 0.0

        return VStack(spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("This Week's Earnings")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.white.opacity(0.7))
                    Text("$\(totalEarned, specifier: "%.2f")")
                        .font(.system(size: 36, weight: .bold))
                        .foregroundColor(.white)
                        .tracking(-1)
                }
                Spacer()
                Button(action: onNavigateToPay) {
                    HStack(spacing: 4) {
                        Text("Pay Details")
                            .font(.system(size: 12, weight: .semibold))
                        Image(systemName: "arrow.right")
                            .font(.system(size: 12))
                    }
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.white.opacity(0.15))
                    )
                }
            }

            Spacer().frame(height: 16)

            HStack(spacing: 12) {
                statPill(label: "Hours", value: String(format: "%.1fh", totalHours))
                statPill(label: "Scheduled", value: "\(shiftCount)")
                statPill(label: "Avg/Shift", value: shiftCount > 0 ? String(format: "$%.2f", avgPerShift) : "$0.00")
            }
        }
        .padding(24)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(
                    LinearGradient(
                        colors: [.primaryGreen, Color(red: 0.106, green: 0.263, blue: 0.196)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
        )
        .contentShape(RoundedRectangle(cornerRadius: 20))
        .onTapGesture { onNavigateToInsights() }
        .padding(.horizontal, 16)
    }

    private var upcomingEarningsCard: some View {
        let now = Date()
        let upcoming = dashboardViewModel.shifts.filter { $0.startDate >= now }
        let projected = upcoming.reduce(0.0) { $0 + $1.totalEarned }
        let hours = upcoming.reduce(0.0) { $0 + $1.durationHours }
        let nextStart = upcoming.map(\.startDate).min()

        let nextLabel: String
        if let nextStart = nextStart {
            let fmt = DateFormatter()
            fmt.dateFormat = "EEE, MMM dd · h:mm a"
            nextLabel = "Next shift \(fmt.string(from: nextStart))"
        } else {
            nextLabel = "No upcoming shifts scheduled"
        }

        return Button(action: onNavigateToInsights) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Upcoming Earnings")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(.secondary)
                        Text("Est. $\(projected, specifier: "%.2f")")
                            .font(.system(size: 28, weight: .bold))
                            .foregroundColor(.accentBlue)
                            .tracking(-1)
                    }
                    Spacer()
                    Image(systemName: "chart.line.uptrend.xyaxis")
                        .font(.system(size: 20))
                        .foregroundColor(.accentBlue)
                }
                Text("\(upcoming.count) scheduled · \(Self.durationLabel(hours)) · \(nextLabel)")
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
            .background(
                RoundedRectangle(cornerRadius: 20)
                    .fill(Color(UIColor.systemBackground))
                    .overlay(
                        RoundedRectangle(cornerRadius: 20)
                            .stroke(Color.accentBlue.opacity(0.4), lineWidth: 1)
                    )
            )
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
    }

    private func statPill(label: String, value: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(.white)
            // Soft near-white (#E0E0E0) keeps the labels readable against the
            // dark green gradient; 0.6-alpha white failed contrast.
            Text(label)
                .font(.system(size: 11))
                .foregroundColor(Color(red: 0.878, green: 0.878, blue: 0.878))
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.white.opacity(0.1))
        )
    }

    // MARK: - Error Banner

    private func errorBanner(_ error: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundColor(.red)
                .font(.system(size: 16))
            Text(error)
                .font(.system(size: 13))
                .foregroundColor(.red)
            Spacer()
            Button(action: { dashboardViewModel.clearSyncError() }) {
                Image(systemName: "xmark")
                    .foregroundColor(.red)
                    .font(.system(size: 14))
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.red.opacity(0.1))
        )
        .padding(.horizontal, 16)
    }

    // MARK: - Upcoming Shifts

    private var upcomingShiftsSection: some View {
        let now = Date()
        let upcoming = dashboardViewModel.shifts
            .filter { $0.startDate >= now }
            .sorted { $0.startTime < $1.startTime }
            .prefix(5)

        let timeFormat: DateFormatter = {
            let f = DateFormatter()
            f.dateFormat = "EEE, MMM dd - h:mm a"
            return f
        }()

        return VStack(alignment: .leading, spacing: 12) {
            Button(action: onNavigateToInsights) {
                HStack {
                    Text("Upcoming Shifts")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(.primary)
                    Spacer()
                    Image(systemName: "chart.bar.xaxis")
                        .font(.system(size: 15))
                        .foregroundColor(.secondary)
                }
            }
            .buttonStyle(.plain)

            if upcoming.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "calendar.badge.checkmark")
                        .font(.system(size: 28))
                        .foregroundColor(.secondary.opacity(0.4))
                    Text("No upcoming shifts")
                        .font(.system(size: 14))
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(32)
                .background(
                    RoundedRectangle(cornerRadius: 16)
                        .fill(Color(UIColor.secondarySystemBackground).opacity(0.4))
                )
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(upcoming.enumerated()), id: \.element.id) { index, shift in
                        Button(action: { onEditShift(shift.id) }) {
                            HStack(spacing: 12) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 10)
                                        .fill((shift.isGig ? Color.accentOrange : Color.accentBlue).opacity(0.1))
                                        .frame(width: 44, height: 44)
                                    // Same employer iconography as the goals cards, so
                                    // one employer never shows two different symbols.
                                    Image(systemName: shift.isGig ? "car.fill" : "building.2.fill")
                                        .font(.system(size: 18))
                                        .foregroundColor(shift.isGig ? .accentOrange : .accentBlue)
                                }

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(shift.company)
                                        .font(.system(size: 14, weight: .semibold))
                                    Text(timeFormat.string(from: shift.startDate))
                                        .font(.system(size: 12))
                                        .foregroundColor(.secondary)
                                }

                                Spacer()

                                VStack(alignment: .trailing, spacing: 2) {
                                    // "Est." marks this as a projection, not money in hand.
                                    Text("Est. $\(shift.totalEarned, specifier: "%.2f")")
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundColor(.primaryGreen)
                                    Text(Self.durationLabel(shift.durationHours))
                                        .font(.system(size: 11))
                                        .foregroundColor(.secondary)
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                        }
                        .buttonStyle(.plain)

                        if index < upcoming.count - 1 {
                            Divider().padding(.horizontal, 16)
                        }
                    }
                }
                .padding(4)
                .background(
                    RoundedRectangle(cornerRadius: 16)
                        .fill(Color(UIColor.systemBackground))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(Color(UIColor.separator).opacity(0.3), lineWidth: 1)
                        )
                )
            }
        }
        .padding(.horizontal, 16)
    }

    static func durationLabel(_ hours: Double) -> String {
        hours.truncatingRemainder(dividingBy: 1) == 0
            ? "\(Int(hours))h"
            : String(format: "%.1fh", hours)
    }
}

// MARK: - Job Goal Tracker Card

struct JobGoalTrackerCard: View {
    let job: Job
    let shifts: [Shift]
    var weekOffset: Int = 0

    // Step by whole cycles, not fixed weeks, so a biweekly or monthly job pages
    // through real pay periods.
    private var cycle: PayCycle {
        payCycle(for: job, at: Date(), offset: weekOffset)
    }

    private var cycleStart: Date { cycle.start }

    private var cycleEnd: Date { cycle.end }

    private var shiftsForJob: [Shift] {
        let now = Date()
        return shifts.filter {
            $0.company.lowercased() == job.title.lowercased() &&
            $0.startDate >= cycleStart &&
            $0.startDate < cycleEnd &&
            $0.startDate < now
        }
    }

    private var hours: Double { shiftsForJob.reduce(0) { $0 + $1.durationHours } }
    private var earnings: Double { shiftsForJob.reduce(0) { $0 + $1.totalEarned } }

    private var overtimeHours: Double {
        !job.isGigWork && hours > job.overtimeThresholdHours ? hours - job.overtimeThresholdHours : 0
    }

    private var overtimeEarnings: Double {
        DashboardViewModel.calculateEarningsWithOvertime(shifts: shiftsForJob, job: job).overtime
    }

    // goalHours is entered and stored as a WEEKLY target. Comparing it straight
    // against a fortnight's or a month's totals would under-report progress by half
    // or more, so scale it to the cycle's real length.
    private var cycleGoal: Double {
        proRateWeeklyGoal(job.goalHours, cycle: cycle)
    }

    private var progressFraction: Double {
        guard cycleGoal > 0 else { return 0 }
        let actual = job.goalType == "Hours" ? hours : earnings
        return min(max(actual / cycleGoal, 0), 1)
    }

    private var accentColor: Color { job.isGigWork ? .accentOrange : .accentBlue }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 10)
                            .fill(accentColor.opacity(0.1))
                            .frame(width: 44, height: 44)
                        Image(systemName: job.isGigWork ? "car.fill" : "building.2.fill")
                            .font(.system(size: 18))
                            .foregroundColor(accentColor)
                    }
                    VStack(alignment: .leading) {
                        Text(job.title)
                            .font(.system(size: 16, weight: .bold))
                        Text(job.isGigWork ? "Gig Work" : "$\(job.defaultHourlyRate, specifier: "%.2f")/hr")
                            .font(.system(size: 12))
                            .foregroundColor(.secondary)
                    }
                }
                Spacer()
                Text("$\(earnings, specifier: "%.2f")")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(.primaryGreen)
            }

            Spacer().frame(height: 16)

            // Stats
            HStack(spacing: 24) {
                VStack(alignment: .leading) {
                    Text("Hours").font(.system(size: 11)).foregroundColor(.secondary)
                    Text(String(format: "%.1fh", hours))
                        .font(.system(size: 15, weight: .semibold))
                }
                VStack(alignment: .leading) {
                    // "Completed" (worked shifts) vs. "Scheduled" on the earnings
                    // card, so the two counts can't read as a mismatch.
                    Text("Completed").font(.system(size: 11)).foregroundColor(.secondary)
                    Text("\(shiftsForJob.count)")
                        .font(.system(size: 15, weight: .semibold))
                }
                if !job.isGigWork && overtimeHours > 0 {
                    VStack(alignment: .leading) {
                        Text("Overtime").font(.system(size: 11)).foregroundColor(.accentOrange)
                        Text(String(format: "%.1fh (+$%.0f)", overtimeHours, overtimeEarnings))
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.accentOrange)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer().frame(height: 14)

            // Progress
            HStack {
                Text("\(PayFrequency.label(job.payFrequency)) \(job.goalType) Target")
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
                Spacer()
                Text(job.goalType == "Hours"
                     ? String(format: "%.1f/%.0fh", hours, cycleGoal)
                     : String(format: "$%.0f/$%.0f", earnings, cycleGoal))
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(progressFraction >= 1.0 ? .primaryGreen : .primary)
            }

            Spacer().frame(height: 8)

            ProgressView(value: progressFraction)
                .tint(progressFraction >= 1.0 ? .primaryGreen : accentColor)
                .scaleEffect(y: 1.5)
                .padding(.bottom, 6)

            if progressFraction >= 1.0 {
                HStack(spacing: 4) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 12))
                    Text("Goal achieved!")
                        .font(.system(size: 12, weight: .semibold))
                }
                .foregroundColor(.primaryGreen)
                .padding(.top, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(UIColor.systemBackground))
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(Color(UIColor.separator).opacity(0.3), lineWidth: 1)
                )
        )
    }
}
