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
            // 2. Create ClassLoader for the target APK
            // Use host classloader as parent so target can access Android framework + AndroidX
            val nativeLibDir = computeNativeLibDir(dataDir)
            val classLoader = DexClassLoader(
                apkPath,
                codeCacheDir.absolutePath,
                nativeLibDir,
                javaClass.classLoader  // host classloader, NOT boot classloader
            )
            targetClassLoader = classLoader
            Log.d(TAG, "Created DexClassLoader for $targetPkg")

            // 3. Load target's resources
            val assetManager = AssetManager::class.java.newInstance()
            val addAssetPath = assetManager.javaClass.getMethod("addAssetPath", String::class.java)
            addAssetPath.invoke(assetManager, apkPath)
            targetResources = Resources(assetManager, resources.displayMetrics, resources.configuration)
            Log.d(TAG, "Loaded resources for $targetPkg")

            // 4. Instantiate the target's Application class (if any)
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

            // 6. Instantiate the target Activity
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
            Log.e(TAG, "Failed to launch $targetPkg", e)
            Toast("Launch failed: ${e.message}")
            finish()
        }
    }

    /**
     * Initialize the target APK's Application class.
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
            Log.w(TAG, "Target Application init failed (non-fatal): ${e.message}")
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
            val pkgInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES) ?: return null
            val activities = pkgInfo.activities ?: return null

            // First exported activity (most common launcher pattern)
            for (act in activities) {
                if (act.exported) return act.name
            }
            // Fallback: first activity
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
