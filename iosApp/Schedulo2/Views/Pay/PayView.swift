import SwiftUI

struct PayView: View {
    @EnvironmentObject var dashboardViewModel: DashboardViewModel

    @State private var selectedCycleIndex: Int?
    @State private var expandedCycleStart: Date?
    @State private var cycleToConfirmPaid: PayCycleInfo?
    @State private var showExportSheet = false
    @State private var showAdjustmentSheet = false
    @State private var adjustmentCycle: PayCycleInfo?
    @State private var adjustmentToDelete: PayAdjustment?
    @State private var optimisticallMarkedPaidCycle: String?
    @State private var showSuccessMessage = false

    private var cycles: [PayCycleInfo] {
        buildPayCycles()
    }

    private var gigShifts: [Shift] {
        dashboardViewModel.shifts.filter { $0.isGig }
    }

    private var gigTotalEarned: Double {
        gigShifts.reduce(0) { $0 + $1.totalEarned }
    }

    private var totalPaid: Double {
        dashboardViewModel.shifts.filter { $0.isPaid }.reduce(0) { $0 + $1.totalEarned }
    }

    private var totalDue: Double {
        cycles.filter { $0.status == .due }
            .flatMap { $0.shifts.filter { !$0.isPaid } }
            .reduce(0) { $0 + $1.totalEarned }
    }

    private var totalPendingHold: Double {
        cycles.filter { $0.status == .pendingHold }.reduce(0) { $0 + $1.totalEarned }
    }

    private var upcomingEarned: Double {
        cycles.filter { $0.status == .upcoming }.reduce(0) { $0 + $1.totalEarned }
    }

