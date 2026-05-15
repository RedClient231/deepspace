# DeepSpace ProGuard Rules

# Keep all engine classes
-keep class com.vspace.engine.** { *; }

# Keep all stub classes
-keep class com.vspace.stub.** { *; }

# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep ServiceManager and its fields (needed for reflection)
-keep class android.os.ServiceManager { *; }
-keep class android.os.IActivityManager$Stub { *; }
-keep class android.content.pm.IPackageManager$Stub { *; }

# Keep our proxy classes
-keep class com.vspace.engine.hook.ActivityManagerProxy { *; }
-keep class com.vspace.engine.hook.PackageManagerProxy { *; }
-keep class com.vspace.engine.hook.ServiceManagerHook { *; }

# Keep annotations
-keepattributes *Annotation*

# Don't warn about hidden APIs
-dontwarn android.hidden.**
-dontwarn mirror.**
