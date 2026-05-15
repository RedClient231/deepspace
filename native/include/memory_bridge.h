#pragma once

#include <cstdint>
#include <cstddef>

/**
 * Memory bridge header.
 * Provides IPC-based memory read/write for GameGuardian support.
 * Uses process_vm_readv/writev via the daemon process.
 */

// Request types (must match DaemonServer constants)
enum MemoryRequest : int {
    REQ_READ_MEM = 1,
    REQ_WRITE_MEM = 2,
    REQ_REGISTER_PID = 3,
    REQ_GET_PIDS = 4,
    REQ_PING = 5
};

// Response codes
enum MemoryResponse : int {
    RESP_OK = 0,
    RESP_ERROR = -1
};

int memory_bridge_init();
void memory_bridge_cleanup();
int memory_bridge_read(int pid, uint64_t addr, void* buf, size_t len);
int memory_bridge_write(int pid, uint64_t addr, const void* buf, size_t len);
int memory_bridge_register_pid(int pid);
bool memory_bridge_is_connected();
