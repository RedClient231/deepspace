package com.vspace.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.vspace.engine.ipc.DaemonServer
import com.vspace.engine.model.VirtualAppInfo
import com.vspace.engine.pm.LaunchConfig
import com.vspace.engine.pm.VPackageManager
import java.io.File

class VirtualCore private constructor() {

    private var context: Context? = null
    private var daemonServer: DaemonServer? = null
    private val vpm = VPackageManager()

    companion object {
        private const val TAG = "VirtualCore"
        private const val VIRTUAL_DIR = "virtual_space"

        @Volatile
        private var instance: VirtualCore? = null

        fun get(): VirtualCore {
            return instance ?: synchronized(this) {
                instance ?: VirtualCore().also { instance = it }
            }
        }
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        ensureDirectories()
        vpm.init(context)
        // Load native library for hooking
        try {
            System.loadLibrary("vengine")
            nativeInit(context.filesDir.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native engine not available, running in pure-JVM mode", e)
        }
    }

    private fun ensureDirectories() {
        val ctx = context ?: return
        File(ctx.filesDir, "$VIRTUAL_DIR/apks").mkdirs()
        File(ctx.filesDir, "$VIRTUAL_DIR/data").mkdirs()
        File(ctx.filesDir, "$VIRTUAL_DIR/stubs").mkdirs()
    }

    fun getContext(): Context? = context

    fun getVirtualRoot(): File {
        return File(context!!.filesDir, VIRTUAL_DIR)
    }

    fun getApkDir(): File = File(getVirtualRoot(), "apks")

    fun getDataDir(): File = File(getVirtualRoot(), "data")

    // ── App Management ──────────────────────────────────────────────

    fun installApp(apkPath: String): Boolean {
        val ctx = context ?: return false
        val pm = ctx.packageManager
        val pkgInfo = pm.getPackageArchiveInfo(apkPath, 0) ?: return false
        val packageName = pkgInfo.packageName

        // Copy APK to virtual storage
        val destApk = File(getApkDir(), "$packageName.apk")
        File(apkPath).copyTo(destApk, overwrite = true)

        // Create data directory
        val dataDir = File(getDataDir(), packageName)
        dataDir.mkdirs()
        File(dataDir, "files").mkdirs()
        File(dataDir, "cache").mkdirs()
        File(dataDir, "databases").mkdirs()
        File(dataDir, "shared_prefs").mkdirs()

        // Extract native libs if present
        extractNativeLibs(destApk, dataDir)

        // Register in VPackageManager
        val appInfo = pkgInfo.applicationInfo?.apply {
            sourceDir = destApk.absolutePath
            publicSourceDir = destApk.absolutePath
        }
        val vAppInfo = VirtualAppInfo(
            packageName = packageName,
            name = appInfo?.loadLabel(pm)?.toString() ?: packageName,
            apkPath = destApk.absolutePath,
            dataDir = dataDir.absolutePath,
            versionName = pkgInfo.versionName ?: "1.0",
            versionCode = if (Build.VERSION.SDK_INT >= 28) pkgInfo.longVersionCode.toInt()
                          else @Suppress("DEPRECATION") pkgInfo.versionCode,
            stubProcessIndex = vpm.assignProcessSlot(),
            icon = null // loaded lazily
        )
        vpm.addApp(vAppInfo)
        Log.i(TAG, "Installed $packageName to virtual space")
        return true
    }

    fun uninstallApp(packageName: String): Boolean {
        val app = vpm.getApp(packageName) ?: return false
        try {
            val dataDir = File(app.dataDir)
            if (dataDir.exists()) dataDir.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete data dir for $packageName", e)
        }
        try {
            val apkFile = File(app.apkPath)
            if (apkFile.exists()) apkFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete APK for $packageName", e)
        }
        vpm.freeProcessSlot(app.stubProcessIndex)
        vpm.removeApp(packageName)
        Log.i(TAG, "Uninstalled $packageName from virtual space")
        return true
    }

    fun getInstalledApps(): List<VirtualAppInfo> = vpm.getAllApps()

    fun isAppInstalled(packageName: String): Boolean = vpm.getApp(packageName) != null

    // ── App Launch ──────────────────────────────────────────────────

    fun launchApp(context: Context, packageName: String): Boolean {
        val ctx = this.context ?: context
        val app = vpm.getApp(packageName) ?: run {
            Log.e(TAG, "App not installed: $packageName")
            return false
        }

        // Verify APK exists before launch
        val apkFile = File(app.apkPath)
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file missing for $packageName: ${app.apkPath}")
            vpm.removeApp(packageName)
            return false
        }

        // Verify stub slot is within declared proxy range
        val stubIndex = app.stubProcessIndex
        if (stubIndex < 0 || stubIndex > 9) {
            Log.e(TAG, "Invalid stub slot $stubIndex for $packageName (max 9)")
            return false
        }

        val hostPkg = ctx.packageName
        val stubClass = "com.vspace.engine.stub.StubActivity$stubIndex"

        // Write launch config (fallback channel)
        LaunchConfig.write(
            ctx,
            processName = ":p$stubIndex",
            targetPkg = packageName,
            targetApk = app.apkPath,
            targetData = app.dataDir
        )

        val intent = Intent().apply {
            setClassName(hostPkg, stubClass)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Pass config via intent extras (primary channel)
            putExtra("target_pkg", packageName)
            putExtra("target_apk", app.apkPath)
            putExtra("target_data", app.dataDir)
        }
        try {
            context.startActivity(intent)
            Log.i(TAG, "Launched $packageName via $hostPkg/$stubClass (slot $stubIndex)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName via $hostPkg/$stubClass", e)
            return false
        }
    }

