# The app's own code is small; keep it whole so the instrumented tests and the manifest can find everything by name.
# The size comes from the libraries (Compose, Kotlin), and those are shrunk.
-keep class app.lockmein.** { *; }
-keep class org.tensorflow.lite.** { *; }
# The ONNX runtime (anime model) finds its Java classes from native code by name; renamed, it crashes on the first run.
-keep class ai.onnxruntime.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
