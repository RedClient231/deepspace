#pragma once

#include <jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <sys/types.h>

// ── Hook Registration ───────────────────────────────────────────────

/**
 * Initialize PLT hooks for libc functions.
 * Hooks: open, openat, fopen, stat, lstat, readlink, access, mkdir,
 *        rename, unlink, execve, ptrace
 */
int hook_init();

/**
 * Refresh and apply all registered hooks.
 */
int hook_refresh();

/**
 * Register a PLT hook for a specific function in a specific library.
 */
int hook_register(const char* regex_lib, const char* func_name,
                  void* hook_func, void** orig_func);

// ── Hooked Function Declarations ────────────────────────────────────

int hook_open(const char* pathname, int flags, ...);
int hook_openat(int dirfd, const char* pathname, int flags, ...);
FILE* hook_fopen(const char* pathname, const char* mode);
int hook_stat(const char* pathname, struct stat* buf);
int hook_access(const char* pathname, int mode);
long hook_ptrace(int request, pid_t pid, void* addr, void* data);
int hook_execve(const char* filename, char* const argv[], char* const envp[]);

// ── IO Redirection ─────────────────────────────────────────────────

/**
 * Check if a path should be redirected for a virtual app.
 * Returns the redirected path, or nullptr if no redirect needed.
 */
const char* redirect_path(const char* path);

/**
 * Initialize the IO redirection system.
 */
void io_redirect_init(const char* virtual_root);

/**
 * Add a virtual package to the redirect map.
 */
void io_redirect_add_package(const char* package_name, const char* data_dir);

// ── Memory Bridge ──────────────────────────────────────────────────

/**
 * Initialize the memory bridge (connect to daemon).
 */
int memory_bridge_init();

/**
 * Read memory from a target process via the daemon.
 */
int memory_bridge_read(int pid, uint64_t addr, void* buf, size_t len);

/**
 * Write memory to a target process via the daemon.
 */
int memory_bridge_write(int pid, uint64_t addr, const void* buf, size_t len);

/**
 * Register a PID with the daemon.
 */
int memory_bridge_register_pid(int pid);

// ── Su Stub ─────────────────────────────────────────────────────────

/**
 * Initialize the fake su binary handler.
 */
void su_stub_init();

/**
 * Handle a su command request.
 */
int su_stub_exec(const char* command);
