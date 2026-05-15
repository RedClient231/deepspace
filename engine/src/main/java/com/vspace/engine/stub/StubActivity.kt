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

        // 1. Read from intent extras
        val targetPkg = intent.getStringExtra("target_pkg")
        val apkPath = intent.getStringExtra("target_apk")
        val dataDir = intent.getStringExtra("target_data")

        if (targetPkg == null || apkPath == null) {
            Log.e(TAG, "No target configured in intent extras, finishing")
            finish()
            return
        }

        try {
            // 2. Create ClassLoader for the target APK
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

            // 5. Instantiate the target Activity
            val targetClass = classLoader.loadClass(launcherActivityName)
            val target = targetClass.newInstance() as Activity
            targetActivity = target

            // 6. Wire up the target Activity properly via Activity.attach()
            wireUpTargetActivity(target, targetPkg, apkPath, classLoader)

            // 7. Call target.onCreate()
            val onCreateMethod = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            onCreateMethod.isAccessible = true
            onCreateMethod.invoke(target, savedInstanceState)

            Log.i(TAG, "Successfully proxied $launcherActivityName from $targetPkg")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to proxy target app", e)
            finish()
        }
    }

    /**
     * Wire up the target Activity so it can inflate layouts, show windows, etc.
     * Calls Activity.attach() via reflection with all required parameters.
     */
    private fun wireUpTargetActivity(target: Activity, pkg: String, apkPath: String, classLoader: ClassLoader) {
        // Step A: Get ActivityThread instance
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentAT = activityThreadClass.getMethod("currentActivityThread").invoke(null)

        // Step B: Get Instrumentation from ActivityThread
        val instrField = activityThreadClass.getDeclaredField("mInstrumentation")
        instrField.isAccessible = true
        val instrumentation = instrField.get(currentAT) as Instrumentation

        // Step C: Get our token (the IBinder from ActivityTaskManager)
        val tokenField = Activity::class.java.getDeclaredField("mToken")
        tokenField.isAccessible = true
        val token = tokenField.get(this) as? IBinder

        // Step D: Build ActivityInfo for the target
        val appInfo = ApplicationInfo().apply {
            packageName = pkg
            sourceDir = apkPath
            publicSourceDir = apkPath
            nativeLibraryDir = computeNativeLibDir(dataDir = intent.getStringExtra("target_data"))
        }
        val activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = target.javaClass.name
            applicationInfo = appInfo
        }

        // Step E: Call Activity.attach()
        // Signature: attach(Context, ActivityThread, Instrumentation, IBinder, int, Application, Intent, ActivityInfo, CharSequence, Activity, String, ...)
        val attachMethod = findActivityAttachMethod()
        if (attachMethod != null) {
            attachMethod.isAccessible = true
            val params = attachMethod.parameterTypes
            val args = arrayOfNulls<Any>(params.size)

            for (i in params.indices) {
                when {
                    params[i] == Context::class.java -> args[i] = this
                    params[i].name == "android.app.ActivityThread" -> args[i] = currentAT
                    params[i] == Instrumentation::class.java -> args[i] = instrumentation
                    params[i] == IBinder::class.java -> args[i] = token
                    params[i] == Int::class.java || params[i] == Int::class.javaPrimitiveType -> args[i] = 0
                    params[i] == Application::class.java -> args[i] = application
                    params[i] == Intent::class.java -> args[i] = Intent().apply {
                        setClassName(this@StubActivity, target.javaClass.name)
                    }
                    params[i] == ActivityInfo::class.java -> args[i] = activityInfo
                    params[i] == CharSequence::class.java -> args[i] = pkg
                    params[i] == Activity::class.java -> args[i] = this
                    params[i] == String::class.java -> args[i] = null // id
                    params[i] == android.content.res.Configuration::class.java -> args[i] = resources.configuration
                    // NonConfigurationInstances
                    params[i].name.contains("NonConfigurationInstances") -> args[i] = null
                }
            }
            attachMethod.invoke(target, *args)
            Log.d(TAG, "Activity.attach() succeeded")
        } else {
            // Fallback: manually set critical fields
            Log.w(TAG, "Activity.attach() not found, using field injection")
            setField(target, "mApplication", application)
            setField(target, "mToken", token)
            setField(target, "mInstrumentation", instrumentation)

            // Set up window manager
            val wm = getSystemService(Context.WINDOW_SERVICE)
            setField(target, "mWindowManager", wm)
        }
    }

    private fun findActivityAttachMethod(): Method? {
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
            Log.d(TAG, "Could not set $fieldName: ${e.message}")
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
