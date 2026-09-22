package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.fragment.app.FragmentActivity
import com.example.ui.theme.*
import com.schedulo.shared.logic.proRateWeeklyGoal
import com.schedulo.shared.model.PayFrequency

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import java.util.Calendar
import java.util.Locale
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

// FragmentActivity (not plain ComponentActivity) is required because
// androidx.biometric.BiometricPrompt hosts its dialog via a Fragment.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Read the biometric preference before the first composition so the lock
        // screen gate is already decided when the UI first renders (avoids a
        // dashboard flash before the gate kicks in).
        ViewModelProvider(this)[AuthViewModel::class.java].initBiometricPreference(this)

        setContent {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {}
                LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }

            val navController = rememberNavController()
            val authViewModel: AuthViewModel = viewModel()
            val authState by authViewModel.authState.collectAsState()
            val biometricLockActive by authViewModel.biometricLockActive.collectAsState()
            val dashboardViewModel: DashboardViewModel = viewModel()
            val teamViewModel: TeamViewModel = viewModel()
            val feedbackViewModel: FeedbackViewModel = viewModel()
            dashboardViewModel.setAppContext(this@MainActivity)

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        "team_chat", "Team Chat", android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Team chat message notifications"
                        enableVibration(true)
                    }
                    val scheduleChannel = android.app.NotificationChannel(
                        "team_schedule", "Team Schedule", android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "New team schedule assignment notifications"
                        enableVibration(true)
                    }
                    androidx.core.app.NotificationManagerCompat.from(this@MainActivity).apply {
                        createNotificationChannel(channel)
                        createNotificationChannel(scheduleChannel)
                    }
                }

                teamViewModel.chatNotificationCallback = { sender, body ->
                    // Tapping the notification opens the app.
                    val openIntent = android.content.Intent(this@MainActivity, MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this@MainActivity, 0, openIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    val notification = androidx.core.app.NotificationCompat.Builder(this@MainActivity, "team_chat")
                        .setSmallIcon(android.R.drawable.ic_dialog_email)
                        .setContentTitle(sender.ifBlank { "Team Chat" })
                        .setContentText(body)
                        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .build()
                    val nm = androidx.core.app.NotificationManagerCompat.from(this@MainActivity)
                    if (nm.areNotificationsEnabled()) {
                        try {
                            nm.notify(System.currentTimeMillis().toInt(), notification)
                        } catch (_: SecurityException) { }
                    }
                }

                teamViewModel.scheduleNotificationCallback = { teamName, company, startTime, endTime ->
                    val dateFormat = java.text.SimpleDateFormat("EEE, MMM dd · h:mm a", java.util.Locale.US)
                    val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
                    val dayStamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    val sameDay = dayStamp.format(java.util.Date(startTime)) == dayStamp.format(java.util.Date(endTime))
                    val endLabel = if (sameDay) timeFormat.format(java.util.Date(endTime))
                        else dateFormat.format(java.util.Date(endTime))
                    val body = "$company: ${dateFormat.format(java.util.Date(startTime))} – $endLabel"
                    val openIntent = android.content.Intent(this@MainActivity, MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this@MainActivity, 1, openIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    val notification = androidx.core.app.NotificationCompat.Builder(this@MainActivity, "team_schedule")
                        .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                        .setContentTitle(if (teamName.isBlank()) "New shift scheduled" else "New shift scheduled — $teamName")
                        .setContentText(body)
                        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_EVENT)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .build()
                    val nm2 = androidx.core.app.NotificationManagerCompat.from(this@MainActivity)
                    if (nm2.areNotificationsEnabled()) {
                        try {
                            nm2.notify(System.currentTimeMillis().toInt(), notification)
                        } catch (_: SecurityException) { }
                    }
                }
            }

            // Keyed on the signed-in uid rather than authState, because authState
            // also carries transient errors (a biometric sensor failure) that
            // leave the session perfectly valid — tearing listeners down there
            // would blank a signed-in user's screen.
            //
            // (Re)attach the schedule-notifications listener whenever the user
            // changes; a listener started before login has no uid to watch. When
            // the uid goes away, drop every team listener and its state: these
            // view models are activity-scoped and outlive a sign-out, so anything
            // still attached keeps streaming the previous account's team data to
            // whoever signs in next. Owning that here rather than in each logout
            // button means no sign-out path — logout, account deletion, or a
            // session revoked server-side — can miss it.
            val signedInUserId by authViewModel.currentUserId.collectAsState()
            LaunchedEffect(signedInUserId) {
                if (signedInUserId.isNotEmpty()) {
                    teamViewModel.startScheduleNotificationsListener(this@MainActivity)
                } else {
                    teamViewModel.reset()
                    dashboardViewModel.reset()
                }
            }
            val themeMode by dashboardViewModel.themeMode.collectAsState()

            MyApplicationTheme(themeMode = themeMode) {
                if (biometricLockActive) {
                    BiometricUnlockScreen(
                        viewModel = authViewModel,
                        onUnlocked = { authViewModel.dismissBiometricLock() }
                    )
                } else {
                    val startDestination = remember {
                        if (authState is AuthState.Authenticated) "dashboard" else "login"
                    }

                    NavHost(
                        navController = navController,
                        startDestination = startDestination,
                        enterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally { it / 4 } },
                        exitTransition = { fadeOut(animationSpec = tween(300)) + slideOutHorizontally { -it / 4 } },
                        popEnterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally { -it / 4 } },
                        popExitTransition = { fadeOut(animationSpec = tween(300)) + slideOutHorizontally { it / 4 } }
                    ) {
                        composable("login") {
                            LoginScreen(
                                viewModel = authViewModel,
                                onNavigateToSignup = { navController.navigate("signup") },
                                onNavigateToDashboard = {
                                    navController.navigate("dashboard") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("signup") {
                            SignupScreen(
                                viewModel = authViewModel,
                                onNavigateToLogin = { navController.navigate("login") },
                                onNavigateToDashboard = {
                                    navController.navigate("dashboard") {
                                        popUpTo("signup") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("dashboard",
                            enterTransition = { fadeIn(tween(250)) },
                            exitTransition = { fadeOut(tween(250)) },
                            popEnterTransition = { fadeIn(tween(250)) },
                            popExitTransition = { fadeOut(tween(250)) }
                        ) { MainLayout(navController, "dashboard", authViewModel, dashboardViewModel, teamViewModel) }
                        composable("plan",
                            enterTransition = { fadeIn(tween(250)) },
                            exitTransition = { fadeOut(tween(250)) },
                            popEnterTransition = { fadeIn(tween(250)) },
                            popExitTransition = { fadeOut(tween(250)) }
                        ) { MainLayout(navController, "plan", authViewModel, dashboardViewModel, teamViewModel) }
                        composable("pay",
                            enterTransition = { fadeIn(tween(250)) },
                            exitTransition = { fadeOut(tween(250)) },
                            popEnterTransition = { fadeIn(tween(250)) },
                            popExitTransition = { fadeOut(tween(250)) }
                        ) { MainLayout(navController, "pay", authViewModel, dashboardViewModel, teamViewModel) }
                        composable("team",
                            enterTransition = { fadeIn(tween(250)) },
                            exitTransition = { fadeOut(tween(250)) },
                            popEnterTransition = { fadeIn(tween(250)) },
                            popExitTransition = { fadeOut(tween(250)) }
                        ) { MainLayout(navController, "team", authViewModel, dashboardViewModel, teamViewModel) }
                        composable("jobs") {
                            JobsScreen(
                                modifier = Modifier,
                                dashboardViewModel = dashboardViewModel,
                                onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() }
                            )
                        }
                        composable("profile") {
                            ProfileScreen(dashboardViewModel = dashboardViewModel, authViewModel = authViewModel, onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() }, onNavigateToInsights = { navController.navigate("insights") }, onNavigateToJobs = { navController.navigate("jobs") }, onNavigateToFeedback = { navController.navigate("feedback") })
                        }
                        composable("feedback") {
                            SendFeedbackScreen(viewModel = feedbackViewModel, onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() })
                        }
                        composable("insights") {
                            InsightsScreen(dashboardViewModel = dashboardViewModel, onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() })
                        }
                        composable("add_week_plan") {
                            AddWeekPlanScreen(viewModel = dashboardViewModel, onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() })
                        }
                        composable(
                            route = "add_shift?shiftId={shiftId}",
                            arguments = listOf(androidx.navigation.navArgument("shiftId") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                            })
                        ) { backStackEntry ->
                            val shiftId = backStackEntry.arguments?.getString("shiftId")
                            AddShiftScreen(
                                shiftId = shiftId,
                                viewModel = dashboardViewModel,
                                onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() }
                            )
                        }
                        composable(
                            route = "team_detail/{section}",
                            arguments = listOf(androidx.navigation.navArgument("section") {
                                type = androidx.navigation.NavType.StringType
                            })
                        ) { backStackEntry ->
                            val section = backStackEntry.arguments?.getString("section") ?: "dashboard"
                            TeamDetailScreen(
                                section = section,
                                teamViewModel = teamViewModel,
                                authViewModel = authViewModel,
                                dashboardViewModel = dashboardViewModel,
                                onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun weekRangeLabel(offset: Int, weekStartDay: String = "Monday"): String {
    val cal = Calendar.getInstance().apply {
        timeInMillis = startOfWeekContaining(System.currentTimeMillis(), weekStartDay)
        add(Calendar.WEEK_OF_YEAR, offset)
    }
    val fmt = java.text.SimpleDateFormat("MMM dd", Locale.US)
    val start = fmt.format(cal.time)
    cal.add(Calendar.DAY_OF_YEAR, 6)
    val end = fmt.format(cal.time)
    return "$start – $end"
}

private fun formatShiftDuration(hours: Double): String =
    if (hours % 1.0 == 0.0) "${hours.toInt()}h" else "${"%.1f".format(hours)}h"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    authViewModel: AuthViewModel? = null,
    dashboardViewModel: DashboardViewModel? = null,
    onNavigateToLogin: (() -> Unit)? = null,
    onEditShift: (String) -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToPay: () -> Unit = {},
    onNavigateToInsights: () -> Unit = {}
) {
    val shifts by dashboardViewModel?.shifts?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }
    val jobs by dashboardViewModel?.jobs?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }
    val userEmail by authViewModel?.currentUserEmail?.collectAsState() ?: remember { mutableStateOf("") }
    val userName by dashboardViewModel?.userName?.collectAsState() ?: remember { mutableStateOf("") }
    val isLoading by dashboardViewModel?.isLoading?.collectAsState() ?: remember { mutableStateOf(false) }
    val syncError by dashboardViewModel?.syncError?.collectAsState() ?: remember { mutableStateOf<String?>(null) }
    val isRefreshing by dashboardViewModel?.isRefreshing?.collectAsState() ?: remember { mutableStateOf(false) }
    val displayInitials = remember(userName, userEmail) {
        if (userName.isNotBlank()) {
            val parts = userName.trim().split(" ")
            if (parts.size >= 2) "${parts.first().first()}${parts.last().first()}".uppercase()
            else userName.take(2).uppercase()
        } else {
            val prefix = userEmail.substringBefore("@")
            if (prefix.length >= 2) prefix.take(2).uppercase() else prefix.uppercase().ifEmpty { "U" }
        }
    }
    val greetingName = remember(userName, userEmail) {
        // Prefer the profile first name; otherwise derive a friendly name from the
        // email handle by dropping trailing digits ("sheshank336" -> "Sheshank").
        val raw = if (userName.isNotBlank()) {
            userName.trim().split(" ").firstOrNull() ?: ""
        } else {
            val prefix = userEmail.substringBefore("@")
            prefix.takeWhile { it.isLetter() }.ifEmpty { prefix }
        }
        raw.ifBlank { "there" }.replaceFirstChar { it.uppercase() }
    }
    val now = System.currentTimeMillis()

    var weekOffset by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }

    // Fiscal pay week (per-job weeklyCycleStartDay), not a calendar week —
    // e.g. a Friday start groups Fri–Thu into one payroll cycle.
    val globalWeekStartDay = remember(jobs) {
        dashboardViewModel?.resolveGlobalWeekStartDay() ?: "Monday"
    }
    val globalWeekStart = remember(weekOffset, globalWeekStartDay) {
        Calendar.getInstance().apply {
            timeInMillis = startOfWeekContaining(System.currentTimeMillis(), globalWeekStartDay)
            add(Calendar.WEEK_OF_YEAR, weekOffset)
        }.timeInMillis
    }
    val globalWeekEnd = globalWeekStart + 7 * 24 * 60 * 60 * 1000L

    val weekShifts = shifts.filter { it.startTime >= globalWeekStart && it.startTime < globalWeekEnd }
    val completedWeekShifts = weekShifts.filter { it.startTime < now }
    val totalHours = completedWeekShifts.sumOf { it.durationHours }
    val totalEarned = completedWeekShifts.sumOf { it.totalEarned }

    LaunchedEffect(Unit) {
        dashboardViewModel?.loadShifts()
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { dashboardViewModel?.refreshData() },
        modifier = modifier.fillMaxSize()
    ) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Hi, $greetingName",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = weekRangeLabel(weekOffset, globalWeekStartDay) + if (weekOffset == 0) " · This Week" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = {
                    dashboardViewModel?.reset()
                    authViewModel?.logout()
                    onNavigateToLogin?.invoke()
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Logout",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(PrimaryGreen, SecondaryGreen))
                        )
                        .clickable { onNavigateToProfile() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayInitials,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }

        // Week filter chip — kept tight under the greeting so the header reads
        // as one block instead of leaving a gap above the earnings card.
        Box(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 4.dp)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                onClick = { expanded = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = weekRangeLabel(weekOffset, globalWeekStartDay) + if (weekOffset == 0) " (Current)" else "",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.UnfoldMore,
                        contentDescription = "Select Week",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf(0, -1, -2, -3, -4).forEach { offset ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                weekRangeLabel(offset, globalWeekStartDay) + if (offset == 0) " (Current)" else "",
                                fontWeight = if (offset == weekOffset) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            weekOffset = offset
                            expanded = false
                        },
                        leadingIcon = if (offset == weekOffset) {
                            { Icon(Icons.Default.Check, null, tint = PrimaryGreen, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
        }

        // Error banner
        if (syncError != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = syncError ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { dashboardViewModel?.clearSyncError() }
                )
            }
        }

        if (isLoading) {
            SkeletalLoader(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                EarningsAndHoursCard(
                    totalEarned, totalHours, weekShifts.size,
                    onNavigateToPay = onNavigateToPay,
                    onClick = onNavigateToInsights
                )
            }

            if (weekOffset == 0) {
                item {
                    val upcomingShifts = shifts.filter { it.startTime >= now }
                    UpcomingEarningsCard(
                        projectedEarnings = upcomingShifts.sumOf { it.totalEarned },
                        projectedHours = upcomingShifts.sumOf { it.durationHours },
                        shiftCount = upcomingShifts.size,
                        nextShiftStart = upcomingShifts.minOfOrNull { it.startTime },
                        onClick = onNavigateToInsights
                    )
                }
            }

            if (jobs.isNotEmpty()) {
                item {
                    Text(
                        text = "Employer Goals",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(jobs) { job ->
                    Box(
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onNavigateToInsights() }
                    ) {
                        JobGoalTrackerCard(job, shifts, weekOffset)
                    }
                }
            }

            if (weekOffset == 0) {
                item { UpcomingShiftsSection(shifts, onEditShift, onHeaderClick = onNavigateToInsights) }
            }
        }
        }
    }

        ConnectivityIndicator(
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarningsAndHoursCard(
    totalEarned: Double,
    totalHours: Double,
    shiftCount: Int,
    onNavigateToPay: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        onClick = onClick
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(PrimaryGreen, Color(0xFF1B4332))
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(24.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            "This Week's Earnings",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "$${"%.2f".format(totalEarned)}",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = (-1).sp
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.15f),
                        onClick = { onNavigateToPay() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Pay Details",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatPill(
                        label = "Hours",
                        value = "${"%.1f".format(totalHours)}h",
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = "Scheduled",
                        value = "$shiftCount",
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = "Avg/Shift",
                        value = if (shiftCount > 0) "$${"%.2f".format(totalEarned / shiftCount)}" else "$0.00",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// Projected money from shifts that haven't started yet. Deliberately a separate,
// visually distinct card from "This Week's Earnings" so projections are never
// mistaken for money already earned.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingEarningsCard(
    projectedEarnings: Double,
    projectedHours: Double,
    shiftCount: Int,
    nextShiftStart: Long?,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.4f)),
        onClick = onClick
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        "Upcoming Earnings",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Est. $${"%.2f".format(projectedEarnings)}",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentBlue,
                        letterSpacing = (-1).sp
                    )
                }
                Icon(
                    Icons.Default.Insights,
                    contentDescription = "Earnings insights",
                    tint = AccentBlue,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            val nextLabel = nextShiftStart?.let {
                "Next shift ${java.text.SimpleDateFormat("EEE, MMM dd · h:mm a", Locale.US).format(java.util.Date(it))}"
            } ?: "No upcoming shifts scheduled"
            Text(
                "$shiftCount scheduled · ${"%.1f".format(projectedHours)}h · $nextLabel",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(2.dp))
        // Soft near-white keeps the labels readable against the dark green
        // gradient (the 0.6-alpha white failed contrast under bright light).
        Text(label, fontSize = 11.sp, color = Color(0xFFE0E0E0))
    }
}

