package com.vspace.engine.stub

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Resources
import android.os.Bundle
import android.util.Log

/**
 * Stub activity that serves as the container for cloned apps.
 * Each cloned app's main activity is launched inside a StubActivity
 * in one of the pre-defined stub processes (:p0, :p1, ... :pN).
 */
open class StubActivity : Activity() {

    companion object {
        private const val TAG = "StubActivity"
    }

    private var targetClassLoader: ClassLoader? = null
    private var targetResources: Resources? = null
    private var targetPackageName: String? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)
        val targetPkg = intent?.getStringExtra("target_pkg")
        val targetApk = intent?.getStringExtra("target_apk")
        val targetData = intent?.getStringExtra("target_data")

        if (targetPkg != null && targetApk != null) {
            targetPackageName = targetPkg
            try {
                // Create classloader for the target APK
                targetClassLoader = createTargetClassLoader(targetApk, targetData)
                // Load target's resources
                targetResources = createTargetResources(targetApk)
                Log.i(TAG, "Loaded target: $targetPkg from $targetApk")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load target: $targetPkg", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetPkg = targetPackageName
        if (targetPkg == null || targetClassLoader == null) {
            Log.e(TAG, "No target loaded, finishing")
            finish()
            return
        }

        try {
            // Find the target's main activity
            val pm = packageManager
            val intent = pm.getLaunchIntentForPackage(targetPkg)
            if (intent?.component != null) {
                val activityName = intent.component!!.className
                val activityClass = targetClassLoader!!.loadClass(activityName)
                val targetActivity = activityClass.newInstance() as Activity

                // Transfer intent extras
                val targetIntent = Intent(intent)
                intent.extras?.let { targetIntent.putExtras(it) }

                Log.i(TAG, "Launching target activity: $activityName")
                // In production, we would inject the activity into the framework
                // For now, we start it as a new activity
                targetIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(targetIntent)
            }
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch target activity", e)
            finish()
        }
    }

    private fun createTargetClassLoader(apkPath: String, dataDir: String?): ClassLoader {
        val nativeLibDir = if (dataDir != null) "$dataDir/lib" else null
        return dalvik.system.DexClassLoader(
            apkPath,
            codeCacheDir.absolutePath,
            nativeLibDir,
            classLoader.parent
        )
    }

    private fun createTargetResources(apkPath: String): Resources {
        val assetManager = android.content.res.AssetManager::class.java.newInstance()
        val addAssetPath = assetManager.javaClass.getMethod("addAssetPath", String::class.java)
        addAssetPath.invoke(assetManager, apkPath)
        return Resources(assetManager, resources.displayMetrics, resources.configuration)
    }
}
