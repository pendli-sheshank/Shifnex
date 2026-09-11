package com.example

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import com.example.ui.theme.PrimaryGreen
import com.schedulo.shared.logic.PayCycle
import com.schedulo.shared.logic.payCycleFor as sharedPayCycleFor
import com.schedulo.shared.logic.payCycleAtOffset as sharedPayCycleAtOffset
import com.schedulo.shared.logic.defaultBiweeklyAnchorMillis
import com.schedulo.shared.model.PayFrequency
import com.schedulo.shared.model.Job as SharedJob
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.example.widget.WidgetDataProvider
import com.google.firebase.firestore.ListenerRegistration

const val FIRESTORE_DB_NAME = "schedulo2"

data class Job(
    var id: String = java.util.UUID.randomUUID().toString(),
    var userId: String = "",
    var title: String = "",
    var isGigWork: Boolean = false,
    var defaultHourlyRate: Double = 15.0,
    var goalHours: Double = 20.0,
    var goalType: String = "Hours", // "Hours" or "Earnings"
    var weeklyCycleStartDay: String? = "Monday", // "Monday", "Tuesday", etc.
    // How long a pay period is: WEEKLY | BIWEEKLY | MONTHLY. Weekly by default so
    // every job created before pay frequency existed keeps its current boundaries.
    var payFrequency: String = PayFrequency.WEEKLY_VALUE,
    // Start of one known pay period. Only BIWEEKLY reads it — a weekday alone can't
    // say which of the two alternating weeks opens a period.
    var payCycleAnchorMillis: Long? = null,
    var overtimeThresholdHours: Double = 40.0, // weekly hours after which overtime kicks in
    var overtimeMultiplier: Double = 1.5, // pay multiplier for overtime (e.g., 1.5x)
    var bonusAmount: Double = 0.0,
    var bonusReason: String = ""
) {
    fun getStartOfCurrentCycle(targetMillis: Long = System.currentTimeMillis()): Long =
        payCycleFor(this, targetMillis).startMillis
}

// Bridges the Android Job onto the shared, unit-tested pay-cycle calculator. The
// boundary math deliberately has exactly one implementation in the codebase: a wrong
// boundary silently misstates someone's pay, and only the shared module has tests.
private fun Job.toSharedJob(): SharedJob = SharedJob(
    id = id,
    userId = userId,
    title = title,
    isGigWork = isGigWork,
    defaultHourlyRate = defaultHourlyRate,
    goalHours = goalHours,
    goalType = goalType,
    weeklyCycleStartDay = weeklyCycleStartDay,
    payFrequency = payFrequency,
    payCycleAnchorMillis = payCycleAnchorMillis,
    overtimeThresholdHours = overtimeThresholdHours,
    overtimeMultiplier = overtimeMultiplier,
    bonusAmount = bonusAmount,
    bonusReason = bonusReason
)

/** The pay cycle containing [targetMillis] for [job], honouring its pay frequency. */
fun payCycleFor(job: Job, targetMillis: Long): PayCycle =
    sharedPayCycleFor(job.toSharedJob(), targetMillis)

/** The pay cycle [offset] whole periods from the one containing [targetMillis]. */
fun payCycleAtOffset(job: Job, targetMillis: Long, offset: Int): PayCycle =
    sharedPayCycleAtOffset(job.toSharedJob(), targetMillis, offset)

// Maps a stored week-start day name ("Friday") to its Calendar constant.
// Payroll weeks are per-job fiscal weeks (job.weeklyCycleStartDay), never
// calendar weeks — anchor all week math here instead of Calendar.MONDAY.
fun weekStartCalendarDay(name: String?): Int = when (name?.lowercase(Locale.US) ?: "monday") {
    "sunday" -> Calendar.SUNDAY
    "monday" -> Calendar.MONDAY
    "tuesday" -> Calendar.TUESDAY
    "wednesday" -> Calendar.WEDNESDAY
    "thursday" -> Calendar.THURSDAY
    "friday" -> Calendar.FRIDAY
    "saturday" -> Calendar.SATURDAY
    else -> Calendar.MONDAY
}

// Start-of-day of the most recent weekStartDay on or before the given moment.
// Deliberately always weekly: this backs the cross-job aggregate views (dashboard
// "this week" card, widgets, unfiltered insights), which summarise a calendar-ish
// week rather than any single employer's pay period.
fun startOfWeekContaining(millis: Long, weekStartDay: String?): Long =
    sharedPayCycleFor(
        SharedJob(weeklyCycleStartDay = weekStartDay, payFrequency = PayFrequency.WEEKLY_VALUE),
        millis
    ).startMillis

data class PayAdjustment(
    var id: String = java.util.UUID.randomUUID().toString(),
    var userId: String = "",
    var cycleKey: String = "",
    var employer: String = "",
    var type: String = "Bonus",
    var amount: Double = 0.0,
    var notes: String = "",
    var createdAt: Long = System.currentTimeMillis()
)

data class Shift(
    var id: String = java.util.UUID.randomUUID().toString(),
    var userId: String = "",
    var company: String = "",
    var role: String = "",
    var startTime: Long = 0,
    var endTime: Long = 0,
    var hourlyRate: Double = 0.0,
    var isGig: Boolean = false,
    var customEarned: Double = 0.0,
    var reminderBeforeMinutes: Int = 30,
    var isPaid: Boolean = false,
    var notes: String = "",
    var bonusApplied: Boolean = false,
    var bonusAmount: Double = 0.0,
    // Set when this shift is a personal mirror of a team_shifts doc; the Plan
    // calendar hides mirrors so the team card is the only one rendered.
    var teamShiftId: String = ""
) {
    @get:com.google.firebase.firestore.Exclude
    val durationHours: Double
        get() = if (endTime > startTime) (endTime - startTime) / 3600000.0 else 0.0

    @get:com.google.firebase.firestore.Exclude
    val totalEarned: Double
        get() = if (isGig) customEarned else (durationHours * hourlyRate) + (if (bonusApplied) bonusAmount else 0.0)
}

fun calculateEarningsWithOvertime(shifts: List<Shift>, job: Job): Pair<Double, Double> {
    // Returns (regularEarnings, overtimeEarnings)
    if (job.isGigWork) {
        // Gig work doesn't have overtime
        return Pair(shifts.sumOf { it.totalEarned }, 0.0)
    }
    // Each shift is priced at its own stored hourlyRate (the rate in effect when
    // it was worked) so editing the job's defaultHourlyRate never re-prices past
    // cycles. Hours past the overtime threshold are split chronologically.
    val threshold = job.overtimeThresholdHours
    val multiplier = job.overtimeMultiplier
    var hoursSoFar = 0.0
    var regularEarnings = 0.0
    var overtimeEarnings = 0.0
    for (shift in shifts.sortedBy { it.startTime }) {
        val hours = shift.durationHours
        val regularPortion = (threshold - hoursSoFar).coerceIn(0.0, hours)
        regularEarnings += regularPortion * shift.hourlyRate
        overtimeEarnings += (hours - regularPortion) * shift.hourlyRate * multiplier
        hoursSoFar += hours
    }
    return Pair(regularEarnings, overtimeEarnings)
}

class DashboardViewModel : ViewModel() {
    private val auth by lazy { try { FirebaseAuth.getInstance() } catch(e:Exception){null} }
    private val db by lazy { try { FirebaseFirestore.getInstance(FirebaseApp.getInstance(), FIRESTORE_DB_NAME) } catch(e:Exception){null} }

    private var appContext: android.content.Context? = null

