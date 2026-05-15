package com.vspace.engine.stub

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import com.vspace.engine.pm.LaunchConfig

/**
 * Stub activity that serves as the container for cloned apps.
 * Each cloned app's main activity is launched inside a StubActivity
 * in one of the pre-defined stub processes (:p0, :p1, ... :pN).
 *
 * Reads target app config from LaunchConfig file (written by VirtualCore).
 */
open class StubActivity : Activity() {

    companion object {
        private const val TAG = "StubActivity"
    }

    private var targetPackageName: String? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)

        // Read config based on process name
        val processName = getProcessName() ?: ""
        val processSuffix = if (processName.contains(":")) {
            processName.substringAfterLast(":")
        } else {
            ""
        }

        val config = LaunchConfig.read(this, ":$processSuffix")
        if (config != null) {
            targetPackageName = config.targetPkg
            Log.i(TAG, "Found config for ${config.targetPkg} in process :$processSuffix")
        } else {
            Log.e(TAG, "No launch config for process :$processSuffix")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetPkg = targetPackageName
        if (targetPkg == null) {
            Log.e(TAG, "No target configured, finishing")
            finish()
            return
        }

        try {
            // Get the StubApp instance which loaded the target's classloader
            val app = application
            val classLoaderMethod = app.javaClass.getMethod("getTargetClassLoader")
            val classLoader = classLoaderMethod.invoke(app) as? ClassLoader

            if (classLoader == null) {
                Log.e(TAG, "StubApp has no target classloader, falling back to direct load")
                // Fallback: load directly (StubApp may not have loaded yet)
                val config = LaunchConfig.read(this, ":${getProcessName()?.substringAfterLast(":")}")
                if (config != null) {
                    launchFromConfig(config.targetApk, config.targetPkg)
                }
                finish()
                return
            }

            // Parse the target APK to find the main activity
            val apkPath = getApkPath(targetPkg)
            if (apkPath == null) {
                Log.e(TAG, "Could not find APK for $targetPkg")
                finish()
                return
            }

            val pm = packageManager
            val pkgInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES)
            val activities = pkgInfo?.activities

            // Find the launcher activity
            var launcherActivity: String? = null
            if (activities != null) {
                for (act in activities) {
                    val intentFilters = act.metaData // not directly available
                    // Try the first exported activity
                    if (act.exported) {
                        launcherActivity = act.name
                        break
                    }
                }
                // Fallback: use first activity
                if (launcherActivity == null && activities.isNotEmpty()) {
                    launcherActivity = activities[0].name
                }
            }

            if (launcherActivity == null) {
                Log.e(TAG, "No activities found in $targetPkg APK")
                finish()
                return
            }

            Log.i(TAG, "Launching $launcherActivity from $targetPkg")

            // Load and launch the target activity
            val activityClass = classLoader.loadClass(launcherActivity)
            val intent = Intent(this, activityClass)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch target app", e)
            finish()
        }
    }

    private fun launchFromConfig(apkPath: String, targetPkg: String) {
        try {
            val classLoader = dalvik.system.DexClassLoader(
                apkPath,
                codeCacheDir.absolutePath,
                null,
                classLoader.parent
            )

            val pm = packageManager
            val pkgInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES)
            val activities = pkgInfo?.activities
            var launcherActivity: String? = null
            if (activities != null) {
                for (act in activities) {
                    if (act.exported) {
                        launcherActivity = act.name
                        break
                    }
                }
                if (launcherActivity == null && activities.isNotEmpty()) {
                    launcherActivity = activities[0].name
                }
            }

            if (launcherActivity != null) {
                val activityClass = classLoader.loadClass(launcherActivity)
                val intent = Intent(this, activityClass)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.i(TAG, "Fallback launched $launcherActivity")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback launch failed", e)
        }
    }

    private fun getApkPath(packageName: String): String? {
        val apkDir = File(filesDir.parentFile, "virtual_space/apks")
        val apkFile = java.io.File(apkDir, "$packageName.apk")
        return if (apkFile.exists()) apkFile.absolutePath else null
    }
}
