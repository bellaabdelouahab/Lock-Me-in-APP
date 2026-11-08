plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// Built for Java 17 so the Android app can use it, whichever JDK runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2048m"
    systemProperty("test.modelAccuracy", System.getProperty("test.modelAccuracy") ?: "")
    testLogging {
        showStandardStreams = true
    }
}
