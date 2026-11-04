plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Three kinds of APK:
//   -Ppreview=true   installs from the phone's own installer, to look at the screens; cannot start protection.
//   -PtestOnly=true  the real thing for the first nights: test-only, so it can be removed over USB (adb install -t).
//   neither          the sealed build.
// Release builds are shrunk (R8), which is what keeps the APK a few MB instead of 24.
val preview = providers.gradleProperty("preview").orNull == "true"
val testBuild = providers.gradleProperty("testOnly").orNull == "true"
require(!(preview && testBuild)) { "choose either -Ppreview=true or -PtestOnly=true" }

android {
    namespace = "app.lockmein"
    compileSdk = 35

    // -PtestRelease=true runs the instrumented tests against the shrunk release build, so CI checks the file that is actually shipped.
    if (providers.gradleProperty("testRelease").orNull == "true") testBuildType = "release"

    defaultConfig {
        applicationId = "app.lockmein"
        minSdk = 30
        targetSdk = 35
        // Every CI build is a new version (the run number), so the in-app updater can tell newer from older.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + run
        versionName = "0.4.$run"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            manifestPlaceholders["testOnly"] = testBuild.toString()
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
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.google.zxing:core:3.5.3")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
