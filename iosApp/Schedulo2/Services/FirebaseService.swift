import Foundation
import Combine
import FirebaseAuth
import FirebaseFirestore

// MARK: - Model Structs

struct Shift: Identifiable, Codable, Equatable {
    var id: String = UUID().uuidString
    var userId: String = ""
    var company: String = ""
    var role: String = ""
    var startTime: Int64 = 0
    var endTime: Int64 = 0
    var hourlyRate: Double = 0.0
    var isGig: Bool = false
    var customEarned: Double = 0.0
    var reminderBeforeMinutes: Int = 30
    var isPaid: Bool = false
    var notes: String = ""
    var bonusApplied: Bool = false
    var bonusAmount: Double = 0.0
    // Set when this shift is a personal mirror of a team_shifts doc; the Plan
    // calendar hides mirrors so the team card is the only one rendered.
    var teamShiftId: String = ""

    var durationHours: Double {
        guard endTime > startTime else { return 0.0 }
        return Double(endTime - startTime) / 3_600_000.0
    }

    var totalEarned: Double {
        isGig ? customEarned : (durationHours * hourlyRate) + (bonusApplied ? bonusAmount : 0.0)
    }

    var startDate: Date {
        Date(timeIntervalSince1970: Double(startTime) / 1000.0)
    }

    var endDate: Date {
        Date(timeIntervalSince1970: Double(endTime) / 1000.0)
    }
}

struct Job: Identifiable, Codable, Equatable, Hashable {
    var id: String = UUID().uuidString
    var userId: String = ""
    var title: String = ""
    var isGigWork: Bool = false
    var defaultHourlyRate: Double = 15.0
    var goalHours: Double = 20.0
    var goalType: String = "Hours"
    var weeklyCycleStartDay: String? = "Monday"
    /// How long a pay period is: WEEKLY | BIWEEKLY | MONTHLY. Weekly by default so
    /// every job created before pay frequency existed keeps its current boundaries.
    var payFrequency: String = PayFrequency.weekly.rawValue
    /// Start of one known pay period, in epoch millis. Only BIWEEKLY reads it — a
    /// weekday alone can't say which of the two alternating weeks opens a period.
    var payCycleAnchorMillis: Int64? = nil
    var overtimeThresholdHours: Double = 40.0
    var overtimeMultiplier: Double = 1.5
    var bonusAmount: Double = 0.0
    var bonusReason: String = ""

    /// Start of the pay period containing `targetDate`, honouring this job's frequency.
    /// Delegates to the single boundary implementation in `PayCycleCalculator.swift`.
    func getStartOfCurrentCycle(targetDate: Date = Date()) -> Int64 {
        payCycle(for: self, at: targetDate).startMillis
    }

    /// The full pay period containing `targetDate`.
    func currentCycle(targetDate: Date = Date()) -> PayCycle {
        payCycle(for: self, at: targetDate)
    }
}

struct UserProfile: Codable, Equatable {
    var id: String = ""
    var email: String = ""
    var fullName: String = ""
    var createdAt: Int64 = 0

    enum CodingKeys: String, CodingKey {
        case id, email
        case fullName = "full_name"
        case createdAt = "created_at"
    }
}

struct UserSettings: Codable, Equatable {
    var defaultCompany: String = ""
    var defaultRate: Double = 0.0
    var themeMode: String = "system"
    var remindersEnabled: Bool = true
    var defaultReminderMinutes: Int = 30
    var userId: String = ""
}

struct PayAdjustment: Identifiable, Codable, Equatable {
    var id: String = UUID().uuidString
    var userId: String = ""
    var cycleKey: String = ""
    var employer: String = ""
    var type: String = ""  // Bonus, Overpaid, Underpaid, Deduction, Correction
    var amount: Double = 0.0
    var notes: String = ""
    var createdAt: Int64 = 0
}

// MARK: - FirebaseService

final class FirebaseService {
    static let shared = FirebaseService()

    private let auth = Auth.auth()
    private let db = Firestore.firestore(database: "schedulo2")

    // MARK: Combine publishers for real-time data
    let shiftsSubject = CurrentValueSubject<[Shift], Never>([])
    let jobsSubject = CurrentValueSubject<[Job], Never>([])
    let profileSubject = CurrentValueSubject<UserProfile?, Never>(nil)
    let settingsSubject = CurrentValueSubject<UserSettings?, Never>(nil)
    let authStateSubject = CurrentValueSubject<User?, Never>(nil)
    let payAdjustmentsSubject = CurrentValueSubject<[PayAdjustment], Never>([])

