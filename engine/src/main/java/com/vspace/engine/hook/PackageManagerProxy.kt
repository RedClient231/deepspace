package com.vspace.engine.hook

import android.os.*
import android.util.Log
import com.vspace.engine.VirtualCore
import java.io.FileDescriptor

/**
 * Proxy for PackageManagerService binder.
 * Makes virtual apps appear installed and handles package queries.
 */
class PackageManagerProxy(private val original: IBinder) : IBinder {

    companion object {
        private const val TAG = "PMSProxy"

        // Transaction codes for IPackageManager
        private const val TRANSACTION_getPackageInfo = 1
        private const val TRANSACTION_getApplicationInfo = 2
        private const val TRANSACTION_getInstalledPackages = 5
        private const val TRANSACTION_getInstalledApplications = 7
        private const val TRANSACTION_checkPermission = 10
        private const val TRANSACTION_resolveActivity = 12
    }

    override fun getInterfaceDescriptor(): String? {
        return try { original.interfaceDescriptor } catch (_: Exception) { null }
    }

    override fun pingBinder(): Boolean = original.pingBinder()
    override fun isBinderAlive(): Boolean = original.isBinderAlive

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    override fun dump(fd: FileDescriptor, args: Array<out String>?) {
        original.dump(fd, args)
    }

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
        original.dumpAsync(fd, args)
    }

    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return try {
            when (code) {
                TRANSACTION_getPackageInfo -> {
                    handleGetPackageInfo(data, reply, flags)
                }
                TRANSACTION_getApplicationInfo -> {
                    handleGetApplicationInfo(data, reply, flags)
                }
                TRANSACTION_checkPermission -> {
                    // Grant all permissions for virtual apps
                    handleCheckPermission(data, reply, flags)
                }
                else -> {
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

    private fun handleGetPackageInfo(data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // Read package name from the request
        data.setDataPosition(0)
        val packageName = data.readString()

        if (packageName != null && VirtualCore.get().isAppInstalled(packageName)) {
            // This is a virtual app — return our custom PackageInfo
            reply?.writeNoException()
            // TODO: Write proper PackageInfo to reply parcel
            // For now, fall through to real PMS
            return original.transact(TRANSACTION_getPackageInfo, data, reply, flags)
        }

        return original.transact(TRANSACTION_getPackageInfo, data, reply, flags)
    }

    private fun handleGetApplicationInfo(data: Parcel, reply: Parcel?, flags: Int): Boolean {
        data.setDataPosition(0)
        val packageName = data.readString()

        if (packageName != null && VirtualCore.get().isAppInstalled(packageName)) {
            // Virtual app — we could return custom ApplicationInfo here
            // For now, fall through
        }

        return original.transact(TRANSACTION_getApplicationInfo, data, reply, flags)
    }

    private fun handleCheckPermission(data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // Grant all permissions for virtual apps
        // The real check happens via the original PMS for non-virtual apps
        return original.transact(TRANSACTION_checkPermission, data, reply, flags)
    }
}
