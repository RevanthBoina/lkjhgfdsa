# Prompt 01: Bootstrap - Gradle, Manifest, Permissions, minSdk 30

## Objective
Establish the foundational Android & multi-module Gradle project structure for Aniob:
1. Multi-module configuration:
   - Root project: `My Application` / `Aniob`
   - `:core-agent`: Pure Kotlin JVM module (Zero `android.*` imports).
   - `:app`: Android Application module with Jetpack Compose, Room, OkHttp, Retrofit, Coroutines.
2. Android Build Configuration:
   - `minSdk = 30` (required for non-root AccessibilityService.takeScreenshot API)
   - `targetSdk = 35`
   - `compileSdk = 36`
   - Java & Kotlin compatibility: JDK 17 / JVM 17.
3. Permissions & Policies:
   - Required: `INTERNET`, `ACCESS_NETWORK_STATE`, `SYSTEM_ALERT_WINDOW`, `VIBRATE`.
   - Accessibility Service: `android.permission.BIND_ACCESSIBILITY_SERVICE` with exported=true.
   - Strictly FORBIDDEN: `com.android.vending.BILLING`, ad SDKs, analytics trackers.
4. Accessibility Configuration (`res/xml/aniob_accessibility_service_config.xml`):
   - `canRetrieveWindowContent="true"`
   - `canPerformGestures="true"`
   - `canTakeScreenshot="true"`
   - `accessibilityFeedbackType="feedbackGeneric"`
   - `accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews|flagReportViewIds"`
