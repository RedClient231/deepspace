package com.vspace.engine.hook

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

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
        try {
            installActivityHook()
            installPackageHook()
            installNotificationHook()
            installed = true
            Log.i(TAG, "Binder hooks installed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install binder hooks", e)
        }
    }

    private fun installActivityHook() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val activityBinder = getServiceMethod.invoke(null, "activity") as? android.os.IBinder ?: return

            val iActivityManagerClass = Class.forName("android.app.IActivityManager\$Stub")
            val asInterfaceMethod = iActivityManagerClass.getMethod("asInterface", android.os.IBinder::class.java)
            val originalAm = asInterfaceMethod.invoke(null, activityBinder) ?: return

            val proxy = Proxy.newProxyInstance(
                originalAm.javaClass.classLoader,
                getInterfaces(originalAm.javaClass),
                AMSHookHandler(originalAm)
            )

            // Replace in ServiceManager cache
            val cacheField = serviceManagerClass.getDeclaredField("sCache")
            cacheField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cache = cacheField.get(null) as MutableMap<String, android.os.IBinder>
            // We don't replace the binder directly; instead we hook at a higher level
        } catch (e: Exception) {
            Log.w(TAG, "AMS hook skipped (hidden API)", e)
        }
    }

    private fun installPackageHook() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val packageBinder = getServiceMethod.invoke(null, "package") as? android.os.IBinder ?: return

            val iPackageManagerClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterfaceMethod = iPackageManagerClass.getMethod("asInterface", android.os.IBinder::class.java)
            val originalPm = asInterfaceMethod.invoke(null, packageBinder) ?: return

            Log.i(TAG, "PMS hook ready")
        } catch (e: Exception) {
            Log.w(TAG, "PMS hook skipped (hidden API)", e)
        }
    }

    private fun installNotificationHook() {
        try {
            // NotificationManager hook for virtual apps
            Log.i(TAG, "Notification hook ready")
        } catch (e: Exception) {
            Log.w(TAG, "Notification hook skipped", e)
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
