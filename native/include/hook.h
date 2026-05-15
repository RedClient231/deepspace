#pragma once

#include <jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <sys/types.h>
#include <stdint.h>

// ── Hook Registration ───────────────────────────────────────────────

/**
 * Initialize PLT hooks for libc functions.
 * Hooks: open, openat, fopen, stat, access, ptrace, execve
 */
int hook_init();

/**
 * Refresh and apply all registered hooks.
 * Scans /proc/self/maps and patches GOT entries.
 */
int hook_refresh();

/**
 * Unhook all registered hooks (restore original GOT entries).
 */
int hook_unhook_all();

/**
 * Register a PLT hook for a specific function in a specific library.
 * @param regex_lib  Regex pattern matching library paths (e.g. ".*\\.so$")
 * @param func_name  Symbol name to hook (e.g. "open")
 * @param hook_func  Replacement function pointer
 * @param orig_func  Out: receives pointer to original function
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
ssize_t hook_read(int fd, void* buf, size_t count);
ssize_t hook_write(int fd, const void* buf, size_t count);

// ── IO Redirection ─────────────────────────────────────────────────

const char* redirect_path(const char* path);
void io_redirect_init(const char* virtual_root);
void io_redirect_add_package(const char* package_name, const char* data_dir);

// ── Memory Bridge ──────────────────────────────────────────────────

int memory_bridge_init();
int memory_bridge_read(int pid, uint64_t addr, void* buf, size_t len);
int memory_bridge_write(int pid, uint64_t addr, const void* buf, size_t len);
int memory_bridge_register_pid(int pid);

// ── Su Stub ─────────────────────────────────────────────────────────

void su_stub_init();
int su_stub_exec(const char* command);
