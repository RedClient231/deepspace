#include "hook.h"
#include <android/log.h>
#include <unistd.h>
#include <cstring>
#include <cstdlib>

#define TAG "su_stub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// ── Fake su Implementation ─────────────────────────────────────────
// GameGuardian tries to execute "su" to get root access.
// We intercept this and return success without actually granting root.
// All memory operations are handled by our hooks instead.

static bool g_su_initialized = false;

void su_stub_init() {
    if (g_su_initialized) return;
    g_su_initialized = true;
    LOGI("su_stub_init: fake su handler initialized");
}

/**
 * Handle a su command request.
 * Instead of actually running as root, we:
 * 1. Return success (exit code 0)
 * 2. Let the calling process continue with our hooks active
 * 3. The memory bridge handles all memory operations
 */
int su_stub_exec(const char* command) {
    LOGI("su_stub_exec: intercepted su command: %s", command ? command : "(null)");

    if (!command) {
        // No command, just return success
        return 0;
    }

    // GameGuardian typically runs: su -c <daemon_command>
    // We don't need to execute anything because our hooks are
    // already loaded in the process and handle memory access.

    LOGD("su_stub_exec: returning success (hooks handle everything)");
    return 0;
}

/**
 * Check if a path points to our fake su binary.
 */
bool is_su_path(const char* path) {
    if (!path) return false;
    return (strstr(path, "/su") != nullptr &&
            (strstr(path, "/system/bin/") != nullptr ||
             strstr(path, "/system/xbin/") != nullptr));
}

/**
 * Create the fake su binary in the virtual environment.
 * This is a minimal ELF that returns 0 immediately.
 */
int create_fake_su(const char* path) {
    // Minimal ELF x86_64 binary that does: exit(0)
    static const unsigned char FAKE_SU[] = {
        0x7f, 0x45, 0x4c, 0x46,  // ELF magic
        0x02, 0x01, 0x01, 0x00,  // 64-bit, little endian, version 1
        0x00, 0x00, 0x00, 0x00,  // padding
        0x00, 0x00, 0x00, 0x00,
        0x02, 0x00, 0x3e, 0x00,  // executable, x86_64
        0x01, 0x00, 0x00, 0x00,  // version 1
        0x78, 0x00, 0x40, 0x00,  // entry point
        0x40, 0x00, 0x00, 0x00,  // phoff
        0x00, 0x00, 0x00, 0x00,  // shoff
        0x00, 0x00, 0x00, 0x00,  // flags
        0x40, 0x00,  // ehsize
        0x38, 0x00,  // phentsize
        0x01, 0x00,  // phnum
        0x40, 0x00,  // shentsize
        0x00, 0x00,  // shnum
        0x00, 0x00,  // shstrndx
        // Program header
        0x01, 0x00, 0x00, 0x00,  // PT_LOAD
        0x05, 0x00, 0x00, 0x00,  // PF_R | PF_X
        0x78, 0x00, 0x00, 0x00,  // offset
        0x78, 0x00, 0x40, 0x00,  // vaddr
        0x78, 0x00, 0x40, 0x00,  // paddr
        0x10, 0x00, 0x00, 0x00,  // filesz
        0x10, 0x00, 0x00, 0x00,  // memsz
        0x10, 0x00, 0x00, 0x00,  // align
        // Code at entry 0x400078:
        // xor edi, edi   (31 ff)
        // mov eax, 231   (b8 e7 00 00 00)  -- exit_group(0)
        // syscall         (0f 05)
        0x31, 0xff, 0xb8, 0xe7, 0x00, 0x00, 0x00, 0x0f,
        0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    FILE* f = fopen(path, "wb");
    if (!f) {
        LOGE("create_fake_su: failed to open %s", path);
        return -1;
    }
    fwrite(FAKE_SU, 1, sizeof(FAKE_SU), f);
    fclose(f);
    chmod(path, 0755);

    LOGI("create_fake_su: created fake su at %s", path);
    return 0;
}
