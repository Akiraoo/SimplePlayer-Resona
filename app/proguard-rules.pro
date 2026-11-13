# Keep the app's own code (small project; avoids surprises from R8 full mode).
# Core's native methods are looked up by name (Java_com_akira_simpleplayer_core_Core_*).
-keep class com.akira.simpleplayer.** { *; }
-dontwarn org.jetbrains.annotations.**
