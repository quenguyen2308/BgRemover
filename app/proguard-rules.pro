# Proguard rules for BgRemover
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

-keep class com.google.android.gms.** { *; }
-keep interface com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

-keep class androidx.exifinterface.** { *; }
-dontwarn androidx.exifinterface.**

-keep class com.bgremover.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