    var body: some View {
        ZStack(alignment: .top) {
            ScrollView {
                LazyVStack(spacing: 0) {
                    // Title
                    Text("Pay & Earnings")
                        .font(.system(size: 28, weight: .bold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.top, 16)

                Spacer().frame(height: 24)

                // Summary card
                summaryCard

                Spacer().frame(height: 24)

                // Gig earnings
                if !gigShifts.isEmpty {
                    gigEarningsSection
                    Spacer().frame(height: 24)
                }

                // Pay cycles
                if cycles.isEmpty && gigShifts.isEmpty {
                    emptyState
                }

                if !cycles.isEmpty {
                    Text("Payroll Cycles")
                        .font(.system(size: 20, weight: .bold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)

                    Spacer().frame(height: 12)

                    ForEach(cycles, id: \.cycleKey) { cycle in
                        cycleCard(cycle)
                            .padding(.horizontal, 16)
                            .padding(.bottom, 12)
                    }
                }

                // Export button
                if !dashboardViewModel.shifts.isEmpty {
                    Button(action: { showExportSheet = true }) {
                        HStack {
                            Image(systemName: "square.and.arrow.up")
                            Text("Export Report")
                                .font(.system(size: 14, weight: .semibold))
                        }
                        .foregroundColor(.primaryGreen)
                        .padding(.vertical, 12)
                        .frame(maxWidth: .infinity)
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color.primaryGreen, lineWidth: 1)
                        )
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                }

                Spacer().frame(height: 80)
                }
            }

            // Success toast notification
            if showSuccessMessage {
                HStack(spacing: 12) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 18))
                        .foregroundColor(.white)
                    Text("Payment marked as paid")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.white)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.primaryGreen)
                )
                .padding(16)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .sheet(isPresented: $showExportSheet) {
            ExportReportView()
                .environmentObject(dashboardViewModel)
        }
        .sheet(isPresented: Binding(
            get: { adjustmentCycle != nil },
            set: { if !$0 { adjustmentCycle = nil } }
        )) {
            if let cycle = adjustmentCycle {
                AddAdjustmentSheet(cycle: cycle)
                    .environmentObject(dashboardViewModel)
            }
        }
        .alert("Confirm Payment", isPresented: Binding(
            get: { cycleToConfirmPaid != nil },
            set: { if !$0 { cycleToConfirmPaid = nil } }
        )) {
            Button("Confirm") {
                if let cycle = cycleToConfirmPaid {
                    // Optimistic update
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.7)) {
                        optimisticallMarkedPaidCycle = cycle.cycleKey
                        showSuccessMessage = true
                    }
                    // Send to API
                    dashboardViewModel.markCycleAsPaid(shiftIds: cycle.shifts.map { $0.id }, isPaid: true)
                    cycleToConfirmPaid = nil

                    // Hide success message after 2 seconds
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                        withAnimation(.easeOut(duration: 0.3)) {
                            showSuccessMessage = false
                        }
                    }
                }
            }
            Button("Cancel", role: .cancel) { cycleToConfirmPaid = nil }
        } message: {
            if let cycle = cycleToConfirmPaid {
                let fmt = DateFormatter()
                let _ = (fmt.dateFormat = "MMM dd")
                Text("Mark the week of \(fmt.string(from: cycle.startDate)) - \(fmt.string(from: cycle.endDate.addingTimeInterval(-1))) ($\(String(format: "%.2f", cycle.totalEarned))) as Paid?")
            }
        }
        .alert("Delete Adjustment", isPresented: Binding(
            get: { adjustmentToDelete != nil },
            set: { if !$0 { adjustmentToDelete = nil } }
        )) {
            Button("Delete", role: .destructive) {
                if let adj = adjustmentToDelete {
                    dashboardViewModel.deletePayAdjustment(adj.id)
                    adjustmentToDelete = nil
                }
            }
            Button("Cancel", role: .cancel) { adjustmentToDelete = nil }
        } message: {
            Text("Are you sure you want to delete this adjustment?")
        }
    }

    // MARK: - Summary Card

    private var summaryCard: some View {
        VStack(spacing: 0) {
            Text("Out-of-Pocket / Due Payout")
                .font(.system(size: 14))
                .foregroundColor(.secondary)
            Text("$\(totalDue, specifier: "%.2f")")
                .font(.system(size: 42, weight: .black))
                .foregroundColor(.primaryGreen)
                .padding(.top, 4)

            Spacer().frame(height: 16)
            Divider()
            Spacer().frame(height: 16)

            HStack {
                VStack(spacing: 4) {
                    Text("Received").font(.system(size: 12, weight: .medium)).foregroundColor(.secondary)
                    Text("$\(totalPaid, specifier: "%.2f")")
                        .font(.system(size: 16, weight: .bold))
                }
                .frame(maxWidth: .infinity)
                VStack(spacing: 4) {
                    Text("On Hold").font(.system(size: 12, weight: .medium)).foregroundColor(.secondary)
                    Text("$\(totalPendingHold, specifier: "%.2f")")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.accentOrange)
                }
                .frame(maxWidth: .infinity)
                VStack(spacing: 4) {
                    Text("Upcoming").font(.system(size: 12, weight: .medium)).foregroundColor(.secondary)
                    Text("$\(upcomingEarned, specifier: "%.2f")")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.accentBlue)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(20)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.primaryGreen.opacity(0.08))
        )
        .padding(.horizontal, 16)
    }

    // MARK: - Gig Earnings

    private var gigEarningsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Gig Earnings (Direct Payout)")
                .font(.system(size: 20, weight: .bold))
                .padding(.horizontal, 16)

            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(gigShifts.count) Gig Shift\(gigShifts.count == 1 ? "" : "s")")
                        .font(.system(size: 14))
                        .foregroundColor(.secondary)
                    Text("Paid daily via direct payout")
                        .font(.system(size: 12))
                        .foregroundColor(.secondary.opacity(0.7))
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Text("$\(gigTotalEarned, specifier: "%.2f")")
                        .font(.system(size: 20, weight: .heavy))
                        .foregroundColor(.accentOrange)
                    Text("PAID")
                        .font(.system(size: 10, weight: .black))
                        .foregroundColor(.primaryGreen)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(RoundedRectangle(cornerRadius: 6).fill(Color.primaryGreen.opacity(0.12)))
                }
            }
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.accentOrange.opacity(0.08))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.accentOrange.opacity(0.3), lineWidth: 1)
                    )
            )
            .padding(.horizontal, 16)
        }
    }

    // MARK: - Cycle Card

    private func cycleCard(_ cycle: PayCycleInfo) -> some View {
        let fmt = DateFormatter()
        fmt.dateFormat = "MMM dd"
        let rangeStr = "\(fmt.string(from: cycle.startDate)) - \(fmt.string(from: cycle.endDate.addingTimeInterval(-1)))"
        let isExpanded = expandedCycleStart == cycle.startDate

        let statusColor: Color = {
            switch cycle.status {
            case .paid: return .primaryGreen
            case .due: return .primaryGreen
            case .pendingHold: return .accentOrange
            case .upcoming: return .secondary
            }
        }()

        let statusLabel: String = {
            switch cycle.status {
            case .paid: return "RECEIVED"
            case .due: return "DUE"
            case .pendingHold: return "ON HOLD"
            case .upcoming: return "ACTIVE"
            }
        }()

        // Cycle key for adjustments: employer + start date
        let cycleKeyFmt = DateFormatter()
        cycleKeyFmt.dateFormat = "yyyy-MM-dd"
        let cycleKey = "\(cycle.employer)\(cycleKeyFmt.string(from: cycle.startDate))"

        let cycleAdjustments = dashboardViewModel.getAdjustmentsForCycle(cycleKey: cycleKey)
        let adjustmentTotal = cycleAdjustments.reduce(0.0) { total, adj in
            if adj.type == "Deduction" || adj.type == "Underpaid" {
                return total - adj.amount
            } else {
                return total + adj.amount
            }
        }
        let netPay = cycle.totalEarned + adjustmentTotal

        // Payment window calculations
        let holdEnd = cycle.endDate.addingTimeInterval(4 * 24 * 60 * 60)
        let payWindowEnd = holdEnd.addingTimeInterval(7 * 24 * 60 * 60)
        let fullDateFmt = DateFormatter()
        fullDateFmt.dateFormat = "MMM dd, yyyy"
        let payWindowStartStr = fullDateFmt.string(from: holdEnd)
        let payWindowEndStr = fullDateFmt.string(from: payWindowEnd.addingTimeInterval(-1))

        return VStack(alignment: .leading, spacing: 0) {
            // Header row
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(cycle.employer)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.primaryGreen)
                    Text(rangeStr)
                        .font(.system(size: 16, weight: .bold))
                    Text("\(cycle.shifts.count) Work Shift\(cycle.shifts.count == 1 ? "" : "s")")
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Text("$\(cycle.totalEarned, specifier: "%.2f")")
                        .font(.system(size: 18, weight: .heavy))
                        .foregroundColor(.primaryGreen)
                    if adjustmentTotal != 0.0 {
                        let sign = adjustmentTotal > 0 ? "+" : ""
                        Text("\(sign)$\(String(format: "%.2f", adjustmentTotal))")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(adjustmentTotal > 0 ? .primaryGreen : .red)
                        Text("Net: $\(String(format: "%.2f", netPay))")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.secondary)
                    }
                    Text(statusLabel)
                        .font(.system(size: 10, weight: .black))
                        .foregroundColor(statusColor)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(RoundedRectangle(cornerRadius: 6).fill(statusColor.opacity(0.12)))
                }
                Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.secondary)
            }

            // Payment window period - Pending Hold
            if cycle.status == .pendingHold {
                let now = Date()
                let daysLeft = max(0, Int(holdEnd.timeIntervalSince(now) / (24 * 60 * 60)))

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Image(systemName: "clock")
                            .font(.system(size: 14))
                            .foregroundColor(.accentOrange)
                        Text("Payment Hold Active")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.accentOrange)
                    }
                    Text("Payment window: \(payWindowStartStr) - \(payWindowEndStr)")
                        .font(.system(size: 12))
                    Text(daysLeft > 0
                         ? "\(daysLeft) day\(daysLeft != 1 ? "s" : "") until payout window"
                         : "Payout window is now open")
                        .font(.system(size: 11))
                        .foregroundColor(daysLeft > 0 ? .secondary : .primaryGreen)
                        .fontWeight(daysLeft == 0 ? .medium : .regular)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Color.accentOrange.opacity(0.08))
                )
                .padding(.top, 12)
            }

            // Payment window period - Due
            if cycle.status == .due {
                HStack(spacing: 6) {
                    Image(systemName: "creditcard")
                        .font(.system(size: 14))
                        .foregroundColor(.primaryGreen)
                    Text("Payment window: \(payWindowStartStr) - \(payWindowEndStr)")
                        .font(.system(size: 12))
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Color.primaryGreen.opacity(0.06))
                )
                .padding(.top, 12)

                // Mark as paid button
                Button(action: { cycleToConfirmPaid = cycle }) {
                    Text("Mark Entire Week as Paid")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 40)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color.primaryGreen))
                }
                .padding(.top, 8)
            }

            // Adjustments summary
            if !cycleAdjustments.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Image(systemName: "doc.text")
                            .font(.system(size: 14))
                            .foregroundColor(.accentBlue)
                        Text("\(cycleAdjustments.count) Adjustment\(cycleAdjustments.count != 1 ? "s" : "")")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.accentBlue)
                    }

                    ForEach(cycleAdjustments) { adj in
                        HStack {
                            HStack(spacing: 6) {
                                Image(systemName: adjustmentTypeIcon(adj.type))
                                    .font(.system(size: 12))
                                    .foregroundColor(adjustmentTypeColor(adj.type))
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(adj.type)
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundColor(adjustmentTypeColor(adj.type))
                                    if !adj.notes.isEmpty {
                                        Text(adj.notes)
                                            .font(.system(size: 11))
                                            .foregroundColor(.secondary)
                                            .lineLimit(1)
                                    }
                                }
                            }
                            Spacer()
                            HStack(spacing: 8) {
                                let isNegative = adj.type == "Deduction" || adj.type == "Underpaid"
                                Text("\(isNegative ? "-" : "+")$\(String(format: "%.2f", adj.amount))")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(isNegative ? .red : .primaryGreen)
                                if isExpanded {
                                    Button(action: { adjustmentToDelete = adj }) {
                                        Image(systemName: "xmark")
                                            .font(.system(size: 10))
                                            .foregroundColor(.secondary.opacity(0.5))
                                    }
                                    .frame(width: 20, height: 20)
                                }
                            }
                        }
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Color.accentBlue.opacity(0.06))
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(Color.accentBlue.opacity(0.2), lineWidth: 1)
                        )
                )
                .padding(.top, 12)
            }

            // Add Adjustment button (for non-upcoming cycles)
            if cycle.status != .upcoming {
                Button(action: { adjustmentCycle = cycle }) {
                    HStack(spacing: 4) {
                        Image(systemName: "plus")
                            .font(.system(size: 14))
                            .foregroundColor(.accentBlue)
                        Text("Add Adjustment")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.accentBlue)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 36)
                    .background(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.accentBlue.opacity(0.4), lineWidth: 1)
                    )
                }
                .padding(.top, 8)
            }

            // Expanded details
            if isExpanded {
                Divider().padding(.vertical, 12)
                Text("Timesheet Details")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(.secondary)
                    .padding(.bottom, 8)

                ForEach(cycle.shifts, id: \.id) { shift in
                    shiftDetailRow(shift, cycle: cycle)
                        .padding(.vertical, 4)
                }
            } else {
                Text("Tap to view timesheet details")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary.opacity(0.8))
                    .frame(maxWidth: .infinity)
                    .padding(.top, 8)
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(UIColor.systemBackground))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(
                            cycle.status == .due ? Color.primaryGreen.opacity(0.5) :
                            cycle.status == .pendingHold ? Color.accentOrange.opacity(0.4) :
                            Color(UIColor.separator).opacity(0.3),
                            lineWidth: 1
                        )
                )
        )
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation { expandedCycleStart = isExpanded ? nil : cycle.startDate }
        }
    }

    // MARK: - Adjustment Helpers

    private func adjustmentTypeIcon(_ type: String) -> String {
        switch type {
        case "Bonus": return "arrow.up.right"
        case "Overpaid": return "arrow.up"
        case "Underpaid": return "arrow.down"
        case "Deduction": return "minus.circle"
        default: return "arrow.up.arrow.down"
        }
    }

    private func adjustmentTypeColor(_ type: String) -> Color {
        switch type {
        case "Bonus", "Overpaid": return .primaryGreen
        case "Underpaid", "Deduction": return .red
        default: return .secondary
        }
    }

    private func shiftDetailRow(_ shift: Shift, cycle: PayCycleInfo) -> some View {
        let timeFmt = DateFormatter()
        timeFmt.dateFormat = "hh:mm a"
        let dayFmt = DateFormatter()
        dayFmt.dateFormat = "EEE, MMM dd"

        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(shift.company)
                    .font(.system(size: 14, weight: .semibold))
                Text(dayFmt.string(from: shift.startDate))
                    .font(.system(size: 12))
                    .foregroundColor(.secondary.opacity(0.7))
                Text("\(timeFmt.string(from: shift.startDate)) -> \(timeFmt.string(from: shift.endDate)) - \(String(format: "%.1f", shift.durationHours)) hrs")
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text("$\(shift.totalEarned, specifier: "%.2f")")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.primaryGreen)
                if cycle.status != .upcoming {
                    Toggle("", isOn: Binding(
                        get: { shift.isPaid },
                        set: { dashboardViewModel.toggleShiftPaidStatus(shiftId: shift.id, isPaid: $0) }
                    ))
                    .labelsHidden()
                    .tint(.primaryGreen)
                }
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(Color(UIColor.secondarySystemBackground).opacity(0.3))
        )
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 8) {
            Text("No shifts reported yet.")
                .font(.system(size: 15))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(40)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(UIColor.secondarySystemBackground).opacity(0.3))
        )
        .padding(.horizontal, 16)
    }

    // MARK: - Build Pay Cycles

    private func buildPayCycles() -> [PayCycleInfo] {
        let nonGigShifts = dashboardViewModel.shifts.filter { !$0.isGig }
        let jobs = dashboardViewModel.jobs
        let now = Date()

        // Group shifts by (employer, cycleStart) so each employer gets separate cycles.
        // The period end is carried through rather than recomputed as start + 7 days,
        // which would collapse biweekly and monthly cycles back to a week.
        var cyclesMap: [String: (start: Date, end: Date, employer: String, shifts: [Shift])] = [:]

        for shift in nonGigShifts {
            let (start, end) = cycleStartAndEnd(for: shift, jobs: jobs)
            let employer = shift.company
            let key = "\(employer.lowercased())_\(start.timeIntervalSince1970)"
            if cyclesMap[key] == nil {
                cyclesMap[key] = (start: start, end: end, employer: employer, shifts: [shift])
            } else {
                cyclesMap[key]!.shifts.append(shift)
            }
        }

        let holdDays: TimeInterval = 4 * 24 * 60 * 60

        return cyclesMap.values.map { entry in
            let start = entry.start
            let shiftList = entry.shifts
            let employer = entry.employer
            let end = entry.end
            let holdEnd = end.addingTimeInterval(holdDays)

            let status: PayCycleStatus = {
                if now < end { return .upcoming }
                if now >= end && now < holdEnd { return .pendingHold }
                if !shiftList.isEmpty && shiftList.allSatisfy({ $0.isPaid }) { return .paid }
                return .due
            }()

            return PayCycleInfo(
                startDate: start,
                endDate: end,
                employer: employer,
                shifts: shiftList.sorted { $0.startTime < $1.startTime },
                totalEarned: shiftList.reduce(0) { $0 + $1.totalEarned },
                status: status
            )
        }
        .sorted { $0.startDate > $1.startDate }
    }

    // The pay period a shift belongs to, honouring the employer's pay frequency.
    // Delegates to the single boundary implementation in PayCycleCalculator.swift.
    // A shift whose employer has no matching job falls back to weekly Monday cycles,
    // as it always has.
    private func cycleStartAndEnd(for shift: Shift, jobs: [Job]) -> (Date, Date) {
        let job = jobs.first { $0.title.lowercased() == shift.company.lowercased() }
            ?? Job(weeklyCycleStartDay: "Monday")
        let cycle = payCycle(for: job, at: shift.startDate)
        return (cycle.start, cycle.end)
    }
}

