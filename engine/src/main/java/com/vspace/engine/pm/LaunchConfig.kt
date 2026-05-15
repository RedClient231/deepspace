package com.vspace.engine.pm

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Writes launch configuration for stub processes.
 * StubApp reads this config to know which target app to load.
 */
object LaunchConfig {

    private const val TAG = "LaunchConfig"
    private const val CONFIG_FILE = "launch_config.json"

    fun write(context: Context, processName: String, targetPkg: String, targetApk: String, targetData: String) {
        val configDir = File(context.filesDir, "virtual_space")
        configDir.mkdirs()
        val configFile = File(configDir, CONFIG_FILE)

        val config = if (configFile.exists()) {
            try { JSONObject(configFile.readText()) } catch (e: Exception) { JSONObject() }
        } else {
            JSONObject()
        }

        val entry = JSONObject().apply {
            put("target_pkg", targetPkg)
            put("target_apk", targetApk)
            put("target_data", targetData)
        }
        config.put(processName, entry)
        configFile.writeText(config.toString())
        Log.i(TAG, "Wrote launch config for process=$processName pkg=$targetPkg")
    }

    fun read(context: Context, processName: String): TargetInfo? {
        val configFile = File(context.filesDir, "virtual_space/launch_config.json")
        if (!configFile.exists()) return null

        return try {
            val config = JSONObject(configFile.readText())
            val entry = config.optJSONObject(processName) ?: return null
            TargetInfo(
                targetPkg = entry.getString("target_pkg"),
                targetApk = entry.getString("target_apk"),
                targetData = entry.getString("target_data")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read launch config", e)
            null
        }
    }

    fun clear(context: Context, processName: String) {
        val configFile = File(context.filesDir, "virtual_space/launch_config.json")
        if (!configFile.exists()) return
        try {
            val config = JSONObject(configFile.readText())
            config.remove(processName)
            configFile.writeText(config.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear launch config", e)
        }
    }

    data class TargetInfo(val targetPkg: String, val targetApk: String, val targetData: String)
}
