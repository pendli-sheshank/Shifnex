# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Schedulo2 is a cross-platform timesheet/shift tracking app built with **Kotlin Multiplatform (KMP)**. It has three modules:
- `shared/` — KMP shared models and business logic (compiled to Kotlin for Android, framework for iOS)
- `androidApp/` — Android app using Jetpack Compose + Material 3
- `iosApp/` — iOS app using SwiftUI (iOS 16+, generated via XcodeGen)

## Build Commands

```bash
# Android debug APK
./gradlew :androidApp:assembleDebug

# Android release bundle (AAB for Play Store)
./gradlew :androidApp:bundleRelease

# Build KMP shared framework for iOS
./gradlew :shared:linkReleaseFrameworkIosArm64

# iOS — generate Xcode project (requires xcodegen installed)
cd iosApp && xcodegen generate

# Run Android unit tests
./gradlew :androidApp:testDebugUnitTest

# Run shared module tests (commonTest on the JVM)
./gradlew :shared:testAndroidHostTest
```

## Firebase / Firestore

The app uses a **named Firestore database** called `"schedulo2"` (not the default). This is referenced as `FIRESTORE_DB_NAME` in Android code and passed explicitly in iOS `FirebaseService.swift`.

**Collections:**
| Collection | Key Fields | Access Pattern |
|---|---|---|
| `profiles` | userId, displayName, email | User-scoped |
| `settings` | userId, themeMode, remindersEnabled | User-scoped |
| `shifts` | userId, company, startTime, endTime, hourlyRate | User-scoped |
| `jobs` | userId, title, isGigWork, defaultHourlyRate, bonusAmount | User-scoped |
| `pay_adjustments` | userId, cycleKey, employer, type, amount | User-scoped |
| `feedback` | userId, category, description, screenshotUrl | User-scoped, create-only |
| `teams` | name, ownerId, inviteCode, memberCount | Role-based |
| `team_members` | teamId, userId, role, displayName | Role-based |
| `team_shifts` | teamId, assignedTo, company, status, tasks[] | Role-based |

Security rules are in `firestore.rules`.

## Architecture

**Pattern:** MVVM on both platforms.

**Android state:** `MutableStateFlow` / `StateFlow` in ViewModels, collected in Compose via `collectAsState()`.

**iOS state:** `@Published` properties in `ObservableObject` ViewModels, injected via `@EnvironmentObject`.

**Data flow:** ViewModels set up real-time Firestore `addSnapshotListener` calls. Data updates flow reactively to the UI. Optimistic local updates are applied before Firestore confirms, with rollback on failure.

### Key Files by Platform

**Android** (all in `androidApp/src/main/java/com/example/`):
- `MainActivity.kt` — Entry point, navigation host, bottom tab bar, authentication flow
- `DashboardSupport.kt` — `DashboardViewModel`, `Job`/`Shift`/`PayAdjustment` data classes, all CRUD methods, `AddShiftScreen`
- `TabsSupport.kt` — `MainLayout` (tab container), `PlanScreen` (calendar views), `JobsScreen`, `PayScreen`
- `TeamSupport.kt` — `TeamScreen`, `AssignShiftDialog`, `CreateTeamDialog`, `JoinTeamDialog`
- `TeamViewModel.kt` — Team CRUD, shift assignment, task management

**iOS** (in `iosApp/Schedulo2/`):
- `ScheduloApp.swift` — @main entry, ViewModel injection, Firebase configuration
- `Services/FirebaseService.swift` — All Firestore read/write operations, data serialization
- `ViewModels/DashboardViewModel.swift` — Shift/job CRUD, data aggregation
- `ViewModels/TeamViewModel.swift` — Team management, task completion
- `Views/Dashboard/MainTabView.swift` — Bottom tab navigation
- `Views/Plan/PlanView.swift` — Calendar month/week/day views

**KMP Shared** (`shared/src/commonMain/kotlin/com/schedulo/shared/`):
- `model/Job.kt`, `model/Shift.kt`, `model/Team.kt` — Data models with computed properties
- `logic/ShiftCalculator.kt` — Overtime calculations, conflict detection
- `logic/InsightsCalculator.kt` — Weekly earnings summaries

## Version Code

**Every code change must bump the fallback version code** in `androidApp/build.gradle.kts`:
```kotlin
val ciVersionCode = (System.getenv("VERSION_CODE") ?: "92").toInt()
```
CI sets `VERSION_CODE` from the GitHub run number + 100. The fallback (currently 92) must be incremented with each release to avoid Play Store "version code already used" errors.

## CI/CD

- `.github/workflows/deploy-playstore.yml` — Android: build signed AAB, upload to Play Store internal track
- `.github/workflows/deploy-appstore.yml` — iOS: build KMP framework, xcodegen, archive, upload to TestFlight

Both trigger on push to `main` or manual dispatch.

## Platform Details

| | Android | iOS |
|---|---|---|
| Min version | SDK 24 (Android 7.0) | iOS 16.0 |
| UI framework | Jetpack Compose + Material 3 | SwiftUI |
| App ID | `com.schedulo2.app` | `com.schedulo2.ios` |
| Widget | Glance API | WidgetKit |
| Biometric | AndroidX Biometric | LocalAuthentication |
| Calendar sync | CalendarContract | EventKit |
| Notifications | AlarmManager + BroadcastReceiver | UNUserNotificationCenter |

## Cross-Platform Consistency

When modifying features, changes must be made on **both platforms** plus the shared module if models are affected. The typical change flow:
1. Update KMP shared models if needed (`shared/src/commonMain/`)
2. Update iOS ViewModel + FirebaseService serialization
3. Update Android ViewModel + data class
4. Update UI on both platforms
5. Bump version code

## Payroll Fiscal Weeks

Payroll/earnings weekly grouping is anchored to each job's `weeklyCycleStartDay` (e.g. "Friday" → Friday–Thursday cycles), **never** hardcoded Monday or locale calendar weeks. Before touching any weekly grouping (pay cycles, insights, dashboard week cards, widgets), read `.claude/skills/payroll-fiscal-week/SKILL.md` for the rules, canonical helpers, and the persisted-`cycleKey` warning.

## iOS Project Generation

The iOS project uses **XcodeGen** (`iosApp/project.yml`). There is no `.xcodeproj` checked in — it's generated. The project depends on Firebase SDK 11.0 via SPM and the KMP shared framework built by Gradle.
