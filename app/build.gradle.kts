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
        versionCode = 2
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("proguard-rules.pro")
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
            // Signed with the debug key so the file installs on a phone; a key of your own is the next step for a build you share.
            signingConfig = signingConfigs.getByName("debug")
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
