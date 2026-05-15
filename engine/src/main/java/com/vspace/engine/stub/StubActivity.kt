package com.vspace.engine.stub

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.vspace.engine.pm.LaunchConfig
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Proxy activity that hosts imported APK activities.
 *
 * Instead of trying to startActivity() a target class (which Android won't allow
 * since the target isn't an installed package), StubActivity IS the real Android
 * Activity. It loads the target Activity class reflectively and delegates
 * lifecycle calls to it.
 *
 * Config is passed via intent extras (primary), falling back to LaunchConfig file.
 */
open class StubActivity : Activity() {

    companion object {
        private const val TAG = "StubActivity"
    }

    private var targetActivity: Activity? = null
    private var targetResources: Resources? = null
    private var targetClassLoader: ClassLoader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Read from intent extras first
        val targetPkg = intent.getStringExtra("target_pkg")
        val apkPath = intent.getStringExtra("target_apk")
        val dataDir = intent.getStringExtra("target_data")

        if (targetPkg == null || apkPath == null) {
            Log.e(TAG, "No target configured in intent extras, finishing")
            finish()
            return
        }

        try {
            // 2. Load the APK Dex
            val nativeLibDir = computeNativeLibDir(dataDir)
            val classLoader = DexClassLoader(
                apkPath,
                codeCacheDir.absolutePath,
                nativeLibDir,
                this.classLoader.parent
            )
            targetClassLoader = classLoader

            // 3. Find the launcher activity
            val launcherActivityName = resolveLauncherActivity(apkPath, targetPkg)
            if (launcherActivityName == null) {
                Log.e(TAG, "No activities found in $targetPkg")
                finish()
                return
            }

            // 4. Setup Resources for the target
            val assetManager = AssetManager::class.java.newInstance()
            val addAssetPath = assetManager.javaClass.getMethod("addAssetPath", String::class.java)
            addAssetPath.invoke(assetManager, apkPath)
            targetResources = Resources(assetManager, resources.displayMetrics, resources.configuration)

            // 5. Proxy: instantiate the target activity and delegate
            val targetActivityClass = classLoader.loadClass(launcherActivityName)
            val target = targetActivityClass.newInstance() as Activity
            targetActivity = target

            // Inject context via ContextWrapper.attachBaseContext
            val attachMethod = Context::class.java.getDeclaredMethod(
                "attachBaseContext", Context::class.java
            )
            attachMethod.isAccessible = true
            attachMethod.invoke(target, this)

            // Call onCreate of the target
            val onCreateMethod = Activity::class.java.getDeclaredMethod(
                "onCreate", Bundle::class.java
            )
            onCreateMethod.isAccessible = true
            onCreateMethod.invoke(target, savedInstanceState)

            Log.i(TAG, "Successfully proxied to $launcherActivityName from $targetPkg")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to proxy target app", e)
            finish()
        }
    }

    private fun resolveLauncherActivity(apkPath: String, packageName: String): String? {
        try {
            val pm = packageManager
            val pkgInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES)
            val activities = pkgInfo?.activities

            if (activities != null) {
                // First exported activity (most common launcher pattern)
                for (act in activities) {
                    if (act.exported) return act.name
                }
                // Fallback: first activity
                if (activities.isNotEmpty()) return activities[0].name
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve launcher activity", e)
        }
        return null
    }

    private fun computeNativeLibDir(dataDir: String?): String? {
        if (dataDir == null) return null
        val libDir = File(dataDir, "lib")
        if (!libDir.exists()) return null

        // Check for ABI subdirectories first
        for (abi in Build.SUPPORTED_ABIS) {
            val abiDir = File(libDir, abi)
            if (abiDir.exists() && abiDir.listFiles()?.isNotEmpty() == true) {
                return abiDir.absolutePath
            }
        }

        // Check if .so files are directly in lib/
        val directLibs = libDir.listFiles { f -> f.name.endsWith(".so") }
        if (directLibs?.isNotEmpty() == true) {
            return libDir.absolutePath
        }

        return null
    }

    // ── Lifecycle delegation ────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        try {
            val m = Activity::class.java.getDeclaredMethod("onStart")
            m.isAccessible = true
            m.invoke(targetActivity)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try {
            val m = Activity::class.java.getDeclaredMethod("onResume")
            m.isAccessible = true
            m.invoke(targetActivity)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try {
            val m = Activity::class.java.getDeclaredMethod("onPause")
            m.isAccessible = true
            m.invoke(targetActivity)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            val m = Activity::class.java.getDeclaredMethod("onStop")
            m.isAccessible = true
            m.invoke(targetActivity)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val m = Activity::class.java.getDeclaredMethod("onDestroy")
            m.isAccessible = true
            m.invoke(targetActivity)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        try {
            val m = Activity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            m.isAccessible = true
            m.invoke(targetActivity, intent)
        } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            val m = Activity::class.java.getDeclaredMethod(
                "onActivityResult", Int::class.java, Int::class.java, Intent::class.java
            )
            m.isAccessible = true
            m.invoke(targetActivity, requestCode, resultCode, data)
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            val m = Activity::class.java.getDeclaredMethod(
                "onRequestPermissionsResult",
                Int::class.java,
                Array<String>::class.java,
                IntArray::class.java
            )
            m.isAccessible = true
            m.invoke(targetActivity, requestCode, permissions, grantResults)
        } catch (_: Exception) {}
    }

    // ── Resource delegation ─────────────────────────────────────────

    override fun getResources(): Resources {
        return targetResources ?: super.getResources()
    }

    override fun getAssets(): android.content.res.AssetManager {
        return targetResources?.assets ?: super.getAssets()
    }

    override fun getClassLoader(): ClassLoader {
        return targetClassLoader ?: super.getClassLoader()
    }
}
