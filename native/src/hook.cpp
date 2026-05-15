#include "hook.h"
#include "io_redirect.h"
#include "memory_bridge.h"
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <unordered_map>
#include <mutex>
#include <string>
#include <sys/ptrace.h>
#include <sys/stat.h>

#define TAG "vhook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ── Hook Entry ─────────────────────────────────────────────────────

struct HookEntry {
    std::string lib_pattern;
    std::string func_name;
    void* hook_func;
    void** orig_func;
};

static std::vector<HookEntry> g_hooks;
static std::mutex g_hooks_mutex;
static bool g_hooks_applied = false;

// ── PLT Hooking Implementation ─────────────────────────────────────
// Simple GOT/PLT hooking by parsing ELF sections

static void* get_symbol_addr(const char* lib_path, const char* symbol) {
    void* handle = dlopen(lib_path, RTLD_NOW);
    if (!handle) return nullptr;
    void* addr = dlsym(handle, symbol);
    dlclose(handle);
    return addr;
}

// ── Hook Registration ──────────────────────────────────────────────

int hook_init() {
    std::lock_guard<std::mutex> lock(g_hooks_mutex);
    g_hooks.clear();
    g_hooks_applied = false;
    LOGI("hook_init: initialized");
    return 0;
}

int hook_register(const char* regex_lib, const char* func_name,
                  void* hook_func, void** orig_func) {
    std::lock_guard<std::mutex> lock(g_hooks_mutex);
    HookEntry entry;
    entry.lib_pattern = regex_lib;
    entry.func_name = func_name;
    entry.hook_func = hook_func;
    entry.orig_func = orig_func;
    g_hooks.push_back(entry);
    LOGD("hook_register: %s -> %s", func_name, regex_lib);
    return 0;
}

int hook_refresh() {
    std::lock_guard<std::mutex> lock(g_hooks_mutex);
    LOGI("hook_refresh: applying %zu hooks", g_hooks.size());

    for (auto& entry : g_hooks) {
        // In production, iterate /proc/self/maps to find matching libraries
        // and patch their GOT entries. For now, use dlsym-based hooking.
        void* orig = get_symbol_addr(nullptr, entry.func_name.c_str());
        if (orig && entry.orig_func) {
            *entry.orig_func = orig;
            LOGD("hook_refresh: saved original %s at %p", entry.func_name.c_str(), orig);
        }
    }

    g_hooks_applied = true;
    return 0;
}

// ── Hooked Functions ───────────────────────────────────────────────

// These are the actual hook implementations that intercept syscalls

static int (*orig_open)(const char*, int, ...) = nullptr;
static int (*orig_openat)(int, const char*, int, ...) = nullptr;
static FILE* (*orig_fopen)(const char*, const char*) = nullptr;
static int (*orig_stat)(const char*, struct stat*) = nullptr;
static int (*orig_access)(const char*, int) = nullptr;
static long (*orig_ptrace)(int, pid_t, void*, void*) = nullptr;
static int (*orig_execve)(const char*, char* const[], char* const[]) = nullptr;

int hook_open(const char* pathname, int flags, ...) {
    const char* redirected = redirect_path(pathname);
    if (redirected) {
        LOGD("hook_open: %s -> %s", pathname, redirected);
        pathname = redirected;
    }
    if (orig_open) return orig_open(pathname, flags);
    return -1;
}

int hook_openat(int dirfd, const char* pathname, int flags, ...) {
    const char* redirected = redirect_path(pathname);
    if (redirected) {
        LOGD("hook_openat: %s -> %s", pathname, redirected);
        pathname = redirected;
    }
    if (orig_openat) return orig_openat(dirfd, pathname, flags);
    return -1;
}

FILE* hook_fopen(const char* pathname, const char* mode) {
    const char* redirected = redirect_path(pathname);
    if (redirected) {
        LOGD("hook_fopen: %s -> %s", pathname, redirected);
        pathname = redirected;
    }
    if (orig_fopen) return orig_fopen(pathname, mode);
    return nullptr;
}

int hook_stat(const char* pathname, struct stat* buf) {
    const char* redirected = redirect_path(pathname);
    if (redirected) pathname = redirected;
    if (orig_stat) return orig_stat(pathname, buf);
    return -1;
}

int hook_access(const char* pathname, int mode) {
    const char* redirected = redirect_path(pathname);
    if (redirected) pathname = redirected;
    if (orig_access) return orig_access(pathname, mode);
    return -1;
}

long hook_ptrace(int request, pid_t pid, void* addr, void* data) {
    // GameGuardian uses ptrace for memory access
    // Redirect to memory bridge instead of real ptrace
    if (request == PTRACE_ATTACH) {
        LOGD("hook_ptrace: PTRACE_ATTACH pid=%d -> faking success", pid);
        memory_bridge_register_pid(pid);
        return 0; // Fake success
    }
    if (request == PTRACE_PEEKDATA) {
        long result = 0;
        int ret = memory_bridge_read(pid, (uint64_t)addr, &result, sizeof(result));
        if (ret > 0) return result;
        return 0;
    }
    if (request == PTRACE_POKEDATA) {
        int ret = memory_bridge_write(pid, (uint64_t)addr, data, sizeof(long));
        return ret > 0 ? 0 : -1;
    }
    if (request == PTRACE_DETACH) {
        LOGD("hook_ptrace: PTRACE_DETACH pid=%d -> faking success", pid);
        return 0;
    }
    // For other requests, try real ptrace
    if (orig_ptrace) return orig_ptrace(request, pid, addr, data);
    return -1;
}

int hook_execve(const char* filename, char* const argv[], char* const envp[]) {
    // Intercept su execution
    if (filename && strstr(filename, "/su") != nullptr) {
        LOGD("hook_execve: intercepted su call -> faking success");
        return su_stub_exec(argv[2]); // argv[2] is usually the command
    }
    if (orig_execve) return orig_execve(filename, argv, envp);
    return -1;
}
