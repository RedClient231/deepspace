package com.vspace.engine.stub

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.WindowManager
import com.vspace.engine.pm.LaunchConfig
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Method

/**
 * Proxy activity that hosts imported APK activities.
 *
 * Instead of trying to startActivity() a target class (which Android won't allow
 * since the target isn't an installed package), StubActivity IS the real Android
 * Activity. It loads the target Activity class reflectively and delegates all
 * lifecycle calls to it.
 *
 * Config is passed via intent extras (Patch 3), falling back to LaunchConfig file.
 */
open class StubActivity : Activity() {

    companion object {
        private const val TAG = "StubActivity"

        private fun getMyProcessName(): String {
            return if (Build.VERSION.SDK_INT >= 28) {
                Application.getProcessName()
            } else {
                File("/proc/self/cmdline").readText().trim()
            }
        }
    }

    // Target app state
    private var targetPackageName: String? = null
    private var targetApkPath: String? = null
    private var targetDataDir: String? = null

    // Target Activity proxy
    private var targetActivity: Activity? = null
    private var targetClassLoader: ClassLoader? = null
    private var targetResources: Resources? = null
    private var targetTheme: Resources.Theme? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)

        // Patch 3: prefer intent extras, fall back to LaunchConfig
        targetPackageName = intent?.getStringExtra("target_pkg")
        targetApkPath = intent?.getStringExtra("target_apk")
        targetDataDir = intent?.getStringExtra("target_data")

        if (targetPackageName == null) {
            // Fallback to LaunchConfig file
            val processName = getMyProcessName()
            val processSuffix = if (processName.contains(":")) {
                processName.substringAfterLast(":")
            } else ""
            val config = LaunchConfig.read(this, ":$processSuffix")
            if (config != null) {
                targetPackageName = config.targetPkg
                targetApkPath = config.targetApk
                targetDataDir = config.targetData
                Log.i(TAG, "Loaded config from file for ${config.targetPkg}")
            } else {
                Log.e(TAG, "No launch config found (extras or file)")
                return
            }
        } else {
            Log.i(TAG, "Loaded config from intent extras for $targetPackageName")
        }

        // Load target APK classloader and resources
        try {
            val apkPath = targetApkPath!!
            val nativeLibDir = computeNativeLibDir(targetDataDir)

            targetClassLoader = DexClassLoader(
                apkPath,
                codeCacheDir.absolutePath,
                nativeLibDir,
                classLoader.parent
            )

            // Load target resources
            val assetManager = AssetManager::class.java.newInstance()
            val addAssetPath = assetManager.javaClass.getMethod("addAssetPath", String::class.java)
            addAssetPath.invoke(assetManager, apkPath)
            targetResources = Resources(assetManager, resources.displayMetrics, resources.configuration)

            Log.i(TAG, "Loaded classloader and resources for $targetPackageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load target APK", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = targetPackageName
        val apkPath = targetApkPath

        if (pkg == null || apkPath == null || targetClassLoader == null) {
            Log.e(TAG, "Missing target config, finishing")
            finish()
            return
        }

        // Patch 6: resolve launcher activity properly using intent-filter
        val launcherActivity = resolveLauncherActivity(apkPath, pkg)
        if (launcherActivity == null) {
            Log.e(TAG, "No launcher activity found in $pkg")
            finish()
            return
        }

        try {
            // Patch 5: Activity proxy model
            val targetClass = targetClassLoader!!.loadClass(launcherActivity)
            targetActivity = targetClass.newInstance() as Activity

            // Call Activity.attach() via reflection
            callActivityAttach(targetActivity!!, savedInstanceState)

            // Call target onCreate
            val onCreateMethod = Activity::class.java.getDeclaredMethod(
                "onCreate", Bundle::class.java
            )
            onCreateMethod.isAccessible = true
            onCreateMethod.invoke(targetActivity, savedInstanceState)

            Log.i(TAG, "Target activity $launcherActivity launched successfully in $pkg")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch target activity", e)
            finish()
        }
    }

    /**
     * Patch 5: Call Activity.attach() reflectively to wire up the target Activity
     * inside our proxy Activity's process.
     */
    private fun callActivityAttach(target: Activity, savedInstanceState: Bundle?) {
        try {
            // Activity.attach(Context, ActivityThread, Instrumentation, IBinder, int, Application, Intent, ActivityInfo, CharSequence, Activity, String, ...)
            // The signature varies by Android version. We use the common pattern.
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)

            val instrumentationField = activityThreadClass.getDeclaredField("mInstrumentation")
            instrumentationField.isAccessible = true
            val instrumentation = instrumentationField.get(currentActivityThread)

            val app = application
            val token = getAuthToken() // May be null for proxy

            val intent = Intent().apply {
                setClassName(this@StubActivity, target.javaClass.name)
                putExtra("target_pkg", targetPackageName)
                putExtra("target_apk", targetApkPath)
                putExtra("target_data", targetDataDir)
            }

            val activityInfo = ActivityInfo().apply {
                packageName = targetPackageName
                name = target.javaClass.name
                applicationInfo = targetClassLoader?.let {
                    val pm = packageManager
                    val pkgInfo = pm.getPackageArchiveInfo(targetApkPath!!, PackageManager.GET_ACTIVITIES)
                    pkgInfo?.applicationInfo?.apply {
                        sourceDir = targetApkPath
                        publicSourceDir = targetApkPath
                    }
                } ?: android.content.pm.ApplicationInfo()
            }

            // Try the attach method - signature varies by API level
            val attachMethod = findAttachMethod()
            if (attachMethod != null) {
                attachMethod.isAccessible = true

                // Build params based on method signature
                val params = attachMethod.parameterTypes
                val args = arrayOfNulls<Any>(params.size)

                for (i in params.indices) {
                    when {
                        params[i] == Context::class.java -> args[i] = this
                        params[i].name == "android.app.ActivityThread" -> args[i] = currentActivityThread
                        params[i].name == "android.app.Instrumentation" -> args[i] = instrumentation
                        params[i] == android.os.IBinder::class.java -> args[i] = token
                        params[i] == Int::class.java -> args[i] = 0
                        params[i] == Application::class.java -> args[i] = app
                        params[i] == Intent::class.java -> args[i] = intent
                        params[i] == ActivityInfo::class.java -> args[i] = activityInfo
                        params[i] == CharSequence::class.java -> args[i] = targetPackageName
                        params[i] == Activity::class.java -> args[i] = this // parent
                        params[i] == String::class.java -> args[i] = targetDataDir
                        params[i] == android.content.res.Configuration::class.java -> args[i] = resources.configuration
                    }
                }

                attachMethod.invoke(target, *args)
                Log.d(TAG, "Activity.attach() called successfully")
            } else {
                Log.w(TAG, "Could not find Activity.attach() method, using minimal setup")
                // Minimal fallback: just set the application
                val appField = Activity::class.java.getDeclaredField("mApplication")
                appField.isAccessible = true
                appField.set(target, app)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Activity.attach() reflection failed", e)
            // Fallback: set what we can
            try {
                val appField = Activity::class.java.getDeclaredField("mApplication")
                appField.isAccessible = true
                appField.set(target, application)
            } catch (e2: Exception) {
                Log.e(TAG, "Even minimal attach failed", e2)
            }
        }
    }

    private fun findAttachMethod(): Method? {
        val methods = Activity::class.java.declaredMethods
        for (m in methods) {
            if (m.name == "attach" && m.parameterTypes.size >= 5) {
                return m
            }
        }
        return null
    }

    private fun getAuthToken(): android.os.IBinder? {
        return try {
            val field = Activity::class.java.getDeclaredField("mToken")
            field.isAccessible = true
            field.get(this) as? android.os.IBinder
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Patch 6: Resolve the launcher activity using intent-filter (MAIN/LAUNCHER).
     * Falls back to first exported activity, then first activity.
     */
    private fun resolveLauncherActivity(apkPath: String, packageName: String): String? {
        try {
            val pm = packageManager
            // Try with GET_ACTIVITIES | GET_INTENT_FILTERS
            val flags = PackageManager.GET_ACTIVITIES or
                if (Build.VERSION.SDK_INT >= 33) PackageManager.GET_META_DATA else 0
            val pkgInfo = pm.getPackageArchiveInfo(apkPath, flags) ?: return null
            val activities = pkgInfo.activities

            if (activities != null) {
                // First pass: look for MAIN+LAUNCHER
                for (act in activities) {
                    if (act.intentFilters != null) {
                        for (filter in act.intentFilters) {
                            if (filter.hasAction(Intent.ACTION_MAIN) &&
                                filter.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                                return act.name
                            }
                        }
                    }
                }

                // Second pass: first exported activity
                for (act in activities) {
                    if (act.exported) return act.name
                }

                // Third pass: first activity
                if (activities.isNotEmpty()) return activities[0].name
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve launcher activity", e)
        }
        return null
    }

    /**
     * Patch 4: Compute the correct native library directory.
     * Uses dataDir/lib directly (not dataDir/lib/<abi>).
     */
    private fun computeNativeLibDir(dataDir: String?): String? {
        if (dataDir == null) return null
        val libDir = File(dataDir, "lib")
        if (!libDir.exists()) return null

        // Check if there's an ABI subdirectory
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
            val method = Activity::class.java.getDeclaredMethod("onStart")
            method.isAccessible = true
            method.invoke(targetActivity)
        } catch (e: Exception) { /* target may not be initialized */ }
    }

    override fun onResume() {
        super.onResume()
        try {
            val method = Activity::class.java.getDeclaredMethod("onResume")
            method.isAccessible = true
            method.invoke(targetActivity)
        } catch (e: Exception) { }
    }

    override fun onPause() {
        super.onPause()
        try {
            val method = Activity::class.java.getDeclaredMethod("onPause")
            method.isAccessible = true
            method.invoke(targetActivity)
        } catch (e: Exception) { }
    }

    override fun onStop() {
        super.onStop()
        try {
            val method = Activity::class.java.getDeclaredMethod("onStop")
            method.isAccessible = true
            method.invoke(targetActivity)
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val method = Activity::class.java.getDeclaredMethod("onDestroy")
            method.isAccessible = true
            method.invoke(targetActivity)
        } catch (e: Exception) { }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        try {
            val method = Activity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            method.isAccessible = true
            method.invoke(targetActivity, intent)
        } catch (e: Exception) { }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            val method = Activity::class.java.getDeclaredMethod(
                "onActivityResult", Int::class.java, Int::class.java, Intent::class.java
            )
            method.isAccessible = true
            method.invoke(targetActivity, requestCode, resultCode, data)
        } catch (e: Exception) { }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            val method = Activity::class.java.getDeclaredMethod(
                "onRequestPermissionsResult",
                Int::class.java,
                Array<String>::class.java,
                IntArray::class.java
            )
            method.isAccessible = true
            method.invoke(targetActivity, requestCode, permissions, grantResults)
        } catch (e: Exception) { }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            val method = Activity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
            method.isAccessible = true
            method.invoke(targetActivity, outState)
        } catch (e: Exception) { }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        try {
            val method = Activity::class.java.getDeclaredMethod("onRestoreInstanceState", Bundle::class.java)
            method.isAccessible = true
            method.invoke(targetActivity, savedInstanceState)
        } catch (e: Exception) { }
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
