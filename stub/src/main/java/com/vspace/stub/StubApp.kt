package com.vspace.stub

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import dalvik.system.DexClassLoader
import com.vspace.engine.hook.BinderHook

/**
 * Stub Application that loads cloned apps dynamically.
 * Each stub process (:p0, :p1, ... :pN) runs this class,
 * which initializes the virtual engine hooks and loads
 * the target app's APK.
 */
class StubApp : Application() {

    companion object {
        private const val TAG = "StubApp"

        // Set via intent extras before the app loads
        var targetPackage: String? = null
        var targetApkPath: String? = null
        var targetDataDir: String? = null
    }

    private var targetClassLoader: ClassLoader? = null
    private var targetResources: Resources? = null
    private var targetApplication: Application? = null

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        Log.i(TAG, "attachBaseContext: process=${getProcessName()} pid=${android.os.Process.myPid()}")

        // Load native hooks
        try {
            System.loadLibrary("vengine")
            Log.i(TAG, "Loaded libvengine.so")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Failed to load libvengine.so", e)
        }

        // Install Binder hooks
        try {
            BinderHook.install()
            Log.i(TAG, "Binder hooks installed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install binder hooks", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: stub app started")

        // If target info is set, load the target app
        val pkg = targetPackage
        val apk = targetApkPath
        if (pkg != null && apk != null) {
            loadTargetApp(pkg, apk, targetDataDir)
        }
    }

    private fun loadTargetApp(packageName: String, apkPath: String, dataDir: String?) {
        Log.i(TAG, "Loading target app: $packageName from $apkPath")

        try {
            // Create classloader for the target APK
            val nativeLibDir = if (dataDir != null) "$dataDir/lib" else null
            targetClassLoader = DexClassLoader(
                apkPath,
                codeCacheDir.absolutePath,
                nativeLibDir,
                classLoader.parent
            )

            // Load target's resources
            val assetManager = AssetManager::class.java.newInstance()
            val addAssetPath = assetManager.javaClass.getMethod("addAssetPath", String::class.java)
            addAssetPath.invoke(assetManager, apkPath)
            targetResources = Resources(assetManager, resources.displayMetrics, resources.configuration)

            // Parse the target's manifest to find its Application class
            val pm = packageManager
            val pkgInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA)
            val appClassName = pkgInfo?.applicationInfo?.className

            if (appClassName != null) {
                // Instantiate the target's Application class
                val appClass = targetClassLoader!!.loadClass(appClassName)
                targetApplication = appClass.newInstance() as Application

                // Call attachBaseContext via reflection
                val attachMethod = Application::class.java.getDeclaredMethod(
                    "attachBaseContext", Context::class.java
                )
                attachMethod.isAccessible = true
                attachMethod.invoke(targetApplication, this)

                // Call onCreate
                targetApplication!!.onCreate()
                Log.i(TAG, "Target app loaded: $packageName")
            } else {
                Log.w(TAG, "No Application class found in target APK")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load target app: $packageName", e)
        }
    }

    fun getTargetClassLoader(): ClassLoader? = targetClassLoader
    fun getTargetResources(): Resources? = targetResources
}