    private var shiftsListener: ListenerRegistration?
    private var jobsListener: ListenerRegistration?
    private var profileListener: ListenerRegistration?
    private var settingsListener: ListenerRegistration?
    private var payAdjustmentsListener: ListenerRegistration?
    private var authHandle: AuthStateDidChangeListenerHandle?

    private init() {
        authHandle = auth.addStateDidChangeListener { [weak self] _, user in
            self?.authStateSubject.send(user)
        }
    }

    deinit {
        if let handle = authHandle {
            auth.removeStateDidChangeListener(handle)
        }
        removeAllListeners()
    }

    // MARK: - Auth Properties

    var currentUser: User? { auth.currentUser }
    var currentUserId: String? { auth.currentUser?.uid }

    // MARK: - Auth Methods

    func signIn(email: String, password: String) async throws {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        try await auth.signIn(withEmail: trimmed, password: password)
        if let uid = auth.currentUser?.uid {
            try await ensureProfileExists(uid: uid, email: trimmed)
        }
    }

    func signUp(email: String, password: String, fullName: String) async throws {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let result = try await auth.createUser(withEmail: trimmed, password: password)
        let user = result.user
        let profile: [String: Any] = [
            "id": user.uid,
            "email": trimmed,
            "full_name": fullName.trimmingCharacters(in: .whitespacesAndNewlines),
            "created_at": Int64(Date().timeIntervalSince1970 * 1000)
        ]
        try await db.collection("profiles").document(user.uid).setData(profile)
        // Send a verification email so team features can be gated on a verified
        // address (see AuthViewModel.isEmailVerified / TeamViewModel gating).
        try? await user.sendEmailVerification()
    }

    /// Re-send the verification email to the current user.
    func resendVerificationEmail() async throws {
        try await auth.currentUser?.sendEmailVerification()
    }

    /// Reload the cached user so isEmailVerified reflects a just-clicked link.
    func reloadUser() async throws {
        try await auth.currentUser?.reload()
    }

    func signOut() throws {
        removeAllListeners()
        try auth.signOut()
    }

    func deleteAccount(password: String?) async throws {
        guard let user = auth.currentUser else {
            throw NSError(domain: "FirebaseService", code: -1, userInfo: [NSLocalizedDescriptionKey: "No user signed in."])
        }
        let uid = user.uid

        // Re-authenticate when a password is supplied. Firebase requires a recent
        // login to delete an account; the first (password-less) attempt surfaces
        // requiresRecentLogin so the UI can prompt for the password.
        if let password = password, let email = user.email {
            let credential = EmailAuthProvider.credential(withEmail: email, password: password)
            try await user.reauthenticate(with: credential)
        }

        // Delete user data
        let shiftsSnap = try await db.collection("shifts").whereField("userId", isEqualTo: uid).getDocuments()
        for doc in shiftsSnap.documents { try await doc.reference.delete() }

        let jobsSnap = try await db.collection("jobs").whereField("userId", isEqualTo: uid).getDocuments()
        for doc in jobsSnap.documents { try await doc.reference.delete() }

        try await db.collection("profiles").document(uid).delete()
        try await db.collection("settings").document(uid).delete()

        removeAllListeners()
        try await user.delete()
    }

    func changePassword(currentPassword: String, newPassword: String) async throws {
        guard let user = auth.currentUser, let email = user.email else {
            throw NSError(domain: "FirebaseService", code: -1, userInfo: [NSLocalizedDescriptionKey: "No user signed in."])
        }
        let credential = EmailAuthProvider.credential(withEmail: email, password: currentPassword)
        try await user.reauthenticate(with: credential)
        try await user.updatePassword(to: newPassword)
    }

    func sendPasswordReset(email: String) async throws {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        try await auth.sendPasswordReset(withEmail: trimmed)
    }

    // MARK: - Profile

    private func ensureProfileExists(uid: String, email: String) async throws {
        let doc = try await db.collection("profiles").document(uid).getDocument()
        if !doc.exists {
            let profile: [String: Any] = [
                "id": uid,
                "email": email,
                "full_name": "",
                "created_at": Int64(Date().timeIntervalSince1970 * 1000)
            ]
            try await db.collection("profiles").document(uid).setData(profile)
        } else {
            let storedEmail = doc.data()?["email"] as? String ?? ""
            if storedEmail != email && !email.isEmpty {
                try await db.collection("profiles").document(uid).updateData(["email": email])
            }
        }
    }

