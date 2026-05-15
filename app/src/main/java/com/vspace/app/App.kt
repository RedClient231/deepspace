package com.vspace.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.vspace.engine.VirtualCore
import com.vspace.engine.hook.BinderHook
import com.vspace.engine.pm.LaunchConfig
import com.vspace.engine.stub.PluginContext

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
        // If so, load the target app's environment and install hooks.
        val processName = getProcessNameCompat()
        Log.d(TAG, "Process: $processName")

        if (processName != null && processName.contains(":p")) {
            val suffix = processName.substringAfterLast(":")
            if (suffix.startsWith("p") && suffix.length <= 3) {
                Log.i(TAG, "Running in stub process $processName, loading target app environment")
                loadStubEnvironment(":$suffix")
            }
        }
    }

    /**
     * Get process name compatible with all Android versions.
     * Android 9+ has Application.getProcessName(), older versions read /proc/self/cmdline.
     */
    private fun getProcessNameCompat(): String? {
        return if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            try {
                java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\t')
            } catch (_: Exception) { null }
        }
    }

    /**
     * Load the target app's ClassLoader, Resources, and Application
     * into stub processes. Also installs hooks needed for GameGuardian.
     */
    private fun loadStubEnvironment(processSuffix: String) {
        try {
            val config = LaunchConfig.read(this, processSuffix)
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
            PluginContext.setClassLoader(dexClassLoader)
            PluginContext.setResources(targetResources)
            Log.d(TAG, "PluginContext populated for $processSuffix")

            // Register IO redirect for this package
            try {
                val ioRedirectInit = com.vspace.engine.hook.BinderHook::class.java
                // Use native IO redirect
                com.vspace.engine.VirtualCore.get()
                // Register the package for path redirection
                if (dataDir != null) {
                    // This is done via native io_redirect_add_package
                    // but we pass it through the data path init
                    Log.d(TAG, "IO redirect will handle ${config.targetPkg} -> $dataDir")
                }
            } catch (e: Exception) {
                Log.w(TAG, "IO redirect registration failed (non-fatal): ${e.message}")
            }

            // Install BinderHook for system service interception
            try {
                BinderHook.install()
                Log.d(TAG, "BinderHook installed in stub process")
            } catch (e: Exception) {
                Log.w(TAG, "BinderHook install failed (non-fatal): ${e.message}")
            }

            // Install native hooks (GOT/PLT patching)
            try {
                VirtualCore.get().nativeInstallHooks()
                Log.d(TAG, "Native hooks installed in stub process")
            } catch (e: Exception) {
                Log.w(TAG, "Native hook install failed (non-fatal): ${e.message}")
            }

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
