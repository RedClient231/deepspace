#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "memory_bridge.h"
#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <fcntl.h>
#include <sys/uio.h>
#include <errno.h>
#include <sys/syscall.h>

#define TAG "memory_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// process_vm_readv/writev syscall numbers
#if !defined(__NR_process_vm_readv)
  #if defined(__aarch64__) || defined(__x86_64__)
    #define __NR_process_vm_readv 270
    #define __NR_process_vm_writev 271
  #elif defined(__arm__) || defined(__i386__)
    #define __NR_process_vm_readv 376
    #define __NR_process_vm_writev 377
  #endif
#endif

static ssize_t process_vm_readv_compat(pid_t pid, const struct iovec *local_iov,
                                        unsigned long liovcnt, const struct iovec *remote_iov,
                                        unsigned long riovcnt, unsigned long flags) {
    return syscall(__NR_process_vm_readv, pid, local_iov, liovcnt, remote_iov, riovcnt, flags);
}

static ssize_t process_vm_writev_compat(pid_t pid, const struct iovec *local_iov,
                                         unsigned long liovcnt, const struct iovec *remote_iov,
                                         unsigned long riovcnt, unsigned long flags) {
    return syscall(__NR_process_vm_writev, pid, local_iov, liovcnt, remote_iov, riovcnt, flags);
}

// ── State ──────────────────────────────────────────────────────────

static int g_socket = -1;
static bool g_connected = false;
static char g_port_file_path[512] = {0};

// ── Connection ─────────────────────────────────────────────────────

static int read_daemon_port() {
    if (g_port_file_path[0] == '\0') {
        LOGE("memory_bridge_init: port file path not set");
        return -1;
    }
    FILE* f = fopen(g_port_file_path, "r");
    if (!f) {
        LOGD("memory_bridge_init: port file not found: %s", g_port_file_path);
        return -1;
    }
    int port = -1;
    if (fscanf(f, "%d", &port) != 1) {
        fclose(f);
        return -1;
    }
    fclose(f);
    return port;
}

int memory_bridge_init() {
    if (g_connected) return 0;

    int port = read_daemon_port();
    if (port <= 0) {
        LOGE("memory_bridge_init: daemon port not available");
        return -1;
    }

    // Connect to daemon
    g_socket = socket(AF_INET, SOCK_STREAM, 0);
    if (g_socket < 0) {
        LOGE("memory_bridge_init: socket() failed: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);

    if (connect(g_socket, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("memory_bridge_init: connect() failed: %s", strerror(errno));
        close(g_socket);
        g_socket = -1;
        return -1;
    }

    g_connected = true;
    LOGI("memory_bridge_init: connected to daemon on port %d", port);
    return 0;
}

void memory_bridge_cleanup() {
    if (g_socket >= 0) {
        close(g_socket);
        g_socket = -1;
    }
    g_connected = false;
}

/**
 * Set the port file path. Called from JNI before init.
 */
void memory_bridge_set_port_file(const char* path) {
    if (path) {
        strncpy(g_port_file_path, path, sizeof(g_port_file_path) - 1);
        g_port_file_path[sizeof(g_port_file_path) - 1] = '\0';
        LOGD("memory_bridge_set_port_file: %s", g_port_file_path);
    }
}

// ── Memory Operations ──────────────────────────────────────────────

static bool send_all(int fd, const void* buf, size_t len) {
    const uint8_t* p = (const uint8_t*)buf;
    while (len > 0) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n <= 0) return false;
        p += n;
        len -= n;
    }
    return true;
}

static bool recv_all(int fd, void* buf, size_t len) {
    uint8_t* p = (uint8_t*)buf;
    while (len > 0) {
        ssize_t n = recv(fd, p, len, 0);
        if (n <= 0) return false;
        p += n;
        len -= n;
    }
    return true;
}

int memory_bridge_register_pid(int pid) {
    if (!g_connected && memory_bridge_init() < 0) return -1;

    int req = REQ_REGISTER_PID;
    if (!send_all(g_socket, &req, sizeof(req))) return -1;
    if (!send_all(g_socket, &pid, sizeof(pid))) return -1;

    int resp;
    if (!recv_all(g_socket, &resp, sizeof(resp))) return -1;
    return (resp == RESP_OK) ? 0 : -1;
}

int memory_bridge_read(int pid, uint64_t addr, void* buf, size_t len) {
    if (!g_connected && memory_bridge_init() < 0) return -1;

    int req = REQ_READ_MEM;
    if (!send_all(g_socket, &req, sizeof(req))) return -1;
    if (!send_all(g_socket, &pid, sizeof(pid))) return -1;
    if (!send_all(g_socket, &addr, sizeof(addr))) return -1;
    int size = (int)len;
    if (!send_all(g_socket, &size, sizeof(size))) return -1;

    int resp;
    if (!recv_all(g_socket, &resp, sizeof(resp))) return -1;
    if (resp != RESP_OK) return -1;

    int data_len;
    if (!recv_all(g_socket, &data_len, sizeof(data_len))) return -1;
    if (data_len > (int)len) return -1;
    if (!recv_all(g_socket, buf, data_len)) return -1;

    return data_len;
}

int memory_bridge_write(int pid, uint64_t addr, const void* buf, size_t len) {
    if (!g_connected && memory_bridge_init() < 0) return -1;

    int req = REQ_WRITE_MEM;
    if (!send_all(g_socket, &req, sizeof(req))) return -1;
    if (!send_all(g_socket, &pid, sizeof(pid))) return -1;
    if (!send_all(g_socket, &addr, sizeof(addr))) return -1;
    int size = (int)len;
    if (!send_all(g_socket, &size, sizeof(size))) return -1;
    if (!send_all(g_socket, buf, len)) return -1;

    int resp;
    if (!recv_all(g_socket, &resp, sizeof(resp))) return -1;
    return (resp == RESP_OK) ? (int)len : -1;
}

bool memory_bridge_is_connected() {
    return g_connected;
}

// ── Direct process_vm_readv/writev (used by daemon) ────────────────

int direct_read_memory(int pid, uint64_t addr, void* buf, size_t len) {
    struct iovec local_iov = { .iov_base = buf, .iov_len = len };
    struct iovec remote_iov = { .iov_base = (void*)(uintptr_t)addr, .iov_len = len };
    ssize_t n = process_vm_readv_compat(pid, &local_iov, 1, &remote_iov, 1, 0);
    if (n < 0) {
        LOGE("process_vm_readv failed: pid=%d addr=0x%lx err=%s",
             pid, (unsigned long)addr, strerror(errno));
        return -1;
    }
    return (int)n;
}

int direct_write_memory(int pid, uint64_t addr, const void* buf, size_t len) {
    struct iovec local_iov = { .iov_base = (void*)buf, .iov_len = len };
    struct iovec remote_iov = { .iov_base = (void*)(uintptr_t)addr, .iov_len = len };
    ssize_t n = process_vm_writev_compat(pid, &local_iov, 1, &remote_iov, 1, 0);
    if (n < 0) {
        LOGE("process_vm_writev failed: pid=%d addr=0x%lx err=%s",
             pid, (unsigned long)addr, strerror(errno));
        return -1;
    }
    return (int)n;
}