    func updateUserName(_ name: String) async throws {
        guard let uid = currentUserId else { return }
        try await db.collection("profiles").document(uid).updateData(["full_name": name])
    }

    func listenToProfile() {
        guard let uid = currentUserId else { return }
        profileListener?.remove()
        profileListener = db.collection("profiles").document(uid)
            .addSnapshotListener { [weak self] snapshot, error in
                guard let data = snapshot?.data(), error == nil else { return }
                let profile = UserProfile(
                    id: data["id"] as? String ?? uid,
                    email: data["email"] as? String ?? "",
                    fullName: data["full_name"] as? String ?? "",
                    createdAt: data["created_at"] as? Int64 ?? 0
                )
                self?.profileSubject.send(profile)
            }
    }

    // MARK: - Settings

    func listenToSettings() {
        guard let uid = currentUserId else { return }
        settingsListener?.remove()
        settingsListener = db.collection("settings").document(uid)
            .addSnapshotListener { [weak self] snapshot, error in
                guard let data = snapshot?.data(), error == nil else { return }
                let settings = UserSettings(
                    defaultCompany: data["defaultCompany"] as? String ?? "",
                    defaultRate: data["defaultRate"] as? Double ?? 0.0,
                    themeMode: data["themeMode"] as? String ?? "system",
                    remindersEnabled: data["remindersEnabled"] as? Bool ?? true,
                    defaultReminderMinutes: data["defaultReminderMinutes"] as? Int ?? 30,
                    userId: data["userId"] as? String ?? uid
                )
                self?.settingsSubject.send(settings)
            }
    }

    func updateSettings(_ fields: [String: Any]) {
        guard let uid = currentUserId else { return }
        var merged = fields
        merged["userId"] = uid
        db.collection("settings").document(uid).setData(merged, merge: true)
    }

    func saveSettings(company: String, rate: Double) {
        guard let uid = currentUserId else { return }
        let data: [String: Any] = [
            "defaultCompany": company,
            "defaultRate": rate,
            "userId": uid,
            "updatedAt": Int64(Date().timeIntervalSince1970 * 1000)
        ]
        db.collection("settings").document(uid).setData(data, merge: true)
    }

    // MARK: - Shifts

    func listenToShifts() {
        guard let uid = currentUserId else { return }
        shiftsListener?.remove()
        shiftsListener = db.collection("shifts")
            .whereField("userId", isEqualTo: uid)
            .addSnapshotListener { [weak self] snapshot, error in
                guard let documents = snapshot?.documents, error == nil else { return }
                let shifts: [Shift] = documents.compactMap { doc in
                    let data = doc.data()
                    return Shift(
                        id: doc.documentID,
                        userId: data["userId"] as? String ?? "",
                        company: data["company"] as? String ?? "",
                        role: data["role"] as? String ?? "",
                        startTime: (data["startTime"] as? NSNumber)?.int64Value ?? 0,
                        endTime: (data["endTime"] as? NSNumber)?.int64Value ?? 0,
                        hourlyRate: data["hourlyRate"] as? Double ?? 0.0,
                        isGig: data["isGig"] as? Bool ?? false,
                        customEarned: data["customEarned"] as? Double ?? 0.0,
                        reminderBeforeMinutes: data["reminderBeforeMinutes"] as? Int ?? 30,
                        isPaid: data["isPaid"] as? Bool ?? false,
                        notes: data["notes"] as? String ?? "",
                        bonusApplied: data["bonusApplied"] as? Bool ?? false,
                        bonusAmount: data["bonusAmount"] as? Double ?? 0.0,
                        teamShiftId: data["teamShiftId"] as? String ?? ""
                    )
                }.sorted { $0.startTime > $1.startTime }
                self?.shiftsSubject.send(shifts)
            }
    }

    func addShift(_ shift: Shift) {
        var s = shift
        if s.userId.isEmpty { s.userId = currentUserId ?? "" }
        db.collection("shifts").document(s.id).setData(shiftToDict(s))
    }

    func updateShift(_ shift: Shift) {
        db.collection("shifts").document(shift.id).setData(shiftToDict(shift))
    }

    func deleteShift(_ shiftId: String) {
        db.collection("shifts").document(shiftId).delete()
    }

    func toggleShiftPaid(_ shiftId: String, isPaid: Bool, allShifts: [Shift]) {
        guard let shift = allShifts.first(where: { $0.id == shiftId }) else { return }
        var updated = shift
        updated.isPaid = isPaid
        db.collection("shifts").document(shiftId).setData(shiftToDict(updated))
    }

