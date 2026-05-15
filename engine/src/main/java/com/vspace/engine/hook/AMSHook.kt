package com.vspace.engine.hook

import android.content.Intent
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
            "startActivity" -> handleStartActivity(method, args)
            "startService" -> handleStartService(method, args)
            "sendBroadcast" -> handleSendBroadcast(method, args)
            "getRunningAppProcesses" -> handleGetRunningProcesses(method, args)
            "checkPermission" -> handleCheckPermission(method, args)
            else -> method.invoke(proxy, *(args ?: emptyArray()))
        }
    }

    private fun handleStartActivity(method: Method, args: Array<out Any?>?): Any? {
        Log.d(TAG, "Intercepting startActivity")
        if (args != null && args.isNotEmpty()) {
            val newArgs = arrayOfNulls<Any>(args.size)
            for (i in args.indices) {
                newArgs[i] = args[i]
            }
            for (i in newArgs.indices) {
                val arg = newArgs[i]
                if (arg is Intent) {
                    val targetPkg = arg.component?.packageName
                    if (targetPkg != null && isVirtualApp(targetPkg)) {
                        val stubIntent = createStubIntent(arg)
                        newArgs[i] = stubIntent
                        Log.d(TAG, "Redirected $targetPkg through stub")
                    }
                }
            }
            return method.invoke(null, *newArgs)
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
        return result
    }

    private fun handleCheckPermission(method: Method, args: Array<out Any?>?): Any? {
        return android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isVirtualApp(packageName: String): Boolean {
        return com.vspace.engine.VirtualCore.get().isAppInstalled(packageName)
    }

    private fun createStubIntent(original: Intent): Intent {
        val stubIndex = 0
        return Intent().apply {
            setClassName(
                "com.vspace.stub$stubIndex",
                "com.vspace.engine.stub.StubActivity$stubIndex"
            )
            putExtra("original_intent", original)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
