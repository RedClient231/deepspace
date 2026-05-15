package com.vspace.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.vspace.engine.ipc.DaemonServer
import com.vspace.engine.model.VirtualAppInfo
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
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkgInfo.longVersionCode.toInt()
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
        // Remove data
        File(app.dataDir).deleteRecursively()
        // Remove APK
        File(app.apkPath).delete()
        // Free process slot
        vpm.freeProcessSlot(app.stubProcessIndex)
        vpm.removeApp(packageName)
        Log.i(TAG, "Uninstalled $packageName from virtual space")
        return true
    }

    fun getInstalledApps(): List<VirtualAppInfo> = vpm.getAllApps()

    fun isAppInstalled(packageName: String): Boolean = vpm.getApp(packageName) != null

    // ── App Launch ──────────────────────────────────────────────────

    fun launchApp(context: Context, packageName: String): Boolean {
        val app = vpm.getApp(packageName) ?: return false
        val stubPkg = "com.vspace.stub${app.stubProcessIndex}"
        val intent = Intent().apply {
            setClassName(stubPkg, "com.vspace.stub.StubActivity")
            putExtra("target_pkg", packageName)
            putExtra("target_apk", app.apkPath)
            putExtra("target_data", app.dataDir)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName", e)
            return false
        }
    }

    // ── Daemon ──────────────────────────────────────────────────────

    fun startDaemon() {
        daemonServer = DaemonServer()
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

    private fun extractNativeLibs(apkFile: File, dataDir: File) {
        try {
            val libDir = File(dataDir, "lib")
            libDir.mkdirs()
            val zipFile = java.util.zip.ZipFile(apkFile)
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                    val archDir = entry.name.substringAfter("lib/").substringBefore("/")
                    val fileName = entry.name.substringAfterLast("/")
                    val targetDir = File(libDir, archDir)
                    targetDir.mkdirs()
                    zipFile.getInputStream(entry).use { input ->
                        File(targetDir, fileName).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            zipFile.close()
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
