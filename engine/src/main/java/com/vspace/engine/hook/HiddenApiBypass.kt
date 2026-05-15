package com.vspace.engine.hook

import android.os.Build
import android.util.Log
import java.lang.reflect.Method

/**
 * Bypass Android's hidden API enforcement policy.
 * On Android 9+ (API 28+), accessing non-SDK interfaces is restricted.
 * This bypass removes those restrictions using reflection.
 *
 * Based on the approach used by VirtualApp and LSPosed.
 */
object HiddenApiBypass {

    private const val TAG = "HiddenApiBypass"
    private var bypassed = false

    /**
     * Bypass hidden API enforcement policy.
     * Should be called early in app initialization, before any hidden API access.
     */
    fun bypass(): Boolean {
        if (bypassed) return true
        if (Build.VERSION.SDK_INT < 28) {
            bypassed = true
            return true // No restrictions below API 28
        }

        try {
            // Method 1: Set the hidden API policy to allow everything
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getMethod("getRuntime")
            val runtime = getRuntime.invoke(null)

            // Try setHiddenApiExemptions (available on some Android versions)
            try {
                val setExemptions = vmRuntimeClass.getMethod(
                    "setHiddenApiExemptions",
                    Array<String>::class.java
                )
                setExemptions.invoke(runtime, arrayOf("L"))
                bypassed = true
                Log.i(TAG, "Hidden API bypass via setHiddenApiExemptions succeeded")
                return true
            } catch (_: NoSuchMethodException) {
                Log.d(TAG, "setHiddenApiExemptions not available, trying alternative")
            }

            // Method 2: Use the freeform API exemption
            try {
                val setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod(
                    "setHiddenApiExemptions",
                    Array<String>::class.java
                )
                setHiddenApiExemptions.isAccessible = true
                setHiddenApiExemptions.invoke(runtime, arrayOf("L"))
                bypassed = true
                Log.i(TAG, "Hidden API bypass via reflection succeeded")
                return true
            } catch (_: Exception) {}

            Log.w(TAG, "Hidden API bypass not available on this device")
            bypassed = true // Don't retry
            return false

        } catch (e: Exception) {
            Log.e(TAG, "Hidden API bypass failed: ${e.message}", e)
            bypassed = true
            return false
        }
    }
}
