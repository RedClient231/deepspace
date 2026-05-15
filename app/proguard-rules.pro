# DeepSpace ProGuard Rules
-keep class com.vspace.engine.** { *; }
-keep class com.vspace.stub.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-keepattributes *Annotation*
-dontwarn android.hidden.**
-keep class android.os.ServiceManager { *; }