    func markCycleAsPaid(shiftIds: [String], isPaid: Bool, allShifts: [Shift]) {
        let batch = db.batch()
        let idSet = Set(shiftIds)
        for shift in allShifts where idSet.contains(shift.id) {
            var updated = shift
            updated.isPaid = isPaid
            let ref = db.collection("shifts").document(shift.id)
            batch.setData(shiftToDict(updated), forDocument: ref)
        }
        batch.commit(completion: nil)
    }

    private func shiftToDict(_ s: Shift) -> [String: Any] {
        var dict: [String: Any] = [
            "id": s.id,
            "userId": s.userId,
            "company": s.company,
            "role": s.role,
            "startTime": s.startTime,
            "endTime": s.endTime,
            "hourlyRate": s.hourlyRate,
            "isGig": s.isGig,
            "customEarned": s.customEarned,
            "reminderBeforeMinutes": s.reminderBeforeMinutes,
            "isPaid": s.isPaid,
            "notes": s.notes,
            "bonusApplied": s.bonusApplied,
            "bonusAmount": s.bonusAmount
        ]
        if !s.teamShiftId.isEmpty {
            dict["teamShiftId"] = s.teamShiftId
        }
        return dict
    }

    // MARK: - Jobs

    func listenToJobs() {
        guard let uid = currentUserId else { return }
        jobsListener?.remove()
        jobsListener = db.collection("jobs")
            .whereField("userId", isEqualTo: uid)
            .addSnapshotListener { [weak self] snapshot, error in
                guard let documents = snapshot?.documents, error == nil else { return }
                let isFromCache = snapshot?.metadata.isFromCache ?? false
                let jobs: [Job] = documents.compactMap { doc in
                    let data = doc.data()
                    return Job(
                        id: doc.documentID,
                        userId: data["userId"] as? String ?? "",
                        title: data["title"] as? String ?? "",
                        isGigWork: data["isGigWork"] as? Bool ?? false,
                        defaultHourlyRate: data["defaultHourlyRate"] as? Double ?? 15.0,
                        goalHours: data["goalHours"] as? Double ?? 20.0,
                        goalType: data["goalType"] as? String ?? "Hours",
                        weeklyCycleStartDay: data["weeklyCycleStartDay"] as? String ?? "Monday",
                        payFrequency: data["payFrequency"] as? String ?? PayFrequency.weekly.rawValue,
                        payCycleAnchorMillis: (data["payCycleAnchorMillis"] as? NSNumber)?.int64Value,
                        overtimeThresholdHours: data["overtimeThresholdHours"] as? Double ?? 40.0,
                        overtimeMultiplier: data["overtimeMultiplier"] as? Double ?? 1.5,
                        bonusAmount: data["bonusAmount"] as? Double ?? 0.0,
                        bonusReason: data["bonusReason"] as? String ?? ""
                    )
                }
                if jobs.isEmpty && !isFromCache {
                    self?.createDefaultJobs(uid: uid)
                } else {
                    self?.jobsSubject.send(jobs)
                }
            }
    }

    private func createDefaultJobs(uid: String) {
        let defaults: [Job] = [
            Job(userId: uid, title: "7-ELEVEN", isGigWork: false, defaultHourlyRate: 15.0, goalHours: 20.0, goalType: "Hours", weeklyCycleStartDay: "Friday"),
            Job(userId: uid, title: "Walmart", isGigWork: false, defaultHourlyRate: 17.5, goalHours: 25.0, goalType: "Hours", weeklyCycleStartDay: "Monday"),
            Job(userId: uid, title: "DoorDash", isGigWork: true, defaultHourlyRate: 0.0, goalHours: 200.0, goalType: "Earnings", weeklyCycleStartDay: "Monday")
        ]
        for job in defaults {
            db.collection("jobs").document(job.id).setData(jobToDict(job))
        }
    }

    func addJob(_ job: Job) {
        var j = job
        if j.userId.isEmpty { j.userId = currentUserId ?? "" }
        db.collection("jobs").document(j.id).setData(jobToDict(j))
    }

    func updateJob(_ job: Job) {
        db.collection("jobs").document(job.id).setData(jobToDict(job))
    }

    func deleteJob(_ jobId: String) {
        db.collection("jobs").document(jobId).delete()
    }