    fun setAppContext(context: android.content.Context) {
        appContext = context.applicationContext
        val currentShifts = _shifts.value
        if (currentShifts.isNotEmpty()) {
            try { WidgetDataProvider.updateWidgetData(context.applicationContext, currentShifts, resolveGlobalWeekStartDay()) } catch (_: Exception) {}
        }
    }

    private val _userId = MutableStateFlow(auth?.currentUser?.uid ?: "")
    val userId = _userId.asStateFlow()

    private val _shifts = MutableStateFlow<List<Shift>>(emptyList())
    val shifts = _shifts.asStateFlow()

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs = _jobs.asStateFlow()

    private val _defaultCompany = MutableStateFlow("")
    val defaultCompany = _defaultCompany.asStateFlow()

    private val _defaultRate = MutableStateFlow(0.0)
    val defaultRate = _defaultRate.asStateFlow()

    private val _userName = MutableStateFlow("")
    val userName = _userName.asStateFlow()

    private val _memberSince = MutableStateFlow("")
    val memberSince = _memberSince.asStateFlow()

    private val _payAdjustments = MutableStateFlow<List<PayAdjustment>>(emptyList())
    val payAdjustments = _payAdjustments.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError = _syncError.asStateFlow()

    private val _themeMode = MutableStateFlow("system")
    val themeMode = _themeMode.asStateFlow()

    private val _remindersEnabled = MutableStateFlow(true)
    val remindersEnabled = _remindersEnabled.asStateFlow()

