package com.vspace.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.vspace.engine.VirtualCore

class App : Application() {

    companion object {
        private const val TAG = "App"
        const val CHANNEL_ID = "deepspace_daemon"
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        VirtualCore.get().init(this)

        // Detect if we're running in a stub process (:p0, :p1, ... :p9).
        // If so, we need to load the target app's environment here because
        // StubApp (from the stub module) is NOT the Application class for
        // these processes — this App class is.
        val processName = if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            try { java.io.File("/proc/self/cmdline").readText().trim() } catch (_: Exception) { "" }
        }

        if (processName != null && processName.contains(":p")) {
            val suffix = processName.substringAfterLast(":")
            if (suffix.startsWith("p") && suffix.length <= 3) {
                Log.i(TAG, "Running in stub process $processName, loading target app environment")
                loadStubEnvironment(":$suffix")
            }
        }
    }

    /**
     * Load the target app's ClassLoader, Resources, and Application
     * into PluginContext so StubActivity can use them.
     *
     * This replicates what StubApp.onCreate() does, but runs in the
     * app's process where StubApp is never instantiated.
     */
    private fun loadStubEnvironment(processSuffix: String) {
        try {
            val config = com.vspace.engine.pm.LaunchConfig.read(this, processSuffix)
            if (config == null) {
                Log.w(TAG, "No launch config for $processSuffix")
                return
            }

            Log.i(TAG, "Loading target: ${config.targetPkg} from ${config.targetApk}")

            val apkPath = config.targetApk
            val dataDir = config.targetData
            val nativeLibDir = if (dataDir != null) "$dataDir/lib" else null

            // Create ClassLoader for target APK
            val dexClassLoader = dalvik.system.DexClassLoader(
                apkPath,
                codeCacheDir.absolutePath,
                nativeLibDir,
                classLoader.parent  // boot classloader → target can access Android framework + host app
            )

            // Create Resources for target APK
            val assetManager = android.content.res.AssetManager::class.java.newInstance()
            val addAssetPath = assetManager.javaClass.getMethod("addAssetPath", String::class.java)
            addAssetPath.invoke(assetManager, apkPath)
            val targetResources = android.content.res.Resources(
                assetManager, resources.displayMetrics, resources.configuration
            )

            // Store in PluginContext for StubActivity to use
            com.vspace.engine.stub.PluginContext.setClassLoader(dexClassLoader)
            com.vspace.engine.stub.PluginContext.setResources(targetResources)
            Log.d(TAG, "PluginContext populated for $processSuffix")

            // Load target Application class if it has one
            val pm = packageManager
            val pkgInfo = pm.getPackageArchiveInfo(apkPath, android.content.pm.PackageManager.GET_META_DATA)
            val appClassName = pkgInfo?.applicationInfo?.className
            if (appClassName != null) {
                try {
                    val appClass = dexClassLoader.loadClass(appClassName)
                    val targetApp = appClass.newInstance() as Application
                    val attachMethod = android.content.ContextWrapper::class.java
                        .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
                    attachMethod.isAccessible = true
                    attachMethod.invoke(targetApp, this)
                    targetApp.onCreate()
                    Log.i(TAG, "Target Application $appClassName initialized")
                } catch (e: Exception) {
                    Log.w(TAG, "Target Application init failed (non-fatal): ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load stub environment for $processSuffix", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeepSpace Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Virtual space engine service"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
