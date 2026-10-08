# Proguard rules for BgRemover
-keepattributes *Annotation*
-dontwarn com.google.android.gms.**
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
