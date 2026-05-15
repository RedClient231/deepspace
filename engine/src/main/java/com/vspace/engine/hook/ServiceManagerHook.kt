package com.vspace.engine.hook

import android.os.IBinder
import android.os.IInterface
import android.util.Log
import java.lang.reflect.Method

/**
 * Replaces system service binders in ServiceManager's cache.
 *
 * This is the CORE mechanism that makes virtual apps work.
 * Android's ServiceManager maintains a static HashMap<String, IBinder> called sCache.
 * When any code calls ServiceManager.getService("activity"), it returns sCache.get("activity").
 * By replacing entries in sCache, we intercept ALL calls to system services
 * from within the virtual app process.
 *
 * This approach is used by VirtualApp, VirtualXposed, and all working clones.
 */
object ServiceManagerHook {

    private const val TAG = "ServiceManagerHook"
    private var hooked = false

    /** Our custom service cache (name -> IBinder proxy) */
    private val serviceProxies = mutableMapOf<String, IBinder>()

    /**
     * Install service hooks by replacing ServiceManager.sCache entries.
     * Must be called from the virtual app process (stub process).
     */
    fun install(): Boolean {
        if (hooked) return true

        try {
            // Step 1: Get the ServiceManager class
            val smClass = Class.forName("android.os.ServiceManager")

            // Step 2: Get the static sCache field (HashMap<String, IBinder>)
            val sCacheField = smClass.getDeclaredField("sCache")
            sCacheField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val sCache = sCacheField.get(null) as? MutableMap<String, IBinder>
                ?: run {
                    Log.e(TAG, "sCache is null")
                    return false
                }

            // Step 3: Get the real service binders and create proxies
            val getServiceMethod = smClass.getMethod("getService", String::class.java)

            // Hook Activity Manager (the most critical one)
            hookService(sCache, getServiceMethod, "activity") { original ->
                ActivityManagerProxy(original)
            }

            // Hook Package Manager
            hookService(sCache, getServiceMethod, "package") { original ->
                PackageManagerProxy(original)
            }

            hooked = true
            Log.i(TAG, "ServiceManager hooks installed (${serviceProxies.size} services)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "ServiceManager hook failed: ${e.message}", e)
            return false
        }
    }

    private fun hookService(
        sCache: MutableMap<String, IBinder>,
        getServiceMethod: Method,
        serviceName: String,
        proxyFactory: (IBinder) -> IBinder
    ) {
        try {
            // Get the real binder
            val realBinder = getServiceMethod.invoke(null, serviceName) as? IBinder
            if (realBinder == null) {
                Log.w(TAG, "Service '$serviceName' not found, skipping")
                return
            }

            // Create our proxy binder
            val proxy = proxyFactory(realBinder)

            // Replace in sCache
            sCache[serviceName] = proxy
            serviceProxies[serviceName] = proxy

            Log.d(TAG, "Hooked service: $serviceName")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to hook service '$serviceName': ${e.message}")
        }
    }

    /**
     * Check if the ServiceManager hook is active and healthy.
     */
    fun isActive(): Boolean = hooked

    /**
     * Get the number of hooked services.
     */
    fun getHookedCount(): Int = serviceProxies.size
}
