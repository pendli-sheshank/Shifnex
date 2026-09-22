import SwiftUI

struct JobsView: View {
    @EnvironmentObject var dashboardViewModel: DashboardViewModel

    @State private var showDialog = false
    @State private var editingJobId: String?
    @State private var title = ""
    @State private var isGigWork = false
    @State private var rateStr = "15.0"
    @State private var goalHoursStr = "20.0"
    @State private var goalType = "Hours"
    @State private var weeklyCycleStartDay = "Monday"
    @State private var payFrequency = PayFrequency.weekly.rawValue
    @State private var payCycleAnchorMillis: Int64? = nil
    @State private var overtimeThresholdStr = "40.0"
    @State private var overtimeMultiplierStr = "1.5"
    @State private var bonusAmountStr = "0.0"
    @State private var bonusReason = ""
    @State private var jobToDelete: Job?

    private let daysOfWeek = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
    private let anchorFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "EEE, MMM dd"
        return f
    }()
    // Pay-cycle range labels use the app-wide "MMM dd" form so every statement-style
    // date reads the same across screens.
    private let cycleRangeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MMM dd"
        return f
    }()

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("Employers & Jobs")
                    .font(.system(size: 24, weight: .bold))
                Spacer()
                Button(action: { resetForm(); showDialog = true }) {
                    Text("+ Add Job")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .fill(Color.primaryGreen)
                        )
                }
            }
            .padding(16)
            .background(Color(UIColor.systemBackground))

            if dashboardViewModel.jobs.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "briefcase")
                        .font(.system(size: 40))
                        .foregroundColor(.secondary.opacity(0.4))
                    Text("No employers added yet.")
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(UIColor.systemBackground))
            } else {
                ScrollView {
                    LazyVStack(spacing: 16) {
                        ForEach(dashboardViewModel.jobs, id: \.id) { job in
                            jobCard(job)
                        }
                        Spacer().frame(height: 80)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                }
                .background(Color(UIColor.systemBackground))
            }
        }
        .background(Color(UIColor.systemBackground))
        .sheet(isPresented: $showDialog) {
            jobFormSheet
        }
        .alert("Delete Job", isPresented: Binding(
            get: { jobToDelete != nil },
            set: { if !$0 { jobToDelete = nil } }
        )) {
            Button("Delete", role: .destructive) {
                if let job = jobToDelete {
                    dashboardViewModel.deleteJob(jobId: job.id)
                    jobToDelete = nil
                }
            }
            Button("Cancel", role: .cancel) { jobToDelete = nil }
        } message: {
            Text("Are you sure you want to delete this employer?")
        }
    }

    // MARK: - Job Card

    private func jobCard(_ job: Job) -> some View {
        let cycle = payCycle(for: job, at: Date())
        let cycleStart = cycle.start
        let cycleEnd = cycle.end
        let now = Date()
        let jobShifts = dashboardViewModel.shifts.filter {
            $0.company.lowercased() == job.title.lowercased() &&
            $0.startDate >= cycleStart &&
            $0.startDate < cycleEnd &&
            $0.startDate < now
        }

        return Button(action: { populateForm(job); showDialog = true }) {
            VStack(alignment: .leading, spacing: 0) {
                // Title row
                HStack {
                    Text(job.title)
                        .font(.system(size: 20, weight: .bold))
                    Spacer()
                    HStack(spacing: 8) {
                        Text(job.isGigWork ? "Gig" : "Hourly")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(job.isGigWork ? .accentOrange : .accentBlue)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(
                                RoundedRectangle(cornerRadius: 8)
                                    .fill((job.isGigWork ? Color.accentOrange : Color.accentBlue).opacity(0.12))
                            )
                        Button(action: { jobToDelete = job }) {
                            Image(systemName: "trash")
                                .font(.system(size: 14))
                                .foregroundColor(.red)
                        }
                    }
                }

                Spacer().frame(height: 12)

                // Stats
                HStack {
                    VStack(alignment: .leading) {
                        Text("SHIFTS").font(.system(size: 10, weight: .semibold)).foregroundColor(.secondary)
                        Text("\(jobShifts.count)").font(.system(size: 16, weight: .bold))
                    }
                    Spacer()
                    VStack(alignment: .leading) {
                        Text("HOURS").font(.system(size: 10, weight: .semibold)).foregroundColor(.secondary)
                        Text("\(String(format: "%.1f", jobShifts.reduce(0) { $0 + $1.durationHours })) hrs")
                            .font(.system(size: 16, weight: .bold))
                    }
                    Spacer()
                    VStack(alignment: .trailing) {
                        Text("EARNED").font(.system(size: 10, weight: .semibold)).foregroundColor(.secondary)
                        Text("$\(jobShifts.reduce(0) { $0 + $1.totalEarned }, specifier: "%.2f")")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.primaryGreen)
                    }
                }

                Spacer().frame(height: 12)
                Divider()
                Spacer().frame(height: 8)

                // Details
                HStack {
                    Text("Weekly Target: \(job.goalType == "Hours" ? "\(String(format: "%.0f", job.goalHours)) hrs" : "$\(String(format: "%.0f", job.goalHours))")")
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                    Spacer()
                    if !job.isGigWork {
                        Text("Base Rate: $\(job.defaultHourlyRate, specifier: "%.2f")/hr")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.secondary)
                    }
                }

                if !job.isGigWork {
                    // Show the real period rather than a fixed weekday pair: a monthly
                    // job has no start weekday, and a biweekly one spans two weeks, so
                    // a "Friday to Thursday" label would be wrong.
                    Text("Pay Cycle: \(payCycleLabel(for: job, cycle: cycle))")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.primaryGreen)
                        .padding(.top, 6)
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
        .buttonStyle(.plain)
    }

    // MARK: - Form Sheet

    private var jobFormSheet: some View {
        NavigationStack {
            Form {
                Section("Job Details") {
                    TextField("Job / Employer Title", text: $title)

                    Toggle("Is Gig Work (e.g. DoorDash)?", isOn: $isGigWork)
                }

                if !isGigWork {
                    Section("Pay Settings") {
                        HStack {
                            Text("Hourly Rate ($)")
                            Spacer()
                            TextField("15.0", text: $rateStr)
                                .keyboardType(.decimalPad)
                                .multilineTextAlignment(.trailing)
                                .frame(width: 100)
                        }

                        Picker("Pay Frequency", selection: $payFrequency) {
                            ForEach(PayFrequency.allCases, id: \.self) { freq in
                                Text(freq.label).tag(freq.rawValue)
                            }
                        }
                        .onChange(of: payFrequency) { newValue in
                            // Offer a sensible starting fortnight the moment biweekly is
                            // picked, so the field is never silently empty.
                            if newValue == PayFrequency.biweekly.rawValue && payCycleAnchorMillis == nil {
                                payCycleAnchorMillis = defaultBiweeklyAnchorMillis(
                                    weeklyCycleStartDay: weeklyCycleStartDay
                                )
                            }
                        }

                        // Monthly pay periods are calendar months, so the start day is
                        // meaningless there and is hidden rather than quietly ignored.
                        if payFrequency != PayFrequency.monthly.rawValue {
                            Picker("Cycle Start Day", selection: $weeklyCycleStartDay) {
                                ForEach(daysOfWeek, id: \.self) { day in
                                    Text(day).tag(day)
                                }
                            }
                            .onChange(of: weeklyCycleStartDay) { newValue in
                                // Re-anchor to the new day so boundaries stay on it.
                                if payFrequency == PayFrequency.biweekly.rawValue {
                                    payCycleAnchorMillis = defaultBiweeklyAnchorMillis(
                                        weeklyCycleStartDay: newValue
                                    )
                                }
                            }
                        }

                        // Which of the two alternating weeks opens a period can't be
                        // derived from a weekday, so show the resolved one and let the
                        // user shift it by a week if their employer is on the other.
                        if payFrequency == PayFrequency.biweekly.rawValue {
                            let anchor = payCycleAnchorMillis
                                ?? defaultBiweeklyAnchorMillis(weeklyCycleStartDay: weeklyCycleStartDay)
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Current period started")
                                        .font(.system(size: 12))
                                        .foregroundColor(.secondary)
                                    Text(anchorFormatter.string(
                                        from: Date(timeIntervalSince1970: Double(anchor) / 1000.0)))
                                        .font(.system(size: 15, weight: .semibold))
                                }
                                Spacer()
                                Button("Shift a week") {
                                    payCycleAnchorMillis = anchor - 7 * 24 * 60 * 60 * 1000
                                }
                                .font(.system(size: 14))
                            }
                        }

                        HStack {
                            Text("Overtime After (hrs/week)")
                            Spacer()
                            TextField("40.0", text: $overtimeThresholdStr)
                                .keyboardType(.decimalPad)
                                .multilineTextAlignment(.trailing)
                                .frame(width: 100)
                        }

                        HStack {
                            Text("Overtime Rate Multiplier")
                            Spacer()
                            TextField("1.5", text: $overtimeMultiplierStr)
                                .keyboardType(.decimalPad)
                                .multilineTextAlignment(.trailing)
                                .frame(width: 100)
                        }
                    }
                }

                if !isGigWork {
                    Section("Bonus Pay") {
                        HStack {
                            Text("Bonus Amount ($)")
                            Spacer()
                            TextField("0.00", text: $bonusAmountStr)
                                .keyboardType(.decimalPad)
                                .multilineTextAlignment(.trailing)
                                .frame(width: 100)
                        }

                        TextField("Bonus Reason (e.g. Weekend, Holiday)", text: $bonusReason)
                    }
                }

                Section("Weekly Target") {
                    Picker("Target Type", selection: $goalType) {
                        Text("Hours").tag("Hours")
                        Text("Earnings").tag("Earnings")
                    }
                    .pickerStyle(.segmented)

                    HStack {
                        Text(goalType == "Hours" ? "Weekly Hours Target" : "Weekly Earnings Target ($)")
                        Spacer()
                        TextField("20.0", text: $goalHoursStr)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 100)
                    }
                }
            }
            .navigationTitle(editingJobId == nil ? "Add Employer Job" : "Edit Employer Job")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showDialog = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        saveJob()
                        showDialog = false
                    }
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.primaryGreen)
                }
            }
        }
    }

    // MARK: - Helpers

    private func resetForm() {
        editingJobId = nil
        title = ""
        isGigWork = false
        rateStr = "15.0"
        goalHoursStr = "20.0"
        goalType = "Hours"
        weeklyCycleStartDay = "Monday"
        payFrequency = PayFrequency.weekly.rawValue
        payCycleAnchorMillis = nil
        overtimeThresholdStr = "40.0"
        overtimeMultiplierStr = "1.5"
        bonusAmountStr = "0.0"
        bonusReason = ""
    }

    private func populateForm(_ job: Job) {
        editingJobId = job.id
        title = job.title
        isGigWork = job.isGigWork
        rateStr = "\(job.defaultHourlyRate)"
        goalHoursStr = "\(job.goalHours)"
        goalType = job.goalType
        weeklyCycleStartDay = job.weeklyCycleStartDay ?? "Monday"
        payFrequency = job.payFrequency
        payCycleAnchorMillis = job.payCycleAnchorMillis
        overtimeThresholdStr = "\(job.overtimeThresholdHours)"
        overtimeMultiplierStr = "\(job.overtimeMultiplier)"
        bonusAmountStr = "\(job.bonusAmount)"
        bonusReason = job.bonusReason
    }

    private func saveJob() {
        let finalRate = isGigWork ? 0.0 : (Double(rateStr) ?? 15.0)
        let finalGoal = Double(goalHoursStr) ?? 20.0
        let finalOT = Double(overtimeThresholdStr) ?? 40.0
        let finalOTM = Double(overtimeMultiplierStr) ?? 1.5
        let finalBonus = isGigWork ? 0.0 : (Double(bonusAmountStr) ?? 0.0)
        let finalBonusReason = isGigWork ? "" : bonusReason

        if let id = editingJobId {
            dashboardViewModel.updateJob(
                jobId: id, title: title, isGigWork: isGigWork,
                defaultHourlyRate: finalRate, goalHours: finalGoal,
                goalType: goalType, weeklyCycleStartDay: weeklyCycleStartDay,
                payFrequency: payFrequency, payCycleAnchorMillis: payCycleAnchorMillis,
                overtimeThresholdHours: finalOT, overtimeMultiplier: finalOTM,
                bonusAmount: finalBonus, bonusReason: finalBonusReason
            )
        } else {
            dashboardViewModel.addJob(
                title: title, isGigWork: isGigWork,
                defaultHourlyRate: finalRate, goalHours: finalGoal,
                goalType: goalType, weeklyCycleStartDay: weeklyCycleStartDay,
                payFrequency: payFrequency, payCycleAnchorMillis: payCycleAnchorMillis,
                overtimeThresholdHours: finalOT, overtimeMultiplier: finalOTM,
                bonusAmount: finalBonus, bonusReason: finalBonusReason
            )
        }
    }

    private func payCycleLabel(for job: Job, cycle: PayCycle) -> String {
        switch PayFrequency.from(job.payFrequency) {
        case .weekly:
            let start = job.weeklyCycleStartDay ?? "Monday"
            return "Weekly: \(start) to \(dayBefore(start))"
        case .biweekly, .monthly:
            let range = "\(cycleRangeFormatter.string(from: cycle.start)) – \(cycleRangeFormatter.string(from: cycle.lastInstant))"
            return "\(PayFrequency.from(job.payFrequency).label): \(range)"
        }
    }

    private func dayBefore(_ day: String) -> String {
        switch day.lowercased() {
        case "monday": return "Sunday"
        case "tuesday": return "Monday"
        case "wednesday": return "Tuesday"
        case "thursday": return "Wednesday"
        case "friday": return "Thursday"
        case "saturday": return "Friday"
        case "sunday": return "Saturday"
        default: return "Sunday"
        }
    }
}