// MARK: - Models

enum PayCycleStatus {
    case upcoming, pendingHold, due, paid
}

struct PayCycleInfo {
    let startDate: Date
    let endDate: Date
    let employer: String
    let shifts: [Shift]
    let totalEarned: Double
    let status: PayCycleStatus

    var cycleKey: String {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        return "\(employer)\(fmt.string(from: startDate))"
    }
}

// MARK: - Add Adjustment Sheet

struct AddAdjustmentSheet: View {
    @EnvironmentObject var dashboardViewModel: DashboardViewModel
    @Environment(\.dismiss) var dismiss

    let cycle: PayCycleInfo

    @State private var selectedType = "Bonus"
    @State private var amountText = ""
    @State private var notes = ""

    private let adjustmentTypes = ["Bonus", "Overpaid", "Underpaid", "Deduction", "Correction"]

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Adjustment Type")) {
                    Picker("Type", selection: $selectedType) {
                        ForEach(adjustmentTypes, id: \.self) { type in
                            Text(type).tag(type)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section(header: Text("Amount")) {
                    TextField("0.00", text: $amountText)
                        .keyboardType(.decimalPad)
                }

                Section(header: Text("Notes (Optional)")) {
                    TextField("Reason for adjustment", text: $notes)
                }

                Section {
                    let fmt = DateFormatter()
                    let _ = (fmt.dateFormat = "MMM dd")
                    Text("Cycle: \(cycle.employer) - \(fmt.string(from: cycle.startDate)) - \(fmt.string(from: cycle.endDate.addingTimeInterval(-1)))")
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("Add Adjustment")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard let amount = Double(amountText), amount > 0 else { return }
                        dashboardViewModel.addPayAdjustment(
                            cycleKey: cycle.cycleKey,
                            employer: cycle.employer,
                            type: selectedType,
                            amount: amount,
                            notes: notes
                        )
                        dismiss()
                    }
                    .disabled(Double(amountText) == nil || (Double(amountText) ?? 0) <= 0)
                }
            }
        }
    }
}

// MARK: - Share Sheet

struct ShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
