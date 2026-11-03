# The app's own code is small; keep it whole so the instrumented tests and the manifest can find everything by name.
# The size comes from the libraries (Compose, Kotlin), and those are shrunk.
-keep class app.lockmein.** { *; }
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
