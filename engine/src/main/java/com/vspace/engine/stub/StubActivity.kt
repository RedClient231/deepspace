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
import java.lang.reflect.Method

/**
 * Proxy activity that hosts imported APK activities.
 *
 * StubActivity IS the real Android Activity registered in the host manifest.
 * It loads the target Activity class reflectively, wires it up via Activity.attach(),
 * and delegates lifecycle calls to it.
 *
 * FIX: Now reuses the ClassLoader and Resources already loaded by StubApp
 * via PluginContext, instead of creating a second DexClassLoader.
 * This eliminates class identity conflicts between StubApp and StubActivity.
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
            // 2. Get ClassLoader — prefer the one StubApp already loaded
            val classLoader: ClassLoader = PluginContext.getClassLoader()
                ?: run {
                    // Fallback: create one if StubApp didn't set it (shouldn't happen)
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
            Log.d(TAG, "Using ClassLoader: ${if (PluginContext.getClassLoader() != null) "from PluginContext" else "fallback"}")

            // 3. Get Resources — prefer the one StubApp already loaded
            val resources: Resources = PluginContext.getResources()
                ?: run {
                    Log.w(TAG, "PluginContext has no Resources, creating fallback")
                    val assetManager = AssetManager::class.java.newInstance()
                    val addAssetPath = assetManager.javaClass.getMethod("addAssetPath", String::class.java)
                    addAssetPath.invoke(assetManager, apkPath)
                    val res = Resources(assetManager, getResources().displayMetrics, getResources().configuration)
                    PluginContext.setResources(res)
                    res
                }
            targetResources = resources
            Log.d(TAG, "Using Resources: ${if (PluginContext.getResources() != null) "from PluginContext" else "fallback"}")

            // 4. Load target Application if StubApp hasn't already
            initTargetApplication(classLoader, apkPath, targetPkg, dataDir)

            // 5. Find the launcher activity
            val launcherName = resolveLauncherActivity(apkPath, targetPkg)
            if (launcherName == null) {
                Log.e(TAG, "No launcher activity found in $targetPkg")
                Toast("App has no launcher activity")
                finish()
                return
            }
            Log.d(TAG, "Launcher activity: $launcherName")

            // 6. Instantiate the target Activity using the shared ClassLoader
            val targetClass = classLoader.loadClass(launcherName)
            val target = targetClass.newInstance() as Activity
            targetActivity = target
            Log.d(TAG, "Instantiated target Activity: $launcherName")

            // 7. Wire up the target Activity via Activity.attach()
            wireUpTargetActivity(target, targetPkg, apkPath, dataDir, classLoader)

            // 8. Call target.onCreate()
            val onCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            onCreate.isAccessible = true
            onCreate.invoke(target, savedInstanceState)
            Log.i(TAG, "✓ Target activity $launcherName launched successfully")

        } catch (e: Exception) {
            // e.message can be null (e.g. NullPointerException), use toString() for a useful message
            val errorDetail = e.message ?: e.toString()
            Log.e(TAG, "Failed to launch $targetPkg: $errorDetail", e)
            Toast("Launch failed: $errorDetail")
            finish()
        }
    }

    /**
     * Initialize the target APK's Application class.
     * StubApp may have already done this — check before duplicating.
     */
    private fun initTargetApplication(classLoader: ClassLoader, apkPath: String, pkg: String, dataDir: String?) {
        try {
            val pm = packageManager
            val pkgInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA)
            val appClassName = pkgInfo?.applicationInfo?.className ?: return

            val appClass = classLoader.loadClass(appClassName)
            val app = appClass.newInstance() as Application
            targetApp = app

            // Call attachBaseContext via ContextWrapper (where it's declared)
            val attachMethod = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attachMethod.isAccessible = true
            attachMethod.invoke(app, this)

            app.onCreate()
            Log.i(TAG, "Target Application $appClassName initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Target Application init failed (non-fatal, may already be loaded by StubApp): ${e.message}")
        }
    }

    /**
     * Wire up the target Activity so it can inflate layouts, show windows, etc.
     * Uses Activity.attach() via reflection.
     */
    private fun wireUpTargetActivity(target: Activity, pkg: String, apkPath: String, dataDir: String?, classLoader: ClassLoader) {
        try {
            // Get ActivityThread
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getMethod("currentActivityThread").invoke(null)

            // Get Instrumentation
            val instrField = atClass.getDeclaredField("mInstrumentation")
            instrField.isAccessible = true
            val instrumentation = instrField.get(currentAT) as Instrumentation

            // Get our token (IBinder from the system)
            val tokenField = Activity::class.java.getDeclaredField("mToken")
            tokenField.isAccessible = true
            val token = tokenField.get(this) as? IBinder

            // Build ApplicationInfo for the target
            val appInfo = ApplicationInfo().apply {
                packageName = pkg
                sourceDir = apkPath
                publicSourceDir = apkPath
                nativeLibraryDir = computeNativeLibDir(dataDir)
            }

            // Build ActivityInfo
            val actInfo = ActivityInfo().apply {
                packageName = pkg
                name = target.javaClass.name
                applicationInfo = appInfo
            }

            // Find Activity.attach() — signature varies by API level
            val attachMethod = findAttachMethod()

            if (attachMethod != null) {
                attachMethod.isAccessible = true
                val params = attachMethod.parameterTypes
                val args = arrayOfNulls<Any>(params.size)

                for (i in params.indices) {
                    args[i] = when {
                        params[i] == Context::class.java -> this
                        params[i].name == "android.app.ActivityThread" -> currentAT
                        params[i] == Instrumentation::class.java -> instrumentation
                        params[i] == IBinder::class.java -> token
                        params[i] == Int::class.java || params[i] == Int::class.javaPrimitiveType -> 0
                        params[i] == Application::class.java -> (targetApp ?: application)
                        params[i] == Intent::class.java -> Intent().apply {
                            setClassName(this@StubActivity, target.javaClass.name)
                        }
                        params[i] == ActivityInfo::class.java -> actInfo
                        params[i] == CharSequence::class.java -> pkg as CharSequence
                        params[i] == Activity::class.java -> this  // parent
                        params[i] == String::class.java -> null    // id
                        params[i] == Configuration::class.java -> resources.configuration
                        params[i].name.contains("NonConfigurationInstances") -> null
                        params[i] == Boolean::class.java || params[i] == Boolean::class.javaPrimitiveType -> false
                        else -> null
                    }
                }

                attachMethod.invoke(target, *args)
                Log.d(TAG, "Activity.attach() succeeded with ${params.size} params")
            } else {
                // Manual field injection fallback
                Log.w(TAG, "Activity.attach() not found, using field injection")
                setField(target, "mApplication", targetApp ?: application)
                setField(target, "mToken", token)
                setField(target, "mInstrumentation", instrumentation)
                setField(target, "mActivityInfo", actInfo)

                // Set up Window
                val window = target.window
                if (window != null) {
                    val wm = getSystemService(Context.WINDOW_SERVICE)
                    window.setWindowManager(wm as? android.view.WindowManager, token, null, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "wireUpTargetActivity failed", e)
            // Last resort: set minimum fields
            try {
                setField(target, "mApplication", targetApp ?: application)
            } catch (_: Exception) {}
        }
    }

    private fun findAttachMethod(): Method? {
        for (m in Activity::class.java.declaredMethods) {
            if (m.name == "attach" && m.parameterTypes.size >= 8) {
                return m
            }
        }
        return null
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(obj, value)
        } catch (e: Exception) {
            Log.d(TAG, "setField($fieldName) failed: ${e.message}")
        }
    }

    private fun resolveLauncherActivity(apkPath: String, packageName: String): String? {
        try {
            val pm = packageManager
            // GET_ACTIVITIES | GET_INTENT_FILTERS to get intent filter info
            val pkgInfo = pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
            ) ?: return null
            val activities = pkgInfo.activities ?: return null

            // Method 1: Find activity with MAIN/LAUNCHER intent filter
            // getPackageArchiveInfo doesn't populate intentFilters directly,
            // so we try to find it via the package's default launcher activity.
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                }
                val resolveInfo = pm.resolveActivity(intent, 0)
                if (resolveInfo?.activityInfo != null) {
                    val name = resolveInfo.activityInfo.name
                    Log.d(TAG, "Found launcher via resolveActivity: $name")
                    return name
                }
            } catch (_: Exception) {}

            // Method 2: Find the first exported activity
            for (act in activities) {
                if (act.exported) {
                    Log.d(TAG, "Found exported activity: ${act.name}")
                    return act.name
                }
            }

            // Method 3: Find activity whose name contains "Main" or "Launch"
            for (act in activities) {
                val lower = act.name.lowercase()
                if (lower.contains("main") || lower.contains("launch") || lower.contains("splash")) {
                    Log.d(TAG, "Found activity by name pattern: ${act.name}")
                    return act.name
                }
            }

            // Fallback: first activity
            Log.w(TAG, "No MAIN/LAUNCHER found, using first activity: ${activities[0].name}")
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
    // These overrides ensure Android framework uses the target app's
    // ClassLoader and Resources, not the host's.

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
}
