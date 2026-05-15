package com.vspace.engine.ipc

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Unix domain socket server that provides memory read/write services
 * to cloned processes. Uses process_vm_readv/writev for actual memory
 * operations (no root required since all processes share the same UID).
 */
class DaemonServer {

    companion object {
        private const val TAG = "DaemonServer"
        private const val SOCKET_NAME = "vspace_daemon"
        private const val PORT = 0 // Use abstract namespace

        // Request types
        const val REQ_READ_MEM = 1
        const val REQ_WRITE_MEM = 2
        const val REQ_REGISTER_PID = 3
        const val REQ_GET_PIDS = 4
        const val REQ_PING = 5

        // Response codes
        const val RESP_OK = 0
        const val RESP_ERROR = -1
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private val registeredPids = ConcurrentHashMap<Int, Boolean>()
    private val memoryBridge = MemoryBridge()

    fun start() {
        if (running) return
        running = true

        executor.execute {
            try {
                // Use abstract Unix domain socket via /proc/self/fd trick
                // For simplicity, we use a local TCP socket on loopback
                serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
                val port = serverSocket!!.localPort
                Log.i(TAG, "Daemon listening on 127.0.0.1:$port")

                // Write port to file so clients can find it
                val portFile = File("/data/data/com.vspace.app/files/virtual_space/daemon_port")
                portFile.writeText(port.toString())

                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        executor.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server failed", e)
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        Log.i(TAG, "Daemon stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            while (running && !socket.isClosed) {
                val requestType = input.readInt()
                when (requestType) {
                    REQ_PING -> {
                        output.writeInt(RESP_OK)
                        output.flush()
                    }
                    REQ_REGISTER_PID -> {
                        val pid = input.readInt()
                        registeredPids[pid] = true
                        output.writeInt(RESP_OK)
                        output.flush()
                        Log.d(TAG, "Registered PID: $pid")
                    }
                    REQ_GET_PIDS -> {
                        output.writeInt(RESP_OK)
                        output.writeInt(registeredPids.size)
                        for (pid in registeredPids.keys) {
                            output.writeInt(pid)
                        }
                        output.flush()
                    }
                    REQ_READ_MEM -> {
                        val pid = input.readInt()
                        val address = input.readLong()
                        val size = input.readInt()
                        val data = memoryBridge.readMemory(pid, address, size)
                        if (data != null) {
                            output.writeInt(RESP_OK)
                            output.writeInt(data.size)
                            output.write(data)
                        } else {
                            output.writeInt(RESP_ERROR)
                        }
                        output.flush()
                    }
                    REQ_WRITE_MEM -> {
                        val pid = input.readInt()
                        val address = input.readLong()
                        val size = input.readInt()
                        val data = ByteArray(size)
                        input.readFully(data)
                        val success = memoryBridge.writeMemory(pid, address, data)
                        output.writeInt(if (success) RESP_OK else RESP_ERROR)
                        output.flush()
                    }
                    else -> {
                        Log.w(TAG, "Unknown request type: $requestType")
                        output.writeInt(RESP_ERROR)
                        output.flush()
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun isPidRegistered(pid: Int): Boolean = registeredPids.containsKey(pid)
}
