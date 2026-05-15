package com.vspace.engine.ipc

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket

/**
 * Client for the daemon's memory bridge. Runs inside cloned processes
 * to communicate with the host daemon for memory operations.
 */
class DaemonClient(private val dataDir: File) {

    companion object {
        private const val TAG = "DaemonClient"
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 300L

        // Must match DaemonServer constants
        private const val REQ_READ_MEM = 1
        private const val REQ_WRITE_MEM = 2
        private const val REQ_REGISTER_PID = 3
        private const val REQ_GET_PIDS = 4
        private const val REQ_PING = 5
        private const val RESP_OK = 0
        private const val RESP_ERROR = -1
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    /** Port file — must match DaemonServer.portFile */
    private val portFile: File = File(dataDir, "daemon_port")

    fun connect(): Boolean {
        for (attempt in 1..MAX_RETRIES) {
            try {
                if (!portFile.exists()) {
                    Log.w(TAG, "Port file not found (${portFile.absolutePath}), retry $attempt")
                    Thread.sleep(RETRY_DELAY_MS)
                    continue
                }
                val port = portFile.readText().trim().toInt()
                if (port <= 0) {
                    Log.w(TAG, "Invalid port: $port, retry $attempt")
                    Thread.sleep(RETRY_DELAY_MS)
                    continue
                }
                socket = Socket("127.0.0.1", port)
                input = DataInputStream(socket!!.getInputStream())
                output = DataOutputStream(socket!!.getOutputStream())
                Log.i(TAG, "Connected to daemon on port $port")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Connection attempt $attempt failed: ${e.message}")
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        return false
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    fun registerPid(pid: Int): Boolean {
        return try {
            output?.writeInt(REQ_REGISTER_PID)
            output?.writeInt(pid)
            output?.flush()
            input?.readInt() == RESP_OK
        } catch (e: Exception) {
            Log.e(TAG, "registerPid failed", e)
            false
        }
    }

    fun readMemory(pid: Int, address: Long, size: Int): ByteArray? {
        return try {
            output?.writeInt(REQ_READ_MEM)
            output?.writeInt(pid)
            output?.writeLong(address)
            output?.writeInt(size)
            output?.flush()
            val resp = input?.readInt() ?: return null
            if (resp == RESP_OK) {
                val dataSize = input!!.readInt()
                val data = ByteArray(dataSize)
                input!!.readFully(data)
                data
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "readMemory failed", e)
            null
        }
    }

    fun writeMemory(pid: Int, address: Long, data: ByteArray): Boolean {
        return try {
            output?.writeInt(REQ_WRITE_MEM)
            output?.writeInt(pid)
            output?.writeLong(address)
            output?.writeInt(data.size)
            output?.write(data)
            output?.flush()
            input?.readInt() == RESP_OK
        } catch (e: Exception) {
            Log.e(TAG, "writeMemory failed", e)
            false
        }
    }

    fun ping(): Boolean {
        return try {
            output?.writeInt(REQ_PING)
            output?.flush()
            input?.readInt() == RESP_OK
        } catch (e: Exception) {
            false
        }
    }
}
