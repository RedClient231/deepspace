package com.vspace.engine.hook

import android.content.Intent
import android.os.*
import android.util.Log
import com.vspace.engine.VirtualCore
import java.io.FileDescriptor

/**
 * Proxy for ActivityManagerService binder.
 * Intercepts startActivity, getRunningAppProcesses, etc.
 *
 * This implements IBinder directly (not via Java Proxy) because
 * Android's binder system requires real IBinder implementations.
 */
class ActivityManagerProxy(private val original: IBinder) : IBinder {

    companion object {
        private const val TAG = "AMSProxy"

        // Transaction codes for IActivityManager
        // These are the actual Binder transaction codes used by Android
        private const val TRANSACTION_startActivity = 5  // IActivityManager.Stub.TRANSACTION_startActivity
        private const val TRANSACTION_getRunningAppProcesses = 14
    }

    override fun getInterfaceDescriptor(): String? {
        return try { original.interfaceDescriptor } catch (_: Exception) { null }
    }

    override fun pingBinder(): Boolean = original.pingBinder()

    override fun isBinderAlive(): Boolean = original.isBinderAlive

    override fun queryLocalInterface(descriptor: String): IInterface? {
        // Return null so the system uses the proxy path
        return null
    }

    override fun dump(fd: FileDescriptor, args: Array<out String>?) {
        original.dump(fd, args)
    }

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
        original.dumpAsync(fd, args)
    }

    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return try {
            // Intercept specific transactions
            when (code) {
                TRANSACTION_startActivity -> {
                    handleStartActivity(data, reply, flags)
                }
                TRANSACTION_getRunningAppProcesses -> {
                    handleGetRunningAppProcesses(data, reply, flags)
                }
                else -> {
                    // Pass through to original
                    original.transact(code, data, reply, flags)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "transact failed for code $code: ${e.message}")
            original.transact(code, data, reply, flags)
        }
    }

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        original.linkToDeath(recipient, flags)
    }

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient?, flags: Int): Boolean {
        return original.unlinkToDeath(recipient, flags)
    }

    private fun handleStartActivity(data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // Read the intent from the parcel
        data.setDataPosition(0)
        // The first field is the calling package, then the intent
        // For now, just pass through — the stub activity mechanism handles redirection
        return original.transact(TRANSACTION_startActivity, data, reply, flags)
    }

    private fun handleGetRunningAppProcesses(data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val result = original.transact(TRANSACTION_getRunningAppProcesses, data, reply, flags)
        // TODO: Filter out virtual engine processes from the list
        return result
    }
}