@Composable
fun JobGoalTrackerCard(job: Job, shifts: List<Shift>, weekOffset: Int = 0) {
    // Step by whole cycles, not fixed weeks, so a biweekly or monthly job pages
    // through real pay periods.
    val now = System.currentTimeMillis()
    val cycle = payCycleAtOffset(job, now, weekOffset)
    val cycleStart = cycle.startMillis
    val cycleEnd = cycle.endMillis

    val shiftsForJob = shifts.filter {
        it.company.equals(job.title, ignoreCase = true) &&
        it.startTime >= cycleStart && it.startTime < cycleEnd && it.startTime < now
    }

    val hours = shiftsForJob.sumOf { it.durationHours }
    val earnings = shiftsForJob.sumOf { it.totalEarned }

    val (_, overtimeEarnings) = calculateEarningsWithOvertime(shiftsForJob, job)
    val overtimeHours = if (!job.isGigWork && hours > job.overtimeThresholdHours) hours - job.overtimeThresholdHours else 0.0

    val isGig = job.isGigWork
    val isHoursGoal = job.goalType == "Hours"
    // goalHours is entered and stored as a WEEKLY target. Comparing it straight
    // against a fortnight's or a month's totals would under-report progress by half
    // or more, so scale it to the cycle's real length.
    val goalValue = proRateWeeklyGoal(job.goalHours, cycle)
    val progressFraction = if (goalValue > 0) {
        val actualValue = if (isHoursGoal) hours else earnings
        (actualValue / goalValue).coerceIn(0.0, 1.0)
    } else 0.0

    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction.toFloat(),
        animationSpec = tween(600),
        label = "progress"
    )

    val accentColor = if (isGig) AccentOrange else AccentBlue

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accentColor.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isGig) Icons.Default.DeliveryDining else Icons.Default.Business,
                            null,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            job.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            if (isGig) "Gig Work" else "$${"%.2f".format(job.defaultHourlyRate)}/hr",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "$${"%.2f".format(earnings)}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryGreen
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column {
                    Text("Hours", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${"%.1f".format(hours)}h",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Column {
                    // "Completed" (worked shifts) vs. "Scheduled" on the earnings
                    // card, so the two counts can't read as a mismatch.
                    Text("Completed", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${shiftsForJob.size}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                if (!isGig && overtimeHours > 0.0) {
                    Column {
                        Text("Overtime", fontSize = 11.sp, color = AccentOrange)
                        Text(
                            "${"%.1f".format(overtimeHours)}h (+$${"%.0f".format(overtimeEarnings)})",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentOrange
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${PayFrequency.label(job.payFrequency)} ${job.goalType} Target",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (isHoursGoal) "${"%.1f".format(hours)}/${"%.0f".format(goalValue)}h"
                    else "$${"%.0f".format(earnings)}/$${"%.0f".format(goalValue)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (progressFraction >= 1.0) PrimaryGreen else MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (progressFraction >= 1.0) PrimaryGreen else accentColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (progressFraction >= 1.0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = PrimaryGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Goal achieved!", fontSize = 12.sp, color = PrimaryGreen, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun UpcomingShiftsSection(
    shifts: List<Shift> = emptyList(),
    onEditShift: (String) -> Unit = {},
    onHeaderClick: () -> Unit = {}
) {
    val now = System.currentTimeMillis()
    val upcomingShifts = shifts.filter { it.startTime >= now }.sortedBy { it.startTime }.take(5)
    val timeFormat = remember { java.text.SimpleDateFormat("EEE, MMM dd · h:mm a", java.util.Locale.US) }

    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onHeaderClick() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Upcoming Shifts",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Icon(
                Icons.Default.Insights,
                contentDescription = "Earnings insights",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (upcomingShifts.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.EventAvailable,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No upcoming shifts",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    upcomingShifts.forEachIndexed { index, shift ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onEditShift(shift.id) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        (if (shift.isGig) AccentOrange else AccentBlue).copy(alpha = 0.1f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                // Same employer iconography as the goals cards, so one
                                // employer never shows two different symbols.
                                Icon(
                                    if (shift.isGig) Icons.Default.DeliveryDining else Icons.Default.Business,
                                    null,
                                    tint = if (shift.isGig) AccentOrange else AccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = shift.company,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = timeFormat.format(java.util.Date(shift.startTime)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                // "Est." marks this as a projection, not money in hand.
                                Text(
                                    "Est. $${"%.2f".format(shift.totalEarned)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryGreen
                                )
                                Text(
                                    formatShiftDuration(shift.durationHours),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (index < upcomingShifts.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bar outline with a smooth Bezier notch at the top center so the docked FAB
 * (56dp, centered on the bar's top edge) sits in a cradle with a ~6dp gap
 * instead of the bar cutting straight through it.
 */
class BottomBarCutoutShape(
    private val fabSize: androidx.compose.ui.unit.Dp = 56.dp,
    private val fabGap: androidx.compose.ui.unit.Dp = 6.dp
) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val r = with(density) { (fabSize / 2 + fabGap).toPx() }
        val cx = size.width / 2f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f)
            lineTo(cx - r * 1.75f, 0f)
            // Left shoulder eases in horizontally, dips to the notch floor…
            cubicTo(cx - r * 0.9f, 0f, cx - r * 0.75f, r, cx, r)
            // …and the right shoulder mirrors it back up to the top edge.
            cubicTo(cx + r * 0.75f, r, cx + r * 0.9f, 0f, cx + r * 1.75f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

@Composable
fun BottomNavigationBar(currentRoute: String, onNavigate: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        shape = BottomBarCutoutShape()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavBarItem(icon = Icons.Default.Home, label = "Home", selected = currentRoute == "dashboard", onClick = { onNavigate("dashboard") })
            NavBarItem(icon = Icons.Default.CalendarMonth, label = "Plan", selected = currentRoute == "plan", onClick = { onNavigate("plan") })
            Spacer(modifier = Modifier.width(72.dp))
            NavBarItem(icon = Icons.Default.Payments, label = "Pay", selected = currentRoute == "pay", onClick = { onNavigate("pay") })
            NavBarItem(icon = Icons.Default.Groups, label = "Team", selected = currentRoute == "team", onClick = { onNavigate("team") })
        }
    }
}

@Composable
fun NavBarItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(250),
        label = "navColor"
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 0.1f else 0f,
        animationSpec = tween(250),
        label = "pillAlpha"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "iconScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = pillAlpha))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp).scale(iconScale))
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = color
        )
    }
}

@Composable
fun FabPlaceholder(onClick: () -> Unit = {}, isExpanded: Boolean = false) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 45f else 0f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "fabRotation"
    )
    val fabScale by animateFloatAsState(
        targetValue = if (isExpanded) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "fabScale"
    )

    // Positioned by MainLayout so its center lands on the bottom bar's top
    // edge, inside the BottomBarCutoutShape cradle.
    Box(
        modifier = Modifier
            .scale(fabScale)
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(listOf(PrimaryGreen, Color(0xFF1B4332)))
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = Icons.Default.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(28.dp).rotate(rotation))
    }
}

@Composable
fun ShimmerCard(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by transition.animateFloat(
        initialValue = -300f,
        targetValue = 300f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Gray.copy(alpha = 0.1f),
                        Color.Gray.copy(alpha = 0.25f),
                        Color.Gray.copy(alpha = 0.1f)
                    ),
                    startX = shimmerOffset,
                    endX = shimmerOffset + 300f
                )
            )
    )
}
