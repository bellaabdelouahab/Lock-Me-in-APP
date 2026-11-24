plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Three kinds of APK:
//   -Ppreview=true   installs from the phone's own installer, to look at the screens; cannot start protection.
//   -PtestOnly=true  the real thing for the first nights: removable over USB (adb shell am broadcast -n app.lockmein/.UsbEscapeReceiver).
//                    Not marked test-only in the manifest: Android lets only a computer install test-only APKs, so the in-app update could not.
//   neither          the sealed build.
// Release builds are shrunk (R8), which is what keeps the APK a few MB instead of 24.
val preview = providers.gradleProperty("preview").orNull == "true"
val testBuild = providers.gradleProperty("testOnly").orNull == "true"
require(!(preview && testBuild)) { "choose either -Ppreview=true or -PtestOnly=true" }

android {
    namespace = "app.lockmein"
    // The server check (scripts/check.sh) links with an older aapt2 that only reads up to android-34.
    compileSdk = providers.gradleProperty("compileSdkOverride").orNull?.toInt() ?: 35

    // -PtestRelease=true runs the instrumented tests against the shrunk release build, so CI checks the file that is actually shipped.
    if (providers.gradleProperty("testRelease").orNull == "true") testBuildType = "release"

    defaultConfig {
        // The preview has its own id, so it can never install over the real app (same key) and silently stop protection,
        // and the two can sit side by side. The test and sealed builds share the real id: one replaces the other on purpose.
        applicationId = if (preview) "app.lockmein.preview" else "app.lockmein"
        minSdk = 30
        targetSdk = 35
        // Every CI build is a new version (the run number), so the in-app updater can tell newer from older.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + run
        versionName = "0.4.$run"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The phone is arm64; x86_64 is for the CI emulator. Leaving out 32-bit keeps the two model runtimes from doubling the APK.
        // The published APKs (-PphoneOnly=true) carry arm64 only: the x86_64 runtimes were 28 MB of every download.
        ndk { abiFilters += if (project.hasProperty("phoneOnly")) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunnerArguments["uiAutomationFlags"] = "1"
        testProguardFiles("proguard-rules.pro")
    }

    // One release key for every build (kept in the repo's secrets), so an update installs over the version before it.
    // Without it (a local build) the debug key is used, and that build can't update or be updated.
    val keystore = System.getenv("LMI_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("lockmein") {
            storeFile = keystore
            storePassword = System.getenv("LMI_KEYSTORE_PASSWORD")
            keyAlias = "lockmein"
            keyPassword = System.getenv("LMI_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // The instrumented tests run on this one. Test-only, like the test build.
            manifestPlaceholders["testOnly"] = if (preview) "false" else "true"
            buildConfigField("boolean", "PREVIEW", preview.toString())
            buildConfigField("boolean", "TEST_BUILD", (!preview).toString())
        }
        release {
            manifestPlaceholders["testOnly"] = "false"
            buildConfigField("boolean", "PREVIEW", preview.toString())
            buildConfigField("boolean", "TEST_BUILD", testBuild.toString())
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystore != null) "lockmein" else "debug")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // scripts/shots.sh draws the screens on the computer (Robolectric); it needs the app's resources.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("shots.dir", System.getProperty("shots.dir") ?: layout.buildDirectory.dir("shots").get().asFile.path)
            test.maxHeapSize = "2g"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.google.zxing:core:3.5.3")
    // The link to the MCP relay (one WebSocket the phone keeps open).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
    // Second filter model (deepghs anime_rating, MIT) ships as ONNX.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Test-only, never in the APK: draws the screens to PNG files on the computer (scripts/shots.sh).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
