package com.vspace.engine.pm

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.vspace.engine.model.VirtualAppInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class VPackageManager {

    private val apps = mutableMapOf<String, VirtualAppInfo>()
    private val usedSlots = mutableSetOf<Int>()
    private var nextSlot = 0
    private var metadataFile: File? = null

    companion object {
        private const val TAG = "VPackageManager"
        private const val MAX_SLOTS = 20
        private const val META_FILE = "virtual_apps.json"
    }

    fun init(context: Context) {
        metadataFile = File(context.filesDir, "virtual_space/$META_FILE")
        loadMetadata()
    }

    fun addApp(info: VirtualAppInfo) {
        apps[info.packageName] = info
        saveMetadata()
    }

    fun removeApp(packageName: String) {
        apps.remove(packageName)
        saveMetadata()
    }

    fun getApp(packageName: String): VirtualAppInfo? = apps[packageName]

    fun getAllApps(): List<VirtualAppInfo> = apps.values.toList()

    fun assignProcessSlot(): Int {
        for (slot in 0 until MAX_SLOTS) {
            if (slot !in usedSlots) {
                usedSlots.add(slot)
                return slot
            }
        }
        throw IllegalStateException("No free stub process slots available (max: $MAX_SLOTS)")
    }

    fun freeProcessSlot(slot: Int) {
        usedSlots.remove(slot)
    }

    // ── Metadata Persistence ────────────────────────────────────────

    private fun saveMetadata() {
        try {
            val jsonArray = JSONArray()
            for (app in apps.values) {
                val obj = JSONObject().apply {
                    put("packageName", app.packageName)
                    put("name", app.name)
                    put("apkPath", app.apkPath)
                    put("dataDir", app.dataDir)
                    put("versionName", app.versionName)
                    put("versionCode", app.versionCode)
                    put("stubProcessIndex", app.stubProcessIndex)
                }
                jsonArray.put(obj)
            }
            metadataFile?.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata", e)
        }
    }

    private fun loadMetadata() {
        try {
            val file = metadataFile ?: return
            if (!file.exists()) return
            val text = file.readText()
            val jsonArray = JSONArray(text)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val info = VirtualAppInfo(
                    packageName = obj.getString("packageName"),
                    name = obj.getString("name"),
                    apkPath = obj.getString("apkPath"),
                    dataDir = obj.getString("dataDir"),
                    versionName = obj.getString("versionName"),
                    versionCode = obj.getInt("versionCode"),
                    stubProcessIndex = obj.getInt("stubProcessIndex"),
                    icon = null
                )
                apps[info.packageName] = info
                usedSlots.add(info.stubProcessIndex)
            }
            Log.i(TAG, "Loaded ${apps.size} virtual apps from metadata")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata", e)
        }
    }

    fun loadAppIcon(app: VirtualAppInfo): Drawable? {
        return try {
            val pm = android.content.pm.PackageManager::class.java
            // Try to load icon from APK
            val pkgInfo = android.content.pm.PackageInfo().apply {
                packageName = app.packageName
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    sourceDir = app.apkPath
                    publicSourceDir = app.apkPath
                }
            }
            null // Icon loading requires a Context; handle in UI layer
        } catch (e: Exception) {
            null
        }
    }
}
