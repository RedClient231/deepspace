package com.vspace.engine.ipc

import android.util.Log

/**
 * Provides memory read/write operations using process_vm_readv/writev.
 * Since all virtual processes share the same UID (via sharedUserId),
 * these operations work without root or ptrace.
 */
class MemoryBridge {

    companion object {
        private const val TAG = "MemoryBridge"

        init {
            try {
                System.loadLibrary("vengine")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native memory bridge not available")
            }
        }
    }

    /**
     * Read memory from a target process.
     * Uses process_vm_readv via native code.
     */
    fun readMemory(pid: Int, address: Long, size: Int): ByteArray? {
        return try {
            nativeReadMemory(pid, address, size)
        } catch (e: Exception) {
            Log.e(TAG, "readMemory failed: pid=$pid addr=0x${address.toString(16)} size=$size", e)
            null
        }
    }

    /**
     * Write memory to a target process.
     * Uses process_vm_writev via native code.
     */
    fun writeMemory(pid: Int, address: Long, data: ByteArray): Boolean {
        return try {
            nativeWriteMemory(pid, address, data)
        } catch (e: Exception) {
            Log.e(TAG, "writeMemory failed: pid=$pid addr=0x${address.toString(16)}", e)
            false
        }
    }

    /**
     * Check if we can access a target process's memory.
     */
    fun canAccess(pid: Int): Boolean {
        return try {
            val testRead = nativeReadMemory(pid, 0, 1)
            true // If it doesn't crash, we have access
        } catch (e: Exception) {
            false
        }
    }

    // ── JNI ─────────────────────────────────────────────────────────

    private external fun nativeReadMemory(pid: Int, address: Long, size: Int): ByteArray?
    private external fun nativeWriteMemory(pid: Int, address: Long, data: ByteArray): Boolean
}