    private val _defaultReminderMinutes = MutableStateFlow(30)
    val defaultReminderMinutes = _defaultReminderMinutes.asStateFlow()

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        val uid = auth?.currentUser?.uid ?: return
        val database = db ?: return
        database.collection("settings").document(uid).update("themeMode", mode)
            .addOnFailureListener {
                database.collection("settings").document(uid)
                    .set(mapOf("themeMode" to mode, "userId" to uid), com.google.firebase.firestore.SetOptions.merge())
            }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        _remindersEnabled.value = enabled
        val uid = auth?.currentUser?.uid ?: return
        val database = db ?: return
        database.collection("settings").document(uid)
            .set(mapOf("remindersEnabled" to enabled, "userId" to uid), com.google.firebase.firestore.SetOptions.merge())
    }

    fun setDefaultReminderMinutes(minutes: Int) {
        _defaultReminderMinutes.value = minutes
        val uid = auth?.currentUser?.uid ?: return
        val database = db ?: return
        database.collection("settings").document(uid)
            .set(mapOf("defaultReminderMinutes" to minutes, "userId" to uid), com.google.firebase.firestore.SetOptions.merge())
    }

    private var loadedForUserId: String? = null
    private var hasSeededDefaults = false
    private var jobsListenerRegistration: ListenerRegistration? = null
    private var shiftsListenerRegistration: ListenerRegistration? = null
    private var profileListenerRegistration: ListenerRegistration? = null
    private var settingsListenerRegistration: ListenerRegistration? = null
    private var adjustmentsListenerRegistration: ListenerRegistration? = null
    
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun refreshData() {
        _isRefreshing.value = true
        loadedForUserId = null
        jobsListenerRegistration?.remove()
        jobsListenerRegistration = null
        shiftsListenerRegistration?.remove()
        shiftsListenerRegistration = null
        profileListenerRegistration?.remove()
        profileListenerRegistration = null
        settingsListenerRegistration?.remove()
        settingsListenerRegistration = null
        adjustmentsListenerRegistration?.remove()
        adjustmentsListenerRegistration = null
        loadShifts()
    }

    fun detectConflicts(startTime: Long, endTime: Long, excludeShiftId: String? = null): List<Shift> {
        return _shifts.value.filter { shift ->
            shift.id != excludeShiftId &&
                shift.startTime < endTime && shift.endTime > startTime
        }
    }

    fun clearSyncError() {
        _syncError.value = null
    }

    fun loadSettings() {
        val uid = auth?.currentUser?.uid ?: return
        val database = db ?: return
        _userId.value = uid
        settingsListenerRegistration?.remove()
        settingsListenerRegistration = database.collection("settings").document(uid).addSnapshotListener { doc, error ->
            if (error != null) {
                _syncError.value = "Failed to load settings: ${error.message}"
                return@addSnapshotListener
            }
            if (doc != null && doc.exists()) {
                _defaultCompany.value = doc.getString("defaultCompany") ?: ""
                _defaultRate.value = doc.getDouble("defaultRate") ?: 0.0
                _themeMode.value = doc.getString("themeMode") ?: "system"
                _remindersEnabled.value = doc.getBoolean("remindersEnabled") ?: true
                _defaultReminderMinutes.value = doc.getLong("defaultReminderMinutes")?.toInt() ?: 30
            }
        }
        profileListenerRegistration?.remove()
        profileListenerRegistration = database.collection("profiles").document(uid).addSnapshotListener { doc, error ->
            if (error != null) {
                _syncError.value = "Failed to load profile: ${error.message}"
                return@addSnapshotListener
            }
            if (doc != null && doc.exists()) {
                _userName.value = doc.getString("full_name") ?: ""
                val createdAt = doc.getLong("created_at")
                if (createdAt != null) {
                    val fmt = java.text.SimpleDateFormat("MMMM yyyy", Locale.US)
                    _memberSince.value = fmt.format(Date(createdAt))
                }
            } else {
                ensureProfileExists(uid, database)
            }
        }
    }

    private fun ensureProfileExists(uid: String, database: FirebaseFirestore) {
        val email = auth?.currentUser?.email ?: ""
        val displayName = auth?.currentUser?.displayName ?: ""
        val profile = hashMapOf(
            "id" to uid,
            "email" to email,
            "full_name" to displayName,
            "created_at" to System.currentTimeMillis()
        )
        database.collection("profiles").document(uid).set(profile)
    }

    fun saveSettings(company: String, rate: Double) {
        val uid = auth?.currentUser?.uid
        val database = db
        if (uid == null || database == null) {
            _syncError.value = "Please sign in to save settings."
            return
        }
        _userId.value = uid
        _defaultCompany.value = company
        _defaultRate.value = rate
        val data = hashMapOf(
            "defaultCompany" to company,
            "defaultRate" to rate,
            "userId" to uid,
            "updatedAt" to System.currentTimeMillis()
        )
        database.collection("settings").document(uid).set(data, com.google.firebase.firestore.SetOptions.merge())
            .addOnFailureListener { e ->
                _syncError.value = "Failed to save settings: ${e.message}"
            }
    }

    fun loadJobs() {
        val uid = auth?.currentUser?.uid ?: return
        val database = db ?: return
        _userId.value = uid
        jobsListenerRegistration?.remove()
        jobsListenerRegistration = database.collection("jobs")
            .whereEqualTo("userId", uid)
            .addSnapshotListener { value, error ->
                if (error != null) {
                    _syncError.value = "Failed to load jobs: ${error.message}"
                    return@addSnapshotListener
                }
                if (value != null) {
                    val list = value.documents.mapNotNull { doc ->
                        doc.toObject(Job::class.java)?.copy(id = doc.id)
                    }
                    _jobs.value = list
                    if (!hasSeededDefaults && list.isEmpty()) {
                        hasSeededDefaults = true
                        seedDefaultJobsIfEmpty()
                    }
                }
            }
    }

    fun seedDefaultJobsIfEmpty() {
        val uid = auth?.currentUser?.uid ?: return
        val database = db ?: return
        if (_jobs.value.isNotEmpty()) return
        database.collection("jobs")
            .whereEqualTo("userId", uid)
            .get(com.google.firebase.firestore.Source.SERVER)
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    val defaultJobs = listOf(
                        Job(title = "7-ELEVEN", isGigWork = false, defaultHourlyRate = 15.0, goalHours = 20.0, goalType = "Hours", weeklyCycleStartDay = "Friday"),
                        Job(title = "Walmart", isGigWork = false, defaultHourlyRate = 17.5, goalHours = 25.0, goalType = "Hours", weeklyCycleStartDay = "Monday"),
                        Job(title = "DoorDash", isGigWork = true, defaultHourlyRate = 0.0, goalHours = 200.0, goalType = "Earnings", weeklyCycleStartDay = "Monday")
                    )
                    val batch = database.batch()
                    for (j in defaultJobs) {
                        j.userId = uid
                        batch.set(database.collection("jobs").document(j.id), j)
                    }
                    batch.commit()
                }
            }
    }

    fun addJob(title: String, isGigWork: Boolean, defaultHourlyRate: Double, goalHours: Double, goalType: String, weeklyCycleStartDay: String = "Monday", payFrequency: String = PayFrequency.WEEKLY_VALUE, payCycleAnchorMillis: Long? = null, overtimeThresholdHours: Double = 40.0, overtimeMultiplier: Double = 1.5, bonusAmount: Double = 0.0, bonusReason: String = "") {
        val uid = auth?.currentUser?.uid
        val database = db
        if (uid == null || database == null) {
            _syncError.value = "Please sign in to add employers."
            return
        }
        val job = Job(
            id = java.util.UUID.randomUUID().toString(),
            userId = uid,
            title = title,
            isGigWork = isGigWork,
            defaultHourlyRate = defaultHourlyRate.coerceAtLeast(0.0),
            goalHours = goalHours.coerceAtLeast(0.0),
            goalType = goalType,
            weeklyCycleStartDay = weeklyCycleStartDay,
            payFrequency = normalizePayFrequency(payFrequency),
            payCycleAnchorMillis = resolveAnchor(payFrequency, payCycleAnchorMillis, weeklyCycleStartDay),
            overtimeThresholdHours = overtimeThresholdHours.coerceAtLeast(1.0),
            overtimeMultiplier = overtimeMultiplier.coerceAtLeast(1.0),
            bonusAmount = if (isGigWork) 0.0 else bonusAmount.coerceAtLeast(0.0),
            bonusReason = if (isGigWork) "" else bonusReason
        )
        _jobs.value = _jobs.value + job
        database.collection("jobs").document(job.id).set(job)
            .addOnFailureListener { e ->
                _jobs.value = _jobs.value.filter { it.id != job.id }
                _syncError.value = "Failed to save job: ${e.message}"
            }
    }

    private fun normalizePayFrequency(raw: String?): String =
        if (PayFrequency.isValid(raw)) raw!!.uppercase() else PayFrequency.WEEKLY_VALUE

    // Biweekly is the only frequency that needs an anchor, and it needs a real one:
    // leaving it null would let the boundary depend on when it was first evaluated.
    // Persist the most recent cycle start day so the user can see and correct it.
    private fun resolveAnchor(frequency: String?, supplied: Long?, weeklyCycleStartDay: String?): Long? =
        if (PayFrequency.from(frequency) != PayFrequency.BIWEEKLY) null
        else supplied ?: defaultBiweeklyAnchorMillis(weeklyCycleStartDay, System.currentTimeMillis())

    fun updateJob(jobId: String, title: String, isGigWork: Boolean, defaultHourlyRate: Double, goalHours: Double, goalType: String, weeklyCycleStartDay: String, payFrequency: String = PayFrequency.WEEKLY_VALUE, payCycleAnchorMillis: Long? = null, overtimeThresholdHours: Double = 40.0, overtimeMultiplier: Double = 1.5, bonusAmount: Double = 0.0, bonusReason: String = "") {
        val job = jobs.value.find { it.id == jobId } ?: return
        val database = db ?: run {
            _syncError.value = "Please sign in to update employers."
            return
        }
        val updated = job.copy(
            title = title,
            isGigWork = isGigWork,
            defaultHourlyRate = defaultHourlyRate.coerceAtLeast(0.0),
            goalHours = goalHours.coerceAtLeast(0.0),
            goalType = goalType,
            weeklyCycleStartDay = weeklyCycleStartDay,
            payFrequency = normalizePayFrequency(payFrequency),
            // Keep the job's existing anchor when one is already stored, so editing an
            // unrelated field can't silently shift every past pay period by a week.
            payCycleAnchorMillis = resolveAnchor(
                payFrequency,
                payCycleAnchorMillis ?: job.payCycleAnchorMillis,
                weeklyCycleStartDay
            ),
            overtimeThresholdHours = overtimeThresholdHours.coerceAtLeast(1.0),
            overtimeMultiplier = overtimeMultiplier.coerceAtLeast(1.0),
            bonusAmount = if (isGigWork) 0.0 else bonusAmount.coerceAtLeast(0.0),
            bonusReason = if (isGigWork) "" else bonusReason
        )
        val previousJobs = _jobs.value
        _jobs.value = _jobs.value.map { if (it.id == jobId) updated else it }
        database.collection("jobs").document(jobId).set(updated)
            .addOnFailureListener { e ->
                _jobs.value = previousJobs
                _syncError.value = "Failed to update job: ${e.message}"
            }
    }

    fun deleteJob(jobId: String) {
        val database = db ?: run {
            _syncError.value = "Please sign in to delete employers."
            return
        }
        val previousJobs = _jobs.value
        _jobs.value = _jobs.value.filter { it.id != jobId }
        database.collection("jobs").document(jobId).delete()
            .addOnFailureListener { e ->
                _jobs.value = previousJobs
                _syncError.value = "Failed to delete job: ${e.message}"
            }
    }

    fun reset() {
        loadedForUserId = null
        hasSeededDefaults = false
        jobsListenerRegistration?.remove()
        jobsListenerRegistration = null
        shiftsListenerRegistration?.remove()
        shiftsListenerRegistration = null
        profileListenerRegistration?.remove()
        profileListenerRegistration = null
        settingsListenerRegistration?.remove()
        settingsListenerRegistration = null
        adjustmentsListenerRegistration?.remove()
        adjustmentsListenerRegistration = null
        _shifts.value = emptyList()
        _jobs.value = emptyList()
        _payAdjustments.value = emptyList()
        _defaultCompany.value = ""
        _defaultRate.value = 0.0
        _userName.value = ""
        _memberSince.value = ""
        _isLoading.value = false
        _syncError.value = null
        _themeMode.value = "system"
    }

    fun loadShifts() {
        val uid = auth?.currentUser?.uid
        val database = db
        if (uid == null || database == null) {
            _isLoading.value = false
            _syncError.value = "Please sign in to access your data."
            return
        }
        if (loadedForUserId == uid) return
        loadedForUserId = uid
        _isLoading.value = true
        _syncError.value = null
        loadSettings()
        loadJobs()
        loadPayAdjustments()
        _userId.value = uid
        shiftsListenerRegistration?.remove()
        shiftsListenerRegistration = database.collection("shifts")
            .whereEqualTo("userId", uid)
            .addSnapshotListener { value, error ->
                _isLoading.value = false
                _isRefreshing.value = false
                if (error != null) {
                    _syncError.value = "Failed to load shifts: ${error.message}"
                    return@addSnapshotListener
                }
                if (value != null) {
                    val list = value.documents.mapNotNull { doc ->
                        doc.toObject(Shift::class.java)?.copy(id = doc.id)
                    }.sortedByDescending { it.startTime }
                    _shifts.value = list
                    val ctx = appContext ?: try { com.google.firebase.FirebaseApp.getInstance().applicationContext } catch (_: Exception) { null }
                    ctx?.let { c ->
                        try { WidgetDataProvider.updateWidgetData(c, list, resolveGlobalWeekStartDay()) } catch (_: Exception) {}
                    }
                }
            }
    }

    fun addShift(company: String, role: String, startTime: Long, endTime: Long, hourlyRate: Double) {
        addShift(company, startTime, endTime, hourlyRate, false, 0.0, 30, "")
    }

    fun addShift(company: String, startTime: Long, endTime: Long, hourlyRate: Double, isGig: Boolean, customEarned: Double, reminderBeforeMinutes: Int, notes: String = "", context: android.content.Context? = null, bonusApplied: Boolean = false, bonusAmount: Double = 0.0) {
        val uid = auth?.currentUser?.uid
        val database = db
        if (uid == null || database == null) {
            _syncError.value = "Please sign in to save shifts."
            return
        }
        val shift = Shift(
            id = java.util.UUID.randomUUID().toString(),
            userId = uid,
            company = company,
            role = "",
            startTime = startTime,
            endTime = endTime,
            hourlyRate = hourlyRate.coerceAtLeast(0.0),
            isGig = isGig,
            customEarned = customEarned.coerceAtLeast(0.0),
            reminderBeforeMinutes = reminderBeforeMinutes,
            isPaid = isGig,
            notes = notes,
            bonusApplied = bonusApplied,
            bonusAmount = bonusAmount
        )
        _shifts.value = (_shifts.value + shift).sortedByDescending { it.startTime }
        database.collection("shifts").document(shift.id).set(shift)
            .addOnSuccessListener {
                if (context != null) {
                    try { CalendarService.syncShiftToCalendar(context, shift) } catch (_: Exception) {}
                }
            }
            .addOnFailureListener { e ->
                _shifts.value = _shifts.value.filter { it.id != shift.id }
                _syncError.value = "Failed to save shift: ${e.message}"
            }
    }

    fun updateShift(shiftId: String, company: String, role: String, startTime: Long, endTime: Long, hourlyRate: Double) {
        updateShift(shiftId, company, startTime, endTime, hourlyRate, false, 0.0, 30, "")
    }

    fun updateShift(shiftId: String, company: String, startTime: Long, endTime: Long, hourlyRate: Double, isGig: Boolean, customEarned: Double, reminderBeforeMinutes: Int, notes: String = "", context: android.content.Context? = null, bonusApplied: Boolean = false, bonusAmount: Double = 0.0) {
        val shift = shifts.value.find { it.id == shiftId } ?: return
        val database = db
        if (database == null) {
            _syncError.value = "Please sign in to update shifts."
            return
        }
        val updated = shift.copy(
            company = company,
            role = "",
            startTime = startTime,
            endTime = endTime,
            hourlyRate = hourlyRate,
            isGig = isGig,
            customEarned = customEarned,
            reminderBeforeMinutes = reminderBeforeMinutes,
            isPaid = if (isGig) true else shift.isPaid,
            notes = notes,
            bonusApplied = bonusApplied,
            bonusAmount = bonusAmount
        )
        val previousShifts = _shifts.value
        _shifts.value = _shifts.value.map { if (it.id == shiftId) updated else it }.sortedByDescending { it.startTime }
        database.collection("shifts").document(shiftId).set(updated)
            .addOnSuccessListener {
                if (context != null) {
                    try { CalendarService.syncShiftToCalendar(context, updated) } catch (_: Exception) {}
                }
            }
            .addOnFailureListener { e ->
                _shifts.value = previousShifts
                _syncError.value = "Failed to update shift: ${e.message}"
            }
    }

    fun deleteShift(shiftId: String, context: android.content.Context? = null) {
        val database = db
        if (database == null) {
            _syncError.value = "Please sign in to delete shifts."
            return
        }
        val previousShifts = _shifts.value
        _shifts.value = _shifts.value.filter { it.id != shiftId }
        database.collection("shifts").document(shiftId).delete()
            .addOnSuccessListener {
                if (context != null) {
                    try { CalendarService.removeShiftFromCalendar(context, shiftId) } catch (_: Exception) {}
                    try { NotificationHelper.cancelReminder(context, shiftId) } catch (_: Exception) {}
                }
            }
            .addOnFailureListener { e ->
                _shifts.value = previousShifts
                _syncError.value = "Failed to delete shift: ${e.message}"
            }
    }

    fun toggleShiftPaidStatus(shiftId: String, isPaid: Boolean) {
        val database = db ?: run {
            _syncError.value = "Please sign in to update payment status."
            return
        }
        val shift = _shifts.value.firstOrNull { it.id == shiftId } ?: return
        val previousShifts = _shifts.value
        _shifts.value = _shifts.value.map { if (it.id == shiftId) it.copy(isPaid = isPaid) else it }
        database.collection("shifts").document(shiftId).set(shift.copy(isPaid = isPaid))
            .addOnFailureListener { e ->
                _shifts.value = previousShifts
                _syncError.value = "Failed to update payment status: ${e.message}"
            }
    }

    fun markCycleAsPaid(shiftIds: List<String>, isPaid: Boolean) {
        val database = db ?: run {
            _syncError.value = "Please sign in to update payment status."
            return
        }
        val shiftIdSet = shiftIds.toSet()
        val previousShifts = _shifts.value
        _shifts.value = _shifts.value.map { if (it.id in shiftIdSet) it.copy(isPaid = isPaid) else it }
        val batch = database.batch()
        val shiftsToWrite = previousShifts.filter { it.id in shiftIdSet }
        for (shift in shiftsToWrite) {
            val docRef = database.collection("shifts").document(shift.id)
            batch.set(docRef, shift.copy(isPaid = isPaid))
        }
        batch.commit()
            .addOnFailureListener { e ->
                _shifts.value = previousShifts
                _syncError.value = "Failed to mark cycle as paid: ${e.message}"
            }
    }

    fun generateFormattedReport(weekStartMillis: Long, employer: String?): String {
        val weekEndMillis = weekStartMillis + 7L * 24 * 60 * 60 * 1000L
        val filtered = _shifts.value.filter { shift ->
            shift.startTime >= weekStartMillis && shift.startTime < weekEndMillis &&
            (employer == null || employer == "All" || shift.company.equals(employer, ignoreCase = true))
        }.sortedBy { it.startTime }

        val sb = StringBuilder()
        val weekFormat = SimpleDateFormat("MMM dd", Locale.US)
        val dayFormat = SimpleDateFormat("EEEE (M/dd)", Locale.US)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
        sb.appendLine("Schedule: ${weekFormat.format(Date(weekStartMillis))} – ${weekFormat.format(Date(weekEndMillis - 1000L))}")
        if (employer != null && employer != "All") sb.appendLine("Employer: $employer")
        sb.appendLine()

        var totalHours = 0.0
        var totalEarnings = 0.0
        for (shift in filtered) {
            val day = dayFormat.format(Date(shift.startTime))
            val start = timeFormat.format(Date(shift.startTime))
            val end = timeFormat.format(Date(shift.endTime))
            val hrs = shift.durationHours
            totalHours += hrs
            totalEarnings += shift.totalEarned
            val line = "$day: $start – $end (${"%.1f".format(hrs)} hrs) $${"%.2f".format(shift.totalEarned)}"
            sb.appendLine(line)
            if (shift.notes.isNotBlank()) sb.appendLine("  Notes: ${shift.notes}")
        }
        sb.appendLine()
        sb.appendLine("Total ${"%.1f".format(totalHours)} hours · $${"%.2f".format(totalEarnings)}")
        if (filtered.any { it.isPaid }) {
            val paidCount = filtered.count { it.isPaid }
            sb.appendLine("Paid: $paidCount/${filtered.size} shifts")
        }
        return sb.toString()
    }

    fun generateCycleReport(cycleStart: Long, cycleEnd: Long, employer: String, job: Job?): String {
        val filtered = _shifts.value.filter { shift ->
            shift.startTime >= cycleStart && shift.startTime < cycleEnd &&
            shift.company.equals(employer, ignoreCase = true)
        }.sortedBy { it.startTime }

        val sb = StringBuilder()
        val weekFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        val dayFormat = SimpleDateFormat("EEEE (M/dd)", Locale.US)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
        sb.appendLine("TIMESHEET REPORT")
        sb.appendLine("Employer: $employer")
        sb.appendLine("Pay Period: ${weekFormat.format(Date(cycleStart))} – ${weekFormat.format(Date(cycleEnd - 1000L))}")
        if (job != null) sb.appendLine("Cycle Start Day: ${job.weeklyCycleStartDay ?: "Monday"}")
        sb.appendLine("─".repeat(40))

        var totalHours = 0.0
        var totalEarnings = 0.0
        for (shift in filtered) {
            val day = dayFormat.format(Date(shift.startTime))
            val start = timeFormat.format(Date(shift.startTime))
            val end = timeFormat.format(Date(shift.endTime))
            val hrs = shift.durationHours
            totalHours += hrs
            totalEarnings += shift.totalEarned
            val status = if (shift.isPaid) " [PAID]" else ""
            sb.appendLine("$day: $start – $end (${"%.1f".format(hrs)} hrs)$status")
            if (shift.notes.isNotBlank()) sb.appendLine("  Notes: ${shift.notes}")
        }

        sb.appendLine("─".repeat(40))

        if (job != null && !job.isGigWork) {
            val (regular, overtime) = calculateEarningsWithOvertime(filtered, job)
            val regularHours = totalHours.coerceAtMost(job.overtimeThresholdHours)
            val overtimeHours = (totalHours - regularHours).coerceAtLeast(0.0)
            // Rates shown are derived from the shifts' stored rates, not the
            // job's current defaultHourlyRate, so historical reports stay stable.
            val regularRate = if (regularHours > 0) regular / regularHours else job.defaultHourlyRate
            sb.appendLine("Regular: ${"%.1f".format(regularHours)} hrs × $${"%.2f".format(regularRate)} = $${"%.2f".format(regular)}")
            if (overtimeHours > 0) {
                sb.appendLine("Overtime: ${"%.1f".format(overtimeHours)} hrs × $${"%.2f".format(overtime / overtimeHours)} = $${"%.2f".format(overtime)}")
            }
            sb.appendLine("TOTAL: ${"%.1f".format(totalHours)} hours · $${"%.2f".format(regular + overtime)}")
        } else {
            sb.appendLine("TOTAL: ${"%.1f".format(totalHours)} hours · $${"%.2f".format(totalEarnings)}")
        }

        val paidCount = filtered.count { it.isPaid }
        sb.appendLine("Payment Status: $paidCount/${filtered.size} shifts paid")
        return sb.toString()
    }

    fun generateCycleCsvReport(cycleStart: Long, cycleEnd: Long, employer: String, job: Job?): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
        val filtered = _shifts.value.filter { shift ->
            shift.startTime >= cycleStart && shift.startTime < cycleEnd &&
            shift.company.equals(employer, ignoreCase = true)
        }.sortedBy { it.startTime }

        val sb = StringBuilder()
        sb.appendLine("Date,Company,Start,End,Hours,Rate,Earned,Gig,Paid,Notes")
        filtered.forEach { s ->
            val company = csvEscape(s.company)
            val notes = csvEscape(s.notes)
            sb.appendLine("${dateFormat.format(Date(s.startTime))},$company,${timeFormat.format(Date(s.startTime))},${timeFormat.format(Date(s.endTime))},${"%.2f".format(s.durationHours)},${s.hourlyRate},${"%.2f".format(s.totalEarned)},${s.isGig},${s.isPaid},$notes")
        }
        return sb.toString()
    }

    private fun csvEscape(value: String): String {
        val cleaned = value.replace("\n", " ")
        return if (cleaned.contains(",") || cleaned.contains("\"") || cleaned.startsWith("=") || cleaned.startsWith("+") || cleaned.startsWith("-") || cleaned.startsWith("@")) {
            "\"${cleaned.replace("\"", "\"\"")}\""
        } else cleaned
    }

    data class PayCycleOption(val cycleStart: Long, val cycleEnd: Long, val employer: String, val label: String, val shiftCount: Int, val isCurrent: Boolean)

    fun getAvailablePayCycles(): List<PayCycleOption> {
        val now = System.currentTimeMillis()
        val weekFormat = SimpleDateFormat("MMM dd", Locale.US)
        val allJobs = _jobs.value
        val allShifts = _shifts.value

        val cycles = mutableListOf<PayCycleOption>()

        for (job in allJobs) {
            val jobShifts = allShifts.filter { it.company.equals(job.title, ignoreCase = true) && !it.isGig }
            if (jobShifts.isEmpty()) continue

            val seenCycles = mutableSetOf<Long>()
            for (shift in jobShifts) {
                val (start, end) = getCycleStartAndEndForShift(shift, allJobs)
                if (seenCycles.add(start)) {
                    val shiftsInCycle = jobShifts.count { it.startTime >= start && it.startTime < end }
                    val isCurrent = now in start until end
                    val label = "${job.title}: ${weekFormat.format(Date(start))} – ${weekFormat.format(Date(end - 1000L))}" +
                        if (isCurrent) " (Current)" else ""
                    cycles.add(PayCycleOption(start, end, job.title, label, shiftsInCycle, isCurrent))
                }
            }

            val currentCycle = payCycleFor(job, now)
            val currentCycleStart = currentCycle.startMillis
            val currentCycleEnd = currentCycle.endMillis
            if (seenCycles.add(currentCycleStart)) {
                val label = "${job.title}: ${weekFormat.format(Date(currentCycleStart))} – ${weekFormat.format(Date(currentCycleEnd - 1000L))} (Current)"
                cycles.add(PayCycleOption(currentCycleStart, currentCycleEnd, job.title, label, 0, true))
            }
        }

        return cycles.sortedWith(compareByDescending<PayCycleOption> { it.cycleStart }.thenBy { it.employer })
    }

    // Week-start day for views that aggregate across jobs: the most common
    // weeklyCycleStartDay among non-gig jobs, ties broken by earliest day
    // (Sunday first), Monday when there are no non-gig jobs. Deterministic
    // regardless of Firestore result order.
    fun resolveGlobalWeekStartDay(): String {
        val dayOrder = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val counts = _jobs.value.asSequence()
            .filter { !it.isGigWork }
            .map { job -> dayOrder.firstOrNull { it.equals(job.weeklyCycleStartDay ?: "Monday", ignoreCase = true) } ?: "Monday" }
            .groupingBy { it }
            .eachCount()
        if (counts.isEmpty()) return "Monday"
        val maxCount = counts.values.max()
        return dayOrder.first { counts[it] == maxCount }
    }

    fun getAvailableWeeks(): List<Pair<Long, String>> {
        val weekFormat = SimpleDateFormat("MMM dd", Locale.US)
        val weeks = mutableSetOf<Long>()
        val now = System.currentTimeMillis()
        val anchor = startOfWeekContaining(now, resolveGlobalWeekStartDay())
        for (offset in -8..4) {
            weeks.add(anchor + offset * 7L * 24 * 60 * 60 * 1000L)
        }
        return weeks.sorted().map { start ->
            val end = start + 7L * 24 * 60 * 60 * 1000L
            val label = "${weekFormat.format(Date(start))} – ${weekFormat.format(Date(end - 1000L))}" +
                if (now in start until end) " (Current)" else ""
            Pair(start, label)
        }.reversed()
    }

    data class WeekDayEntry(val dayOffset: Int, val startH: Int, val startM: Int, val endH: Int, val endM: Int)

    fun addWeekPlan(company: String, hourlyRate: Double, isGig: Boolean, customEarned: Double, reminderMinutes: Int, weekStartMillis: Long, dayEntries: List<Triple<Int, Int, Int>>) {
        addWeekPlanWithMinutes(company, hourlyRate, isGig, customEarned, reminderMinutes, weekStartMillis,
            dayEntries.map { (d, s, e) -> WeekDayEntry(d, s, 0, e, 0) })
    }

    fun addWeekPlanWithMinutes(company: String, hourlyRate: Double, isGig: Boolean, customEarned: Double, reminderMinutes: Int, weekStartMillis: Long, dayEntries: List<WeekDayEntry>) {
        val existingShifts = _shifts.value
        val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        for (entry in dayEntries) {
            val dayMillis = weekStartMillis + entry.dayOffset.toLong() * 24 * 60 * 60 * 1000L
            val dateKey = dayFormat.format(Date(dayMillis))
            val alreadyExists = existingShifts.any {
                it.company.equals(company, ignoreCase = true) &&
                dayFormat.format(Date(it.startTime)) == dateKey
            }
            if (alreadyExists) continue

            val calStart = Calendar.getInstance().apply {
                timeInMillis = dayMillis
                set(Calendar.HOUR_OF_DAY, entry.startH)
                set(Calendar.MINUTE, entry.startM)
                set(Calendar.SECOND, 0)
            }
            val calEnd = Calendar.getInstance().apply {
                timeInMillis = dayMillis
                set(Calendar.HOUR_OF_DAY, entry.endH)
                set(Calendar.MINUTE, entry.endM)
                set(Calendar.SECOND, 0)
            }
            var endTime = calEnd.timeInMillis
            if (endTime <= calStart.timeInMillis) endTime += 86400000L

            addShift(company, calStart.timeInMillis, endTime, hourlyRate, isGig, customEarned, reminderMinutes)
        }
    }

    fun updateUserName(newName: String) {
        val uid = auth?.currentUser?.uid ?: return
        val database = db ?: return
        database.collection("profiles").document(uid)
            .update("full_name", newName)
            .addOnSuccessListener { _userName.value = newName }
            .addOnFailureListener { e ->
                _syncError.value = "Failed to update name: ${e.message}"
            }
    }

    fun getEarningsByEmployer(): Map<String, Double> {
        val now = System.currentTimeMillis()
        return _shifts.value.filter { it.startTime < now }
            .groupBy { it.company }
            .mapValues { (_, shifts) -> shifts.sumOf { it.totalEarned } }
            .toList().sortedByDescending { it.second }.toMap()
    }

    // Generic earnings bucket for the Insights chart — a calendar week or month.
    data class PeriodSummary(
        val periodStart: Long,
        val periodEnd: Long,
        val label: String,
        val hours: Double,
        val earnings: Double,
        val shiftCount: Int
    )

    data class UpcomingProjection(
        val earnings: Double,
        val hours: Double,
        val shiftCount: Int,
        val nextShiftStart: Long?
    )

    fun getWeeklyPeriodSummary(weeks: Int = 8, employer: String? = null): List<PeriodSummary> {
        val weekFormat = SimpleDateFormat("MMM dd", Locale.US)
        val now = System.currentTimeMillis()
        val completedShifts = shiftsForEmployer(employer).filter { it.startTime < now }
        val weekStartDay = if (employer != null) {
            _jobs.value.firstOrNull { it.title.equals(employer, ignoreCase = true) }?.weeklyCycleStartDay ?: "Monday"
        } else {
            resolveGlobalWeekStartDay()
        }
        val anchor = startOfWeekContaining(now, weekStartDay)
        return (0 until weeks).map { offset ->
            val weekStart = anchor - offset * 7L * 24 * 60 * 60 * 1000L
            val weekEnd = weekStart + 7L * 24 * 60 * 60 * 1000L
            val weekShifts = completedShifts.filter { it.startTime in weekStart until weekEnd }
            PeriodSummary(
                periodStart = weekStart,
                periodEnd = weekEnd,
                label = weekFormat.format(Date(weekStart)),
                hours = weekShifts.sumOf { it.durationHours },
                earnings = weekShifts.sumOf { it.totalEarned },
                shiftCount = weekShifts.size
            )
        }.reversed()
    }

    fun getMonthlyPeriodSummary(months: Int = 6, employer: String? = null): List<PeriodSummary> {
        val monthFormat = SimpleDateFormat("MMM", Locale.US)
        val now = System.currentTimeMillis()
        val completedShifts = shiftsForEmployer(employer).filter { it.startTime < now }
        return (0 until months).map { offset ->
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.MONTH, -offset)
            }
            val monthStart = cal.timeInMillis
            val monthEnd = (cal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
            val monthShifts = completedShifts.filter { it.startTime in monthStart until monthEnd }
            PeriodSummary(
                periodStart = monthStart,
                periodEnd = monthEnd,
                label = monthFormat.format(Date(monthStart)),
                hours = monthShifts.sumOf { it.durationHours },
                earnings = monthShifts.sumOf { it.totalEarned },
                shiftCount = monthShifts.size
            )
        }.reversed()
    }

    fun getUpcomingProjection(employer: String? = null): UpcomingProjection {
        val now = System.currentTimeMillis()
        val upcoming = shiftsForEmployer(employer).filter { it.startTime >= now }
        return UpcomingProjection(
            earnings = upcoming.sumOf { it.totalEarned },
            hours = upcoming.sumOf { it.durationHours },
            shiftCount = upcoming.size,
            nextShiftStart = upcoming.minOfOrNull { it.startTime }
        )
    }

    fun getShiftsInPeriod(start: Long, end: Long, employer: String? = null): List<Shift> =
        shiftsForEmployer(employer)
            .filter { it.startTime in start until end }
            .sortedByDescending { it.startTime }

    private fun shiftsForEmployer(employer: String?): List<Shift> =
        if (employer.isNullOrBlank()) _shifts.value
        else _shifts.value.filter { it.company.equals(employer, ignoreCase = true) }

    fun generateCsvReport(weekStart: Long, employer: String): String {
        val weekEnd = weekStart + 7L * 24 * 60 * 60 * 1000L
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
        val filtered = _shifts.value.filter { shift ->
            shift.startTime in weekStart until weekEnd &&
                (employer == "All" || shift.company.equals(employer, ignoreCase = true))
        }.sortedBy { it.startTime }

        val sb = StringBuilder()
        sb.appendLine("Date,Company,Start,End,Hours,Rate,Earned,Gig,Paid,Notes")
        filtered.forEach { s ->
            val company = csvEscape(s.company)
            val notes = csvEscape(s.notes)
            sb.appendLine("${dateFormat.format(Date(s.startTime))},$company,${timeFormat.format(Date(s.startTime))},${timeFormat.format(Date(s.endTime))},${"%.2f".format(s.durationHours)},${s.hourlyRate},${"%.2f".format(s.totalEarned)},${s.isGig},${s.isPaid},$notes")
        }
        return sb.toString()
    }

    private fun loadPayAdjustments() {
        val uid = auth?.currentUser?.uid ?: return
        val database = db ?: return
        adjustmentsListenerRegistration?.remove()
        adjustmentsListenerRegistration = database.collection("pay_adjustments")
            .whereEqualTo("userId", uid)
            .addSnapshotListener { value, error ->
                if (error != null) {
                    _syncError.value = "Failed to load pay adjustments: ${error.message}"
                    return@addSnapshotListener
                }
                if (value != null) {
                    _payAdjustments.value = value.documents.mapNotNull { doc ->
                        doc.toObject(PayAdjustment::class.java)?.copy(id = doc.id)
                    }
                }
            }
    }

    fun addPayAdjustment(cycleKey: String, employer: String, type: String, amount: Double, notes: String) {
        val uid = auth?.currentUser?.uid
        val database = db
        if (uid == null || database == null) {
            _syncError.value = "Please sign in to add adjustments."
            return
        }
        val adjustment = PayAdjustment(
            userId = uid,
            cycleKey = cycleKey,
            employer = employer,
            type = type,
            amount = amount,
            notes = notes
        )
        _payAdjustments.value = _payAdjustments.value + adjustment
        database.collection("pay_adjustments").document(adjustment.id).set(adjustment)
            .addOnFailureListener { e ->
                _payAdjustments.value = _payAdjustments.value.filter { it.id != adjustment.id }
                _syncError.value = "Failed to save adjustment: ${e.message}"
            }
    }

    fun deletePayAdjustment(adjustmentId: String) {
        val database = db ?: return
        val previousAdjustments = _payAdjustments.value
        _payAdjustments.value = _payAdjustments.value.filter { it.id != adjustmentId }
        database.collection("pay_adjustments").document(adjustmentId).delete()
            .addOnFailureListener { e ->
                _payAdjustments.value = previousAdjustments
                _syncError.value = "Failed to delete adjustment: ${e.message}"
            }
    }

    fun getAdjustmentsForCycle(cycleKey: String): List<PayAdjustment> {
        return _payAdjustments.value.filter { it.cycleKey == cycleKey }
    }

    override fun onCleared() {
        super.onCleared()
        jobsListenerRegistration?.remove()
        shiftsListenerRegistration?.remove()
        profileListenerRegistration?.remove()
        settingsListenerRegistration?.remove()
        adjustmentsListenerRegistration?.remove()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddShiftScreen(
    shiftId: String? = null,
    viewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    val jobs by viewModel.jobs.collectAsState()
    val shifts by viewModel.shifts.collectAsState()
    val existingShift = shiftId?.let { id -> shifts.find { it.id == id } }

    var selectedJob by remember(jobs, existingShift) {
        mutableStateOf(
            if (existingShift != null) {
                jobs.find { it.title.equals(existingShift.company, ignoreCase = true) }
            } else if (jobs.isNotEmpty()) {
                jobs.first()
            } else {
                null
            }
        )
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    var company by remember(selectedJob) { mutableStateOf(selectedJob?.title ?: existingShift?.company ?: "") }
    var isGig by remember(selectedJob) { mutableStateOf(selectedJob?.isGigWork ?: existingShift?.isGig ?: false) }
    var rate by remember(selectedJob, existingShift) {
        mutableStateOf(
            existingShift?.hourlyRate?.toString() 
                ?: selectedJob?.defaultHourlyRate?.toString() 
                ?: "0.0"
        )
    }
    var customEarnings by remember(existingShift) {
        mutableStateOf(existingShift?.customEarned?.toString() ?: "")
    }
    val defaultReminderMin by viewModel.defaultReminderMinutes.collectAsState()
    var reminderMinutes by remember(existingShift, defaultReminderMin) {
        mutableStateOf(existingShift?.reminderBeforeMinutes ?: defaultReminderMin)
    }
    var shiftNotes by remember(existingShift) {
        mutableStateOf(existingShift?.notes ?: "")
    }
    var applyBonus by remember(selectedJob, existingShift) {
        mutableStateOf(existingShift?.bonusApplied ?: false)
    }

    var selectedDateMillis by remember { mutableStateOf(existingShift?.startTime ?: System.currentTimeMillis()) }
    var startHour by remember { mutableStateOf(existingShift?.let { Calendar.getInstance().apply { timeInMillis = it.startTime }.get(Calendar.HOUR_OF_DAY) } ?: 9) }
    var startMinute by remember { mutableStateOf(existingShift?.let { Calendar.getInstance().apply { timeInMillis = it.startTime }.get(Calendar.MINUTE) } ?: 0) }
    var endHour by remember { mutableStateOf(existingShift?.let { Calendar.getInstance().apply { timeInMillis = it.endTime }.get(Calendar.HOUR_OF_DAY) } ?: 17) }
    var endMinute by remember { mutableStateOf(existingShift?.let { Calendar.getInstance().apply { timeInMillis = it.endTime }.get(Calendar.MINUTE) } ?: 0) }

    var recurrence by remember { mutableStateOf("None") }
    var recurrenceWeeks by remember { mutableStateOf("4") }

    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    var expandedCompany by remember { mutableStateOf(false) }

    val initialUtcMillis = remember {
        val localCal = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(localCal.get(Calendar.YEAR), localCal.get(Calendar.MONTH), localCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }
        utcCal.timeInMillis
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)
    val startTimePickerState = rememberTimePickerState(initialHour = startHour, initialMinute = startMinute)
    val endTimePickerState = rememberTimePickerState(initialHour = endHour, initialMinute = endMinute)

    val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)
    
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { utcMs ->
                        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        cal.timeInMillis = utcMs
                        val localCal = Calendar.getInstance()
                        localCal.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
                        selectedDateMillis = localCal.timeInMillis
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showStartTimePicker) {
        AlertDialog(
            onDismissRequest = { showStartTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    startHour = startTimePickerState.hour
                    startMinute = startTimePickerState.minute
                    showStartTimePicker = false
                }) { Text("OK") }
            },
            text = { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = startTimePickerState) } }
        )
    }

    if (showEndTimePicker) {
        AlertDialog(
            onDismissRequest = { showEndTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    endHour = endTimePickerState.hour
                    endMinute = endTimePickerState.minute
                    showEndTimePicker = false
                }) { Text("OK") }
            },
            text = { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = endTimePickerState) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingShift != null) "Edit Shift" else "Add Shift") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (existingShift != null) {
                        IconButton(onClick = { 
                            viewModel.deleteShift(existingShift.id, context)
                            onBack()
                        }) {
                            Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text("Select Job", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = company.ifEmpty { "Select a Job..." },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Job") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCompany) }
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { expandedCompany = true }
                )
                if (jobs.isNotEmpty()) {
                    DropdownMenu(
                        expanded = expandedCompany,
                        onDismissRequest = { expandedCompany = false }
                    ) {
                        jobs.forEach { job ->
                            DropdownMenuItem(
                                text = { Text("${job.title} (${if (job.isGigWork) "Gig" else "$${"%.2f".format(job.defaultHourlyRate)}/hr"})") },
                                onClick = {
                                    selectedJob = job
                                    company = job.title
                                    isGig = job.isGigWork
                                    rate = job.defaultHourlyRate.toString()
                                    expandedCompany = false
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Pay representation
            if (isGig) {
                OutlinedTextField(
                    value = customEarnings,
                    onValueChange = { customEarnings = it },
                    label = { Text("Shift Earnings ($) [Gig Work]") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            } else {
                OutlinedTextField(
                    value = rate,
                    onValueChange = { rate = it },
                    label = { Text("Hourly Rate ($)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (!isGig && selectedJob != null && selectedJob!!.bonusAmount > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Apply Bonus: +$${String.format("%.2f", selectedJob!!.bonusAmount)}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        if (selectedJob!!.bonusReason.isNotBlank()) {
                            Text(selectedJob!!.bonusReason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Switch(checked = applyBonus, onCheckedChange = { applyBonus = it })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Date Picker Field
            OutlinedTextField(
                value = dateFormat.format(Date(selectedDateMillis)),
                onValueChange = { },
                label = { Text("Date") },
                enabled = false,
                modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true },
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Start Time Field
                OutlinedTextField(
                    value = String.format(Locale.US, "%02d:%02d", startHour, startMinute),
                    onValueChange = { },
                    label = { Text("Start Time") },
                    enabled = false,
                    modifier = Modifier.weight(1f).clickable { showStartTimePicker = true },
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                // End Time Field
                OutlinedTextField(
                    value = String.format(Locale.US, "%02d:%02d", endHour, endMinute),
                    onValueChange = { },
                    label = { Text("End Time") },
                    enabled = false,
                    modifier = Modifier.weight(1f).clickable { showEndTimePicker = true },
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Reminder selector
            Text("Remind Me", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0 to "None", 15 to "15m", 30 to "30m", 60 to "1h").forEach { (minutes, label) ->
                    val selected = reminderMinutes == minutes
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) PrimaryGreen else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { reminderMinutes = minutes }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            if (existingShift == null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Repeat Shift", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("None", "Daily", "Weekly", "Biweekly").forEach { option ->
                        val selected = recurrence == option
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) PrimaryGreen else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { recurrence = option }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = option,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                if (recurrence != "None") {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = recurrenceWeeks,
                        onValueChange = { recurrenceWeeks = it },
                        label = { Text("Repeat for (weeks)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = shiftNotes,
                onValueChange = { if (it.length <= 1000) shiftNotes = it },
                label = { Text("Notes (optional)") },
                placeholder = { Text("Parking instructions, door code, etc.") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                minLines = 2,
                maxLines = 4
            )

            val previewCalStart = remember(selectedDateMillis, startHour, startMinute) {
                Calendar.getInstance().apply { timeInMillis = selectedDateMillis; set(Calendar.HOUR_OF_DAY, startHour); set(Calendar.MINUTE, startMinute); set(Calendar.SECOND, 0) }.timeInMillis
            }
            val previewCalEnd = remember(selectedDateMillis, endHour, endMinute, previewCalStart) {
                val end = Calendar.getInstance().apply { timeInMillis = selectedDateMillis; set(Calendar.HOUR_OF_DAY, endHour); set(Calendar.MINUTE, endMinute); set(Calendar.SECOND, 0) }.timeInMillis
                if (end < previewCalStart) end + 86400000L else end
            }
            val conflicts = remember(previewCalStart, previewCalEnd, shifts) {
                viewModel.detectConflicts(previewCalStart, previewCalEnd, existingShift?.id)
            }
            if (conflicts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Shift Overlap Detected", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF92400E))
                            conflicts.forEach { conflict ->
                                val fmt = SimpleDateFormat("MMM dd h:mm a", Locale.US)
                                Text("${conflict.company}: ${fmt.format(Date(conflict.startTime))} - ${fmt.format(Date(conflict.endTime))}", fontSize = 12.sp, color = Color(0xFF92400E))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    val calStart = Calendar.getInstance().apply {
                        timeInMillis = selectedDateMillis
                        set(Calendar.HOUR_OF_DAY, startHour)
                        set(Calendar.MINUTE, startMinute)
                        set(Calendar.SECOND, 0)
                    }
                    val calEnd = Calendar.getInstance().apply {
                        timeInMillis = selectedDateMillis
                        set(Calendar.HOUR_OF_DAY, endHour)
                        set(Calendar.MINUTE, endMinute)
                        set(Calendar.SECOND, 0)
                    }
                    var finalEndTime = calEnd.timeInMillis
                    if (finalEndTime < calStart.timeInMillis) {
                        finalEndTime += 86400000L
                    }
                    val hourly = if (isGig) 0.0 else (rate.toDoubleOrNull() ?: 0.0)
                    val earned = if (isGig) (customEarnings.toDoubleOrNull() ?: 0.0) else 0.0
                    val trimmedNotes = shiftNotes.trim()
                    val remindersOn = viewModel.remindersEnabled.value
                    val effectiveReminder = if (remindersOn) reminderMinutes else 0

                    val shiftBonusApplied = applyBonus && !isGig && selectedJob != null && selectedJob!!.bonusAmount > 0
                    val shiftBonusAmount = if (shiftBonusApplied) selectedJob!!.bonusAmount else 0.0

                    if (existingShift != null) {
                        viewModel.updateShift(existingShift.id, company, calStart.timeInMillis, finalEndTime, hourly, isGig, earned, effectiveReminder, trimmedNotes, context, shiftBonusApplied, shiftBonusAmount)
                        if (effectiveReminder > 0) {
                            NotificationHelper.scheduleReminder(context, Shift(id = existingShift.id, company = company, startTime = calStart.timeInMillis, endTime = finalEndTime, reminderBeforeMinutes = effectiveReminder))
                        } else {
                            NotificationHelper.cancelReminder(context, existingShift.id)
                        }
                    } else {
                        viewModel.addShift(company, calStart.timeInMillis, finalEndTime, hourly, isGig, earned, effectiveReminder, trimmedNotes, context, shiftBonusApplied, shiftBonusAmount)
                        if (effectiveReminder > 0) {
                            NotificationHelper.scheduleReminder(context, Shift(company = company, startTime = calStart.timeInMillis, endTime = finalEndTime, reminderBeforeMinutes = effectiveReminder))
                        }
                        if (recurrence != "None") {
                            val weeks = recurrenceWeeks.toIntOrNull()?.coerceIn(1, 52) ?: 4
                            val dayIncrement = when (recurrence) {
                                "Daily" -> 1
                                "Weekly" -> 7
                                "Biweekly" -> 14
                                else -> 0
                            }
                            val totalOccurrences = if (recurrence == "Daily") weeks * 7 else weeks
                            val shiftDuration = finalEndTime - calStart.timeInMillis
                            for (i in 1..totalOccurrences) {
                                val recurStart = Calendar.getInstance().apply {
                                    timeInMillis = calStart.timeInMillis
                                    add(Calendar.DAY_OF_YEAR, i * dayIncrement)
                                }.timeInMillis
                                val recurEnd = recurStart + shiftDuration
                                val recurShift = Shift(company = company, startTime = recurStart, endTime = recurEnd, reminderBeforeMinutes = effectiveReminder)
                                viewModel.addShift(company, recurStart, recurEnd, hourly, isGig, earned, effectiveReminder, trimmedNotes, context, shiftBonusApplied, shiftBonusAmount)
                                if (effectiveReminder > 0) NotificationHelper.scheduleReminder(context, recurShift)
                            }
                        }
                    }
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen)
            ) {
                Text(if (existingShift != null) "Update Shift" else "Save Shift", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