    // ── Daemon ──────────────────────────────────────────────────────

    fun startDaemon() {
        val ctx = context ?: return
        val dataDir = File(ctx.filesDir, VIRTUAL_DIR)
        daemonServer = DaemonServer(dataDir)
        daemonServer?.start()
        Log.i(TAG, "Virtual daemon started")
    }

    fun stopDaemon() {
        daemonServer?.stop()
        daemonServer = null
        Log.i(TAG, "Virtual daemon stopped")
    }

    fun getDaemon(): DaemonServer? = daemonServer

    // ── Native Lib Extraction ───────────────────────────────────────

    /**
     * Extract native libraries for the current device ABI
     * directly into dataDir/lib/.
     */
    private fun extractNativeLibs(apkFile: File, dataDir: File) {
        try {
            val libDir = File(dataDir, "lib")
            libDir.mkdirs()

            val zipFile = java.util.zip.ZipFile(apkFile)
            val entries = zipFile.entries()
            val abiEntries = mutableMapOf<String, MutableList<Pair<String, java.io.InputStream>>>()

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                    val abi = entry.name.substringAfter("lib/").substringBefore("/")
                    if (!abiEntries.containsKey(abi)) {
                        abiEntries[abi] = mutableListOf()
                    }
                    val bytes = zipFile.getInputStream(entry).readBytes()
                    val fileName = entry.name.substringAfterLast("/")
                    abiEntries[abi]!!.add(fileName to bytes.inputStream())
                }
            }
            zipFile.close()

            // Pick the best ABI for this device
            for (abi in Build.SUPPORTED_ABIS) {
                val libs = abiEntries[abi]
                if (libs != null && libs.isNotEmpty()) {
                    for ((fileName, stream) in libs) {
                        stream.use { input ->
                            File(libDir, fileName).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    Log.i(TAG, "Extracted ${libs.size} native libs for ABI $abi to ${libDir.absolutePath}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract native libs", e)
        }
    }

    // ── JNI ─────────────────────────────────────────────────────────

    private external fun nativeInit(dataPath: String)
    external fun nativeInstallHooks()
    external fun nativeRedirectPath(originalPath: String): String
    external fun nativeReadMemory(pid: Int, address: Long, size: Int): ByteArray?
    external fun nativeWriteMemory(pid: Int, address: Long, data: ByteArray): Boolean
}
