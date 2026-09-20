# 10 — Technology Stack

## Language & Framework

| Layer | Technology | Version | Why |
|---|---|---|---|
| **UI** | Jetpack Compose | Latest stable | Modern, declarative, Material 3 |
| **Language** | Kotlin | 2.0+ | First-class Android support, coroutines |
| **Architecture** | MVVM + StateFlow | — | Standard Android architecture |
| **Build** | Gradle (Kotlin DSL) | 8.x | Standard Android build system |
| **Native** | C/C++ (NDK) | r26+ | Display rendering, PTY, JNI |
| **Native build** | CMake | 3.22+ | Standard NDK build system |
| **Min SDK** | 28 (Android 9) | — | `chroot` + external display support baseline |
| **Target SDK** | 35 (Android 15) | — | Latest Play Store requirement |

## Dependencies

### Kotlin/Android Dependencies

```kotlin
// build.gradle.kts (app)
dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // DataStore (preferences)
    implementation("androidx.datastore:datastore-preferences:1.1.0")

    // Work Manager (optional: scheduled tasks)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
```

### Forked Libraries (included as modules)

| Library | Source | License | Usage |
|---|---|---|---|
| `terminal-emulator` | [termux/termux-app](https://github.com/termux/termux-app/tree/master/terminal-emulator) | Apache-2.0 | Terminal state machine |
| `terminal-view` | [termux/termux-app](https://github.com/termux/termux-app/tree/master/terminal-view) | Apache-2.0 | Terminal rendering View |
| X11 renderer | [termux/termux-x11](https://github.com/termux/termux-x11) (subset) | GPL-2.0 | X11 framebuffer rendering |
| Anland renderer | [lfdevs/anland-termux](https://github.com/lfdevs/anland-termux) (subset) | TBD | Wayland frame rendering |

### Native Libraries (NDK)

| Library | Purpose | Source |
|---|---|---|
| `libxiaoian-x11.so` | X11 surface renderer + input | Forked from Termux:X11 |
| `libxiaoian-wayland.so` | Wayland surface renderer + input | Forked from Anland |
| `libxiaoian-terminal.so` | PTY creation + management | Forked from Termux terminal-emulator JNI |

## Project Structure

```
Xiaoian/
├── app/                                 ← Main Android app module
│   ├── src/main/
│   │   ├── java/com/xiaoian/app/
│   │   │   ├── MainActivity.kt
│   │   │   ├── DisplayActivity.kt
│   │   │   ├── TerminalActivity.kt
│   │   │   ├── XiaoianApplication.kt
│   │   │   │
│   │   │   ├── service/
│   │   │   │   ├── XiaoianService.kt       ← Foreground service
│   │   │   │   ├── BootReceiver.kt          ← Boot auto-start
│   │   │   │   └── SessionManager.kt        ← State machine
│   │   │   │
│   │   │   ├── shell/
│   │   │   │   ├── ShellExecutor.kt         ← su -c wrapper
│   │   │   │   ├── ScriptOutputParser.kt    ← [*]/[!] parser
│   │   │   │   └── RootChecker.kt           ← Root detection
│   │   │   │
│   │   │   ├── bootstrap/
│   │   │   │   ├── BootstrapManager.kt      ← First-run setup
│   │   │   │   ├── PackageManager.kt        ← apt wrapper
│   │   │   │   └── Environment.kt           ← $PREFIX, $PATH, etc.
│   │   │   │
│   │   │   ├── display/
│   │   │   │   ├── DisplayDetector.kt       ← External display detection
│   │   │   │   ├── X11SurfaceView.kt        ← X11 renderer
│   │   │   │   ├── AnlandSurfaceView.kt     ← Wayland renderer
│   │   │   │   └── InputHandler.kt          ← Touch/keyboard/mouse
│   │   │   │
│   │   │   ├── terminal/
│   │   │   │   ├── TerminalSessionManager.kt
│   │   │   │   ├── LocalShellSession.kt
│   │   │   │   ├── ChrootShellSession.kt
│   │   │   │   └── ExtraKeysView.kt
│   │   │   │
│   │   │   └── ui/
│   │   │       ├── screens/
│   │   │       │   ├── DashboardScreen.kt
│   │   │       │   ├── SettingsScreen.kt
│   │   │       │   ├── LogViewerScreen.kt
│   │   │       │   └── SetupScreen.kt
│   │   │       ├── components/
│   │   │       │   ├── DESelector.kt
│   │   │       │   ├── ModeSelector.kt
│   │   │       │   ├── StatusCard.kt
│   │   │       │   └── ProgressIndicator.kt
│   │   │       └── theme/
│   │   │           ├── Theme.kt
│   │   │           ├── Color.kt
│   │   │           └── Type.kt
│   │   │
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt
│   │   │   ├── x11/                        ← X11 renderer native code
│   │   │   │   ├── renderer.cpp
│   │   │   │   └── input.cpp
│   │   │   ├── wayland/                    ← Wayland renderer native code
│   │   │   │   ├── renderer.cpp
│   │   │   │   └── input.cpp
│   │   │   └── terminal/                   ← PTY native code
│   │   │       └── pty.cpp
│   │   │
│   │   ├── res/
│   │   │   ├── drawable/                   ← Icons
│   │   │   ├── values/                     ← Strings, colors, themes
│   │   │   └── xml/                        ← Backup rules, network config
│   │   │
│   │   ├── assets/
│   │   │   └── bootstrap-aarch64.zip       ← Bundled bootstrap (optional)
│   │   │
│   │   └── AndroidManifest.xml
│   │
│   └── build.gradle.kts
│
├── terminal-emulator/                      ← Forked library module
│   └── (Apache-2.0 Termux terminal library)
│
├── terminal-view/                          ← Forked library module
│   └── (Apache-2.0 Termux terminal View)
│
├── scripts/                                ← Shell scripts (moved here)
│   ├── xiaoian-wayland-kde.sh
│   └── xiaoian-x11-xfce.sh
│
├── plan/                                   ← This documentation
│
├── platform-tools/                         ← ADB/fastboot (gitignored)
│
├── build.gradle.kts                        ← Root build file
├── settings.gradle.kts
├── gradle.properties
├── README.md
├── LICENSE                                 ← GPL-3.0 (updated from MIT)
└── .gitignore
```

## AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.xiaoian.app">

    <!-- Permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:name=".XiaoianApplication"
        android:label="Xiaoian"
        android:icon="@mipmap/ic_launcher"
        android:theme="@style/Theme.Xiaoian"
        android:supportsRtl="true">

        <!-- Main dashboard -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Desktop renderer (launched on external display) -->
        <activity
            android:name=".DisplayActivity"
            android:exported="true"
            android:launchMode="singleInstance"
            android:theme="@style/Theme.Xiaoian.Fullscreen"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"
            android:resizeableActivity="true" />

        <!-- Terminal emulator -->
        <activity
            android:name=".TerminalActivity"
            android:exported="false"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize" />

        <!-- Foreground service -->
        <service
            android:name=".service.XiaoianService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Linux desktop session management" />
        </service>

        <!-- Boot receiver -->
        <receiver
            android:name=".service.BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

        <!-- Session ended broadcast receiver -->
        <receiver
            android:name=".service.SessionEndedReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="com.xiaoian.app.SESSION_ENDED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

## Build Variants

```kotlin
// build.gradle.kts (app)
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    flavorDimensions += "bootstrap"
    productFlavors {
        create("bundled") {
            // Bootstrap tarball included in APK assets
            dimension = "bootstrap"
            buildConfigField("boolean", "BUNDLE_BOOTSTRAP", "true")
        }
        create("lite") {
            // Bootstrap downloaded on first run (smaller APK)
            dimension = "bootstrap"
            buildConfigField("boolean", "BUNDLE_BOOTSTRAP", "false")
        }
    }
}
```

## CI/CD (GitHub Actions)

```yaml
# .github/workflows/build.yml
name: Build APK
on:
  push:
    branches: [main]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build release APK
        # The Gradle build lives under src/; the repository root holds the
        # standalone scripts, the plan and platform-tools.
        working-directory: src
        run: ./gradlew assembleBundledRelease
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: xiaoian-apk
          path: app/build/outputs/apk/bundled/release/*.apk
```