    private func jobToDict(_ j: Job) -> [String: Any] {
        var dict: [String: Any] = [
            "id": j.id,
            "userId": j.userId,
            "title": j.title,
            "isGigWork": j.isGigWork,
            "defaultHourlyRate": j.defaultHourlyRate,
            "goalHours": j.goalHours,
            "goalType": j.goalType,
            "weeklyCycleStartDay": j.weeklyCycleStartDay ?? "Monday",
            "payFrequency": j.payFrequency,
            "overtimeThresholdHours": j.overtimeThresholdHours,
            "overtimeMultiplier": j.overtimeMultiplier,
            "bonusAmount": j.bonusAmount,
            "bonusReason": j.bonusReason
        ]
        // Only biweekly jobs carry an anchor; writing NSNull for the others would
        // store a field the Android reader has to special-case.
        if let anchor = j.payCycleAnchorMillis {
            dict["payCycleAnchorMillis"] = anchor
        }
        return dict
    }

    // MARK: - Pay Adjustments

    func listenToPayAdjustments() {
        guard let uid = currentUserId else { return }
        payAdjustmentsListener?.remove()
        payAdjustmentsListener = db.collection("pay_adjustments")
            .whereField("userId", isEqualTo: uid)
            .addSnapshotListener { [weak self] snapshot, error in
                guard let documents = snapshot?.documents, error == nil else { return }
                let adjustments: [PayAdjustment] = documents.compactMap { doc in
                    let data = doc.data()
                    return PayAdjustment(
                        id: doc.documentID,
                        userId: data["userId"] as? String ?? "",
                        cycleKey: data["cycleKey"] as? String ?? "",
                        employer: data["employer"] as? String ?? "",
                        type: data["type"] as? String ?? "",
                        amount: data["amount"] as? Double ?? 0.0,
                        notes: data["notes"] as? String ?? "",
                        createdAt: (data["createdAt"] as? NSNumber)?.int64Value ?? 0
                    )
                }
                self?.payAdjustmentsSubject.send(adjustments)
            }
    }

    func addPayAdjustment(_ adjustment: PayAdjustment) {
        var a = adjustment
        if a.userId.isEmpty { a.userId = currentUserId ?? "" }
        if a.createdAt == 0 { a.createdAt = Int64(Date().timeIntervalSince1970 * 1000) }
        let data: [String: Any] = [
            "id": a.id,
            "userId": a.userId,
            "cycleKey": a.cycleKey,
            "employer": a.employer,
            "type": a.type,
            "amount": a.amount,
            "notes": a.notes,
            "createdAt": a.createdAt
        ]
        db.collection("pay_adjustments").document(a.id).setData(data)
    }

    func deletePayAdjustment(_ adjustmentId: String) {
        db.collection("pay_adjustments").document(adjustmentId).delete()
    }

    // MARK: - Feedback

    /// Store a bug report. Unlike the other writes here this one reports its
    /// error: the user is waiting on a confirmation screen, and a silent failure
    /// would tell them the report was filed when it wasn't.
    func submitFeedback(_ feedback: FeedbackReport, completion: @escaping (Error?) -> Void) {
        let data: [String: Any] = [
            "id": feedback.id,
            "userId": feedback.userId,
            "userEmail": feedback.userEmail,
            "category": feedback.category,
            "description": feedback.description,
            "stepsToReproduce": feedback.stepsToReproduce,
            "screenshotUrl": feedback.screenshotUrl,
            "appVersion": feedback.appVersion,
            "platform": feedback.platform,
            "osVersion": feedback.osVersion,
            "deviceModel": feedback.deviceModel,
            "status": feedback.status,
            "createdAt": feedback.createdAt
        ]
        db.collection("feedback").document(feedback.id).setData(data) { error in
            DispatchQueue.main.async { completion(error) }
        }
    }

    // MARK: - Listener Management

    func removeAllListeners() {
        shiftsListener?.remove()
        shiftsListener = nil
        jobsListener?.remove()
        jobsListener = nil
        profileListener?.remove()
        profileListener = nil
        settingsListener?.remove()
        settingsListener = nil
        payAdjustmentsListener?.remove()
        payAdjustmentsListener = nil
        shiftsSubject.send([])
        jobsSubject.send([])
        profileSubject.send(nil)
        settingsSubject.send(nil)
        payAdjustmentsSubject.send([])
    }

    func startAllListeners() {
        listenToShifts()
        listenToJobs()
        listenToProfile()
        listenToSettings()
        listenToPayAdjustments()
    }
}
