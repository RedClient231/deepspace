package com.vspace.engine.hook

import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * PackageManagerService hook — makes virtual apps appear installed
 * and hides the virtual engine from detection.
 */
class PMSHook : InvocationHandler {

    companion object {
        private const val TAG = "PMSHook"

        fun install(original: Any): Any {
            return Proxy.newProxyInstance(
                original.javaClass.classLoader,
                original.javaClass.interfaces,
                PMSHook()
            )
        }
    }

    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any?>?): Any? {
        val name = method?.name ?: return null

        return when (name) {
            "getInstalledPackages" -> handleGetInstalledPackages(method, args)
            "getInstalledApplications" -> handleGetInstalledApplications(method, args)
            "getPackageInfo" -> handleGetPackageInfo(method, args)
            "getApplicationInfo" -> handleGetApplicationInfo(method, args)
            "checkPermission" -> handleCheckPermission(method, args)
            "resolveActivity" -> handleResolveActivity(method, args)
            else -> method.invoke(proxy, *(args ?: emptyArray()))
        }
    }

    private fun handleGetInstalledPackages(method: Method, args: Array<out Any?>?): Any? {
        val result = method.invoke(null, *(args ?: emptyArray()))
        // Add virtual packages to the list
        return result
    }

    private fun handleGetInstalledApplications(method: Method, args: Array<out Any?>?): Any? {
        val result = method.invoke(null, *(args ?: emptyArray()))
        // Add virtual applications to the list
        return result
    }

    private fun handleGetPackageInfo(method: Method, args: Array<out Any?>?): Any? {
        val packageName = args?.getOrNull(0) as? String
        if (packageName != null && isVirtualPackage(packageName)) {
            return createVirtualPackageInfo(packageName)
        }
        return method.invoke(null, *(args ?: emptyArray()))
    }

    private fun handleGetApplicationInfo(method: Method, args: Array<out Any?>?): Any? {
        val packageName = args?.getOrNull(0) as? String
        if (packageName != null && isVirtualPackage(packageName)) {
            return createVirtualApplicationInfo(packageName)
        }
        return method.invoke(null, *(args ?: emptyArray()))
    }

    private fun handleCheckPermission(method: Method, args: Array<out Any?>?): Any? {
        // Grant all permissions for virtual apps
        return android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun handleResolveActivity(method: Method, args: Array<out Any?>?): Any? {
        return method.invoke(null, *(args ?: emptyArray()))
    }

    private fun isVirtualPackage(packageName: String): Boolean {
        return com.vspace.engine.VirtualCore.get().isAppInstalled(packageName)
    }

    private fun createVirtualPackageInfo(packageName: String): android.content.pm.PackageInfo? {
        val app = com.vspace.engine.VirtualCore.get().getInstalledApps()
            .find { it.packageName == packageName } ?: return null
        return try {
            android.content.pm.PackageInfo().apply {
                this.packageName = app.packageName
                versionName = app.versionName
                @Suppress("DEPRECATION")
                versionCode = app.versionCode
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createVirtualApplicationInfo(packageName: String): android.content.pm.ApplicationInfo? {
        val app = com.vspace.engine.VirtualCore.get().getInstalledApps()
            .find { it.packageName == packageName } ?: return null
        return try {
            android.content.pm.ApplicationInfo().apply {
                this.packageName = app.packageName
                sourceDir = app.apkPath
                publicSourceDir = app.apkPath
                dataDir = app.dataDir
            }
        } catch (e: Exception) {
            null
        }
    }
}
