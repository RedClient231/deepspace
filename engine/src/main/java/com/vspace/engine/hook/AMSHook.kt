package com.vspace.engine.hook

import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * ActivityManagerService hook — intercepts activity/process operations
 * to redirect cloned app activities through stub processes.
 */
class AMSHook : InvocationHandler {

    companion object {
        private const val TAG = "AMSHook"

        fun install(original: Any): Any {
            return Proxy.newProxyInstance(
                original.javaClass.classLoader,
                original.javaClass.interfaces,
                AMSHook()
            )
        }
    }

    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any?>?): Any? {
        val name = method?.name ?: return null

        return when (name) {
            "startActivity" -> handleStartActivity(method, args?.copyOf())
            "startService" -> handleStartService(method, args)
            "sendBroadcast" -> handleSendBroadcast(method, args)
            "getRunningAppProcesses" -> handleGetRunningProcesses(method, args)
            "checkPermission" -> handleCheckPermission(method, args)
            else -> method.invoke(proxy, *(args ?: emptyArray()))
        }
    }

    private fun handleStartActivity(method: Method, args: Array<Any?>?): Any? {
        Log.d(TAG, "Intercepting startActivity")
        // Replace intent target with stub activity
        if (args != null && args.isNotEmpty()) {
            for (i in args.indices) {
                val arg = args[i]
                if (arg is android.content.Intent) {
                    val originalIntent = arg
                    val targetPkg = originalIntent.component?.packageName
                    if (targetPkg != null && isVirtualApp(targetPkg)) {
                        // Redirect through stub
                        val stubIntent = createStubIntent(originalIntent)
                        args[i] = stubIntent
                        Log.d(TAG, "Redirected $targetPkg through stub")
                    }
                }
            }
        }
        return method.invoke(null, *(args ?: emptyArray()))
    }

    private fun handleStartService(method: Method, args: Array<out Any?>?): Any? {
        Log.d(TAG, "Intercepting startService")
        return method.invoke(null, *(args ?: emptyArray()))
    }

    private fun handleSendBroadcast(method: Method, args: Array<out Any?>?): Any? {
        Log.d(TAG, "Intercepting sendBroadcast")
        return method.invoke(null, *(args ?: emptyArray()))
    }

    private fun handleGetRunningProcesses(method: Method, args: Array<out Any?>?): Any? {
        val result = method.invoke(null, *(args ?: emptyArray()))
        // Filter out virtual engine processes
        return result
    }

    private fun handleCheckPermission(method: Method, args: Array<out Any?>?): Any? {
        // Grant all permissions for virtual apps
        return android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isVirtualApp(packageName: String): Boolean {
        return com.vspace.engine.VirtualCore.get().isAppInstalled(packageName)
    }

    private fun createStubIntent(original: android.content.Intent): android.content.Intent {
        val stubIndex = 0 // Determine correct stub slot
        return android.content.Intent().apply {
            setClassName(
                "com.vspace.stub$stubIndex",
                "com.vspace.stub.StubActivity"
            )
            putExtra("original_intent", original)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
