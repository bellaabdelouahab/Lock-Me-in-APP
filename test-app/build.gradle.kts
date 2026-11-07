plugins {
    id("com.android.application")
}

android {
    namespace = "app.lockmein.testapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.lockmein.testapp"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }
}
