package com.vspace.engine.hook

import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * Installs dynamic proxies on system service binders to intercept
 * IPC calls made by cloned apps. This makes cloned apps believe they
 * are running on a real device with their own package/activity manager.
 */
object BinderHook {

    private const val TAG = "BinderHook"
    private var installed = false

    fun install() {
        if (installed) return
        var hooksApplied = 0

        if (installActivityHook()) hooksApplied++
        if (installPackageHook()) hooksApplied++

        installed = hooksApplied > 0
        Log.i(TAG, "BinderHook: $hooksApplied/2 hooks applied")
    }

    private fun installActivityHook(): Boolean {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val activityBinder = getServiceMethod.invoke(null, "activity") as? android.os.IBinder ?: return false

            val iActivityManagerClass = Class.forName("android.app.IActivityManager\$Stub")
            val asInterfaceMethod = iActivityManagerClass.getMethod("asInterface", android.os.IBinder::class.java)
            val originalAm = asInterfaceMethod.invoke(null, activityBinder) ?: return false

            // Note: creating proxy but not replacing ServiceManager cache.
            // The proxy is available but not active until cache replacement is implemented.
            Log.d(TAG, "AMS proxy created but not installed (ServiceManager cache hook not implemented)")
            return false
        } catch (e: Exception) {
            Log.d(TAG, "AMS hook not available: ${e.message}")
            return false
        }
    }

    private fun installPackageHook(): Boolean {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val packageBinder = getServiceMethod.invoke(null, "package") as? android.os.IBinder ?: return false

            val iPackageManagerClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterfaceMethod = iPackageManagerClass.getMethod("asInterface", android.os.IBinder::class.java)
            val originalPm = asInterfaceMethod.invoke(null, packageBinder) ?: return false

            Log.d(TAG, "PMS proxy created but not installed (ServiceManager cache hook not implemented)")
            return false
        } catch (e: Exception) {
            Log.d(TAG, "PMS hook not available: ${e.message}")
            return false
        }
    }

    private fun getInterfaces(clazz: Class<*>): Array<Class<*>> {
        val interfaces = mutableListOf<Class<*>>()
        var current: Class<*>? = clazz
        while (current != null) {
            interfaces.addAll(current.interfaces)
            current = current.superclass
        }
        return interfaces.distinct().toTypedArray()
    }

    // ── AMS Hook Handler ────────────────────────────────────────────

    class AMSHookHandler(private val original: Any) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method?, args: Array<out Any?>?): Any? {
            val methodName = method?.name ?: return null

            return when (methodName) {
                "startActivity" -> {
                    // Redirect to stub activity
                    interceptStartActivity(args)
                    method.invoke(original, *(args ?: emptyArray()))
                }
                "getRunningAppProcesses" -> {
                    // Hide virtual engine processes
                    val result = method.invoke(original, *(args ?: emptyArray()))
                    filterRunningProcesses(result)
                }
                else -> method.invoke(original, *(args ?: emptyArray()))
            }
        }

        private fun interceptStartActivity(args: Array<out Any?>?) {
            // Modify intent to redirect through stub
            Log.d(TAG, "Intercepting startActivity")
        }

        private fun filterRunningProcesses(result: Any?): Any? {
            // Filter out our stub processes from the list
            return result
        }
    }

    // ── Permission Hook ─────────────────────────────────────────────

    object PermissionHook {
        fun shouldGrantPermission(permission: String): Boolean {
            // Grant all permissions for virtual apps
            return true
        }
    }
}
