import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version comes from git, so a release can never go out with a versionCode
// Android refuses as a downgrade. versionCode is the commit count: it grows
// with every commit on main and gives the same number locally and in CI.
// versionName is the nearest tag without its "v" (0.2.0, 0.2.0-3-gabc1234, or
// just the hash before the first tag). Outside a git checkout both fall back.
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

// A shallow clone counts only the commits it has, which would silently turn
// every CI build into versionCode 1.
if (git("rev-parse", "--is-shallow-repository") == "true")
    throw GradleException("Shallow git clone: versionCode would be wrong. Fetch the full history (fetch-depth: 0).")

val gitVersionCode = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val gitVersionName = git("describe", "--tags", "--always", "--dirty")?.removePrefix("v") ?: "0.0.0-nogit"

android {
    namespace = "com.xiaoian.app"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.xiaoian.app"
        minSdk = 28
        targetSdk = 35
        versionCode = gitVersionCode
        versionName = gitVersionName

        // arm64 only, like every native module here: the Debian rootfs and
        // the desktops are arm64, so the x86 and armeabi copies libraries
        // bring along would only be dead weight in the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Release signing from src/keystore.properties (git-ignored, see README).
    // Without it the release build stays unsigned, as before, rather than
    // failing on a machine that has no key.
    val keystoreProps = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreProps.exists()) {
        val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = rootProject.file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else null

    buildTypes {
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // libanland.so and libfdhelper.so are executables named lib*.so so that
    // the packager extracts them into the app's native library directory with
    // execute permission -- /data/data itself is non-executable (W^X). That
    // extraction only happens with legacy packaging; with the AGP 8 default
    // (extractNativeLibs=false) nativeLibraryDir points inside the APK and
    // neither binary can be exec'd. The library modules' own packaging
    // options do not affect the final APK, so it has to be set here.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation(project(":lorie"))
    implementation(project(":anland"))
    implementation(project(":terminal"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity & Navigation
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-service:2.8.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    
    // Compression
    implementation("org.apache.commons:commons-compress:1.24.0")
    implementation("org.tukaani:xz:1.9")
}
