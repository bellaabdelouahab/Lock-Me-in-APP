plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "app.lockmein"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.lockmein"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The NextDNS host of the owner's profile is passed with -PdnsHost=<id>.dns.nextdns.io; tests use a public resolver.
        buildConfigField("String", "DNS_HOST", "\"${providers.gradleProperty("dnsHost").getOrElse("dns.google")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // A test-only app can be removed over USB (adb shell dpm remove-active-admin), the way back during the first nights.
            manifestPlaceholders["testOnly"] = "true"
        }
        release {
            manifestPlaceholders["testOnly"] = "false"
            isMinifyEnabled = false
        }
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

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
