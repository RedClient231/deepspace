package com.vspace.engine.stub

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * Proxy activity that hosts imported APK activities.
 *
 * StubActivity IS the real Android Activity registered in the host manifest.
 * It loads the target Activity class reflectively, wires it up via attachBaseContext()
 * + field injection, and delegates lifecycle calls to it.
 *
 * FIX: Replaced complex Activity.attach() reflection (API-level-dependent signature)
 * with simpler attachBaseContext() + field injection. Also unwraps InvocationTargetException
 * to show the real error cause.
 */
open class StubActivity : Activity() {

    companion object {
        private const val TAG = "StubActivity"
    }

    private var targetActivity: Activity? = null
    private var targetResources: Resources? = null
    private var targetClassLoader: ClassLoader? = null
    private var targetApp: Application? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Read config from intent extras
        val targetPkg = intent.getStringExtra("target_pkg")
        val apkPath = intent.getStringExtra("target_apk")
        val dataDir = intent.getStringExtra("target_data")

        if (targetPkg == null || apkPath == null) {
            Log.e(TAG, "No target configured in intent extras, finishing")
            finish()
            return
        }

        Log.i(TAG, "Launching $targetPkg from $apkPath")

        try {
            // 2. Verify APK exists
            if (!File(apkPath).exists()) {
                Log.e(TAG, "APK not found at: $apkPath")
                Toast("APK not found: $apkPath")
                finish()
                return
            }

            // 3. Get or create ClassLoader
            val classLoader: ClassLoader = PluginContext.getClassLoader()
                ?: run {
                    Log.w(TAG, "PluginContext has no ClassLoader, creating fallback")
                    val nativeLibDir = computeNativeLibDir(dataDir)
                    val cl = DexClassLoader(
                        apkPath,
                        codeCacheDir.absolutePath,
                        nativeLibDir,
                        javaClass.classLoader
                    )
                    PluginContext.setClassLoader(cl)
                    cl
                }
            targetClassLoader = classLoader

            // 4. Get or create Resources
            val pluginResources: Resources = PluginContext.getResources()
                ?: run {
                    Log.w(TAG, "PluginContext has no Resources, creating fallback")
                    val am = AssetManager::class.java.newInstance()
                    val addAssetPath = am.javaClass.getMethod("addAssetPath", String::class.java)
                    addAssetPath.invoke(am, apkPath)
                    val res = Resources(am, getResources().displayMetrics, getResources().configuration)
                    PluginContext.setResources(res)
                    res
                }
            targetResources = pluginResources

            // 5. Initialize target Application
            initTargetApplication(classLoader, apkPath, targetPkg, dataDir)

            // 6. Find launcher activity
            val launcherName = resolveLauncherActivity(apkPath, targetPkg)
            if (launcherName.isNullOrEmpty()) {
                Log.e(TAG, "No launcher activity found in $targetPkg")
                Toast("App has no launcher activity")
                finish()
                return
            }
            Log.d(TAG, "Launcher activity: $launcherName")

            // 7. Instantiate target Activity
            val targetClass = classLoader.loadClass(launcherName)
            val target = targetClass.newInstance() as Activity
            targetActivity = target
            Log.d(TAG, "Instantiated target Activity: $launcherName")

            // 8. Wire up the target Activity using simple, reliable approach:
            //    attachBaseContext() + field injection (no complex Activity.attach())
            wireUpTarget(target, targetPkg, pluginResources, classLoader)

            // 9. Call target.onCreate()
            callTargetOnCreate(target, savedInstanceState)
            Log.i(TAG, "✓ Target activity $launcherName launched successfully")

        } catch (e: InvocationTargetException) {
            // Unwrap the REAL error from inside the reflection wrapper
            val realCause = e.cause ?: e
            Log.e(TAG, "Launch failed (InvocationTargetException): ${realCause.javaClass.simpleName}: ${realCause.message}", realCause)
            Toast("Launch failed: ${realCause.javaClass.simpleName}: ${realCause.message}")
            finish()
        } catch (e: Exception) {
            val errorDetail = e.message ?: e.toString()
            Log.e(TAG, "Launch failed: ${e.javaClass.simpleName}: $errorDetail", e)
            Toast("Launch failed: ${e.javaClass.simpleName}: $errorDetail")
            finish()
        }
    }

    /**
     * Wire up the target Activity using simple, reliable methods.
     * Uses attachBaseContext() + field injection instead of complex Activity.attach()
     * which has API-level-dependent signatures.
     */
    private fun wireUpTarget(target: Activity, pkg: String, pluginResources: Resources, classLoader: ClassLoader) {
        // Step 1: Create a wrapped context with target's resources and classloader
        val wrappedContext = object : ContextWrapper(this) {
            override fun getClassLoader(): ClassLoader = classLoader
            override fun getResources(): Resources = pluginResources
            override fun getAssets(): android.content.res.AssetManager = pluginResources.assets
        }

        // Step 2: Call attachBaseContext on target (this is safe, declared in ContextWrapper)
        try {
            val attachMethod = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attachMethod.isAccessible = true
            attachMethod.invoke(target, wrappedContext)
            Log.d(TAG, "attachBaseContext succeeded")
        } catch (e: InvocationTargetException) {
            Log.e(TAG, "attachBaseContext failed: ${e.cause?.message}", e.cause)
            throw e
        }

        // Step 3: Set mResources field directly
        try {
            val resField = Activity::class.java.getDeclaredField("mResources")
            resField.isAccessible = true
            resField.set(target, pluginResources)
            Log.d(TAG, "mResources set")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set mResources: ${e.message}")
        }

        // Step 4: Set mApplication field
        try {
            val appField = Activity::class.java.getDeclaredField("mApplication")
            appField.isAccessible = true
            appField.set(target, targetApp ?: application)
            Log.d(TAG, "mApplication set")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set mApplication: ${e.message}")
        }

        // Step 5: Set mToken (needed for window management)
        try {
            val tokenField = Activity::class.java.getDeclaredField("mToken")
            tokenField.isAccessible = true
            val token = tokenField.get(this) as? IBinder
            tokenField.set(target, token)
            Log.d(TAG, "mToken set")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set mToken: ${e.message}")
        }

        // Step 6: Set mInstrumentation
        try {
            val instrField = Activity::class.java.getDeclaredField("mInstrumentation")
            instrField.isAccessible = true
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getMethod("currentActivityThread").invoke(null)
            val instrumentation = atClass.getDeclaredField("mInstrumentation").apply { isAccessible = true }.get(currentAT) as Instrumentation
            instrField.set(target, instrumentation)
            Log.d(TAG, "mInstrumentation set")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set mInstrumentation: ${e.message}")
        }

        // Step 7: Set mActivityInfo
        try {
            val actInfoField = Activity::class.java.getDeclaredField("mActivityInfo")
            actInfoField.isAccessible = true
            val actInfo = ActivityInfo().apply {
                packageName = pkg
                name = target.javaClass.name
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    sourceDir = intent.getStringExtra("target_apk")
                    publicSourceDir = intent.getStringExtra("target_apk")
                }
            }
            actInfoField.set(target, actInfo)
            Log.d(TAG, "mActivityInfo set")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set mActivityInfo: ${e.message}")
        }

        // Step 8: Setup Window if available
        try {
            val window = target.window
            if (window != null) {
                val wm = getSystemService(Context.WINDOW_SERVICE)
                val tokenField = Activity::class.java.getDeclaredField("mToken")
                tokenField.isAccessible = true
                val token = tokenField.get(this) as? IBinder
                window.setWindowManager(wm as? android.view.WindowManager, token, null, false)
                Log.d(TAG, "Window setup complete")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup window: ${e.message}")
        }
    }

    /**
     * Call the target activity's onCreate via reflection.
     * Unwraps InvocationTargetException to show the real error.
     */
    private fun callTargetOnCreate(target: Activity, savedInstanceState: Bundle?) {
        try {
            val onCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            onCreate.isAccessible = true
            onCreate.invoke(target, savedInstanceState)
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: e
            Log.e(TAG, "Target onCreate() failed: ${cause.javaClass.simpleName}: ${cause.message}", cause)
            throw e
        }
    }

    private fun initTargetApplication(classLoader: ClassLoader, apkPath: String, pkg: String, dataDir: String?) {
        try {
            val pm = packageManager
            val pkgInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA)
            val appClassName = pkgInfo?.applicationInfo?.className ?: return

            val appClass = classLoader.loadClass(appClassName)
            val app = appClass.newInstance() as Application
            targetApp = app

            val attachMethod = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attachMethod.isAccessible = true
            attachMethod.invoke(app, this)

            app.onCreate()
            Log.i(TAG, "Target Application $appClassName initialized")
        } catch (e: InvocationTargetException) {
            Log.w(TAG, "Target Application init failed: ${e.cause?.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Target Application init failed (non-fatal): ${e.message}")
        }
    }

    private fun resolveLauncherActivity(apkPath: String, packageName: String): String? {
        try {
            val pm = packageManager
            val pkgInfo = pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
            ) ?: return null
            val activities = pkgInfo.activities ?: return null

            // Method 1: Use pm.resolveActivity with MAIN/LAUNCHER intent
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                }
                val resolveInfo = pm.resolveActivity(intent, 0)
                if (resolveInfo?.activityInfo != null) {
                    Log.d(TAG, "Found launcher via resolveActivity: ${resolveInfo.activityInfo.name}")
                    return resolveInfo.activityInfo.name
                }
            } catch (_: Exception) {}

            // Method 2: Find by name pattern
            for (act in activities) {
                val lower = act.name.lowercase()
                if (lower.contains("main") || lower.contains("launch") || lower.contains("splash")) {
                    Log.d(TAG, "Found activity by name pattern: ${act.name}")
                    return act.name
                }
            }

            // Method 3: First exported activity
            for (act in activities) {
                if (act.exported) {
                    Log.d(TAG, "Found exported activity: ${act.name}")
                    return act.name
                }
            }

            // Fallback: first activity
            Log.w(TAG, "No MAIN/LAUNCHER found, using first: ${activities[0].name}")
            return activities[0].name
        } catch (e: Exception) {
            Log.e(TAG, "resolveLauncherActivity failed", e)
            return null
        }
    }

    private fun computeNativeLibDir(dataDir: String?): String? {
        if (dataDir == null) return null
        val libDir = File(dataDir, "lib")
        if (!libDir.exists()) return null

        for (abi in Build.SUPPORTED_ABIS) {
            val abiDir = File(libDir, abi)
            if (abiDir.exists() && abiDir.listFiles()?.isNotEmpty() == true) {
                return abiDir.absolutePath
            }
        }

        val directLibs = libDir.listFiles { f -> f.name.endsWith(".so") }
        if (directLibs?.isNotEmpty() == true) {
            return libDir.absolutePath
        }
        return null
    }

    private fun Toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    // ── Resource & ClassLoader delegation ───────────────────────────

    override fun getResources(): Resources {
        return targetResources ?: PluginContext.getResources() ?: super.getResources()
    }

    override fun getAssets(): android.content.res.AssetManager {
        return targetResources?.assets ?: PluginContext.getResources()?.assets ?: super.getAssets()
    }

    override fun getClassLoader(): ClassLoader {
        return targetClassLoader ?: PluginContext.getClassLoader() ?: super.getClassLoader()
    }

    // ── Lifecycle delegation ────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        try { targetActivity?.onStart() } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try { targetActivity?.onResume() } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { targetActivity?.onPause() } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try { targetActivity?.onStop() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { targetActivity?.onDestroy() } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        try { targetActivity?.onNewIntent(intent) } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try { targetActivity?.onActivityResult(requestCode, resultCode, data) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try { targetActivity?.onRequestPermissionsResult(requestCode, permissions, grantResults) } catch (_: Exception) {}
    }
}
