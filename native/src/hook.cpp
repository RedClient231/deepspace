#include "hook.h"
#include "io_redirect.h"
#include "memory_bridge.h"
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <cstdio>
#include <vector>
#include <mutex>
#include <string>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <unistd.h>
#include <elf.h>
#include <link.h>
#include <errno.h>
#include <fcntl.h>
#include <fnmatch.h>
#include <sys/syscall.h>
#include <unistd.h>

#define TAG "vhook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ── Architecture Detection ──────────────────────────────────────────

#if defined(__LP64__)
  #define ELF_EHDR Elf64_Ehdr
  #define ELF_PHDR Elf64_Phdr
  #define ELF_SHDR Elf64_Shdr
  #define ELF_SYM  Elf64_Sym
  #define ELF_REL  Elf64_Rel
  #define ELF_RELA Elf64_Rela
  #define ELF_DYN  Elf64_Dyn
  #define ELF_R_SYM(x) ELF64_R_SYM(x)
  #define ELF_R_TYPE(x) ELF64_R_TYPE(x)
  #define ELF_ST_BIND(x) ELF64_ST_BIND(x)
  #define R_JUMP_SLOT R_X86_64_JUMP_SLOT
  #define R_GLOB_DAT  R_X86_64_GLOB_DAT
#else
  #define ELF_EHDR Elf32_Ehdr
  #define ELF_PHDR Elf32_Phdr
  #define ELF_SHDR Elf32_Shdr
  #define ELF_SYM  Elf32_Sym
  #define ELF_REL  Elf32_Rel
  #define ELF_RELA Elf32_Rela
  #define ELF_DYN  Elf32_Dyn
  #define ELF_R_SYM(x) ELF32_R_SYM(x)
  #define ELF_R_TYPE(x) ELF32_R_TYPE(x)
  #define ELF_ST_BIND(x) ELF32_ST_BIND(x)
  #define R_JUMP_SLOT R_ARM_JUMP_SLOT
  #define R_GLOB_DAT  R_ARM_GLOB_DAT
#endif

// ── Hook Entry ─────────────────────────────────────────────────────

struct HookEntry {
    std::string lib_pattern;
    std::string func_name;
    void* hook_func;
    void** orig_func;
    void* original_addr;    // saved original function address
    void** got_entry;       // GOT entry address (for unhooking)
};

static std::vector<HookEntry> g_hooks;
static std::mutex g_hooks_mutex;
static bool g_hooks_applied = false;

// ── /proc/self/maps Parsing ────────────────────────────────────────

struct MapEntry {
    uintptr_t start;
    uintptr_t end;
    std::string path;
    bool is_exec;
};

static std::vector<MapEntry> parse_maps() {
    std::vector<MapEntry> entries;
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return entries;

    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        uintptr_t start, end;
        char perms[5], path[256] = "";
        if (sscanf(line, "%lx-%lx %4s %*s %*s %*s %255[^\n]",
                   &start, &end, perms, path) >= 3) {
            MapEntry entry;
            entry.start = start;
            entry.end = end;
            entry.path = path;
            entry.is_exec = (perms[2] == 'x');
            // Trim leading whitespace from path
            size_t pos = entry.path.find_first_not_of(" \t");
            if (pos != std::string::npos) entry.path = entry.path.substr(pos);
            if (!entry.path.empty()) {
                entries.push_back(entry);
            }
        }
    }
    fclose(fp);
    return entries;
}

// ── ELF GOT Patching ───────────────────────────────────────────────

/**
 * Find .got.plt section and patch a specific symbol's entry.
 * Works with both ELF32 and ELF64.
 */
static int patch_got_in_library(uintptr_t base_addr, const char* lib_path,
                                 const char* symbol_name, void* new_func, void** orig_out) {
    // Read ELF header
    ELF_EHDR ehdr;
    if (base_addr == 0) return -1;

    memcpy(&ehdr, (void*)base_addr, sizeof(ELF_Ehdr));

    // Verify ELF magic
    if (memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0) {
        return -1;
    }

    // Find program headers to get dynamic section
    ELF_PHDR* phdrs = (ELF_PHDR*)(base_addr + ehdr.e_phoff);
    ELF_DYN* dynamic = nullptr;
    uintptr_t dynamic_vaddr = 0;

    for (int i = 0; i < ehdr.e_phnum; i++) {
        if (phdrs[i].p_type == PT_DYNAMIC) {
            dynamic = (ELF_DYN*)(base_addr + phdrs[i].p_vaddr);
            dynamic_vaddr = phdrs[i].p_vaddr;
            break;
        }
    }

    if (!dynamic) return -1;

    // Parse dynamic entries to find:
    // - .dynsym (DT_SYMTAB)
    // - .dynstr (DT_STRTAB)
    // - .rel.plt / .rela.plt (DT_JMPREL)
    // - .rel.dyn / .rela.dyn (DT_REL / DT_RELA)
    ELF_SYM* symtab = nullptr;
    const char* strtab = nullptr;
    void* jmprel = nullptr;
    size_t jmprel_size = 0;
    void* rel = nullptr;
    size_t rel_size = 0;
    size_t relent_size = sizeof(ELF_REL);
    size_t sym_ent_size = sizeof(ELF_SYM);

    for (ELF_DYN* d = dynamic; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_SYMTAB:
                symtab = (ELF_SYM*)(base_addr + d->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = (const char*)(base_addr + d->d_un.d_ptr);
                break;
            case DT_JMPREL:
                jmprel = (void*)(base_addr + d->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                jmprel_size = d->d_un.d_val;
                break;
            case DT_PLTREL:
                // DT_REL or DT_RELA
                relent_size = (d->d_un.d_val == DT_RELA) ? sizeof(ELF_RELA) : sizeof(ELF_REL);
                break;
            case DT_REL:
            case DT_RELA:
                rel = (void*)(base_addr + d->d_un.d_ptr);
                break;
            case DT_RELSZ:
            case DT_RELASZ:
                rel_size = d->d_un.d_val;
                break;
            case DT_RELENT:
            case DT_RELAENT:
                relent_size = d->d_un.d_val;
                break;
            case DT_SYMENT:
                sym_ent_size = d->d_un.d_val;
                break;
        }
    }

    if (!symtab || !strtab) {
        LOGE("patch_got: missing symtab or strtab for %s", lib_path);
        return -1;
    }

    // Find the symbol index by name
    int target_sym_idx = -1;
    // We need to know the number of symbols. Estimate from string table offset.
    // Walk symbols until we find our target.
    // Use a reasonable upper bound.
    for (int i = 0; i < 65536; i++) {
        ELF_SYM* sym = (ELF_SYM*)((uintptr_t)symtab + i * sym_ent_size);
        if (sym->st_name == 0 && ELF_ST_BIND(sym->st_info) == STB_LOCAL) continue;
        if (sym->st_name == 0) break;
        const char* name = strtab + sym->st_name;
        if (strcmp(name, symbol_name) == 0) {
            target_sym_idx = i;
            break;
        }
    }

    if (target_sym_idx < 0) {
        LOGD("patch_got: symbol '%s' not found in %s", symbol_name, lib_path);
        return -1;
    }

    // Search .rel.plt / .rela.plt (JMPREL) for the symbol
    void** got_entry = nullptr;
    void* original = nullptr;

    auto search_rel_section = [&](void* rel_start, size_t rel_sz, bool is_rela) -> bool {
        size_t entry_size = is_rela ? sizeof(ELF_RELA) : sizeof(ELF_REL);
        size_t count = rel_sz / entry_size;

        for (size_t i = 0; i < count; i++) {
            uintptr_t r_offset;
            uintptr_t r_info;

            if (is_rela) {
                ELF_RELA* r = (ELF_RELA*)((uintptr_t)rel_start + i * entry_size);
                r_offset = r->r_offset;
                r_info = r->r_info;
            } else {
                ELF_REL* r = (ELF_REL*)((uintptr_t)rel_start + i * entry_size);
                r_offset = r->r_offset;
                r_info = r->r_info;
            }

            if (ELF_R_TYPE(r_info) != R_JUMP_SLOT && ELF_R_TYPE(r_info) != R_GLOB_DAT) {
                continue;
            }

            if ((int)ELF_R_SYM(r_info) == target_sym_idx) {
                got_entry = (void**)(base_addr + r_offset);
                original = *got_entry;
                return true;
            }
        }
        return false;
    };

    // Try JMPREL first (PLT entries)
    bool found = false;
    if (jmprel && jmprel_size > 0) {
        found = search_rel_section(jmprel, jmprel_size, relent_size > sizeof(ELF_REL));
    }

    // Fall back to REL/RELA (GLOB_DAT entries)
    if (!found && rel && rel_size > 0) {
        found = search_rel_section(rel, rel_size, relent_size > sizeof(ELF_REL));
    }

    if (!found || !got_entry) {
        LOGD("patch_got: no GOT entry for '%s' in %s", symbol_name, lib_path);
        return -1;
    }

    // Verify the GOT entry points to a valid address
    if (original == nullptr || original == new_func) {
        LOGD("patch_got: GOT entry for '%s' already hooked or null", symbol_name);
        return -1;
    }

    // Save original and patch
    if (orig_out) *orig_out = original;

    // Make GOT entry writable
    uintptr_t page = (uintptr_t)got_entry & ~(sysconf(_SC_PAGESIZE) - 1);
    if (mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ | PROT_WRITE) != 0) {
        LOGE("patch_got: mprotect failed for '%s': %s", symbol_name, strerror(errno));
        return -1;
    }

    // Atomic write (void* is pointer-sized, naturally atomic on ARM/x86)
    *got_entry = new_func;

    // Restore protection
    mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ);

    LOGI("patch_got: hooked '%s' in %s: %p -> %p (GOT @ %p)",
         symbol_name, lib_path, original, new_func, got_entry);
    return 0;
}

// ── Hook Registration & Application ────────────────────────────────

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
    entry.original_addr = nullptr;
    entry.got_entry = nullptr;
    g_hooks.push_back(entry);
    LOGD("hook_register: %s -> %s", func_name, regex_lib);
    return 0;
}

int hook_refresh() {
    std::lock_guard<std::mutex> lock(g_hooks_mutex);
    LOGI("hook_refresh: applying %zu hooks", g_hooks.size());

    auto maps = parse_maps();
    int hooked_count = 0;

    for (auto& entry : g_hooks) {
        bool symbol_found = false;

        for (auto& map : maps) {
            if (!map.is_exec) continue;
            if (map.path.find("[") != std::string::npos) continue; // skip [vdso], [stack], etc.
            // Use fnmatch instead of regex for Android NDK compatibility
            if (fnmatch(entry.lib_pattern.c_str(), map.path.c_str(), 0) != 0 &&
                map.path.find(".so") == std::string::npos) continue;

            // Skip our own library
            if (map.path.find("libvengine.so") != std::string::npos) continue;

            void* orig = nullptr;
            int ret = patch_got_in_library(map.start, map.path.c_str(),
                                           entry.func_name.c_str(),
                                           entry.hook_func, &orig);
            if (ret == 0 && orig) {
                entry.original_addr = orig;
                if (entry.orig_func) {
                    *entry.orig_func = orig;
                }
                // Set the static orig_* variables for hook functions
                if (entry.func_name == "open") orig_open = (int(*)(const char*, int, ...))orig;
                else if (entry.func_name == "openat") orig_openat = (int(*)(int, const char*, int, ...))orig;
                else if (entry.func_name == "fopen") orig_fopen = (FILE*(*)(const char*, const char*))orig;
                else if (entry.func_name == "stat") orig_stat = (int(*)(const char*, struct stat*))orig;
                else if (entry.func_name == "access") orig_access = (int(*)(const char*, int))orig;
                else if (entry.func_name == "ptrace") orig_ptrace = (long(*)(int, pid_t, void*, void*))orig;
                else if (entry.func_name == "execve") orig_execve = (int(*)(const char*, char* const[], char* const[]))orig;
                hooked_count++;
                symbol_found = true;
                // Don't break — hook in ALL matching libraries
            }
        }

        if (!symbol_found) {
            // Try dlsym fallback for libc functions
            void* orig = dlsym(RTLD_DEFAULT, entry.func_name.c_str());
            if (orig) {
                entry.original_addr = orig;
                if (entry.orig_func) {
                    *entry.orig_func = orig;
                }
                // Also set the static orig_* variables in hook functions
                if (entry.func_name == "open") orig_open = (int(*)(const char*, int, ...))orig;
                else if (entry.func_name == "openat") orig_openat = (int(*)(int, const char*, int, ...))orig;
                else if (entry.func_name == "fopen") orig_fopen = (FILE*(*)(const char*, const char*))orig;
                else if (entry.func_name == "stat") orig_stat = (int(*)(const char*, struct stat*))orig;
                else if (entry.func_name == "access") orig_access = (int(*)(const char*, int))orig;
                else if (entry.func_name == "ptrace") orig_ptrace = (long(*)(int, pid_t, void*, void*))orig;
                else if (entry.func_name == "execve") orig_execve = (int(*)(const char*, char* const[], char* const[]))orig;
                LOGD("hook_refresh: dlsym fallback for %s = %p (no GOT patch)",
                     entry.func_name.c_str(), orig);
            }
        }
    }

    g_hooks_applied = true;
    LOGI("hook_refresh: %d GOT entries patched, %zu hooks registered",
         hooked_count, g_hooks.size());
    return 0;
}

int hook_unhook_all() {
    std::lock_guard<std::mutex> lock(g_hooks_mutex);
    // TODO: restore original GOT entries (need to save them)
    g_hooks_applied = false;
    LOGI("hook_unhook_all: hooks disabled");
    return 0;
}

// ── Original Function Pointers ─────────────────────────────────────

static int (*orig_open)(const char*, int, ...) = nullptr;
static int (*orig_openat)(int, const char*, int, ...) = nullptr;
static FILE* (*orig_fopen)(const char*, const char*) = nullptr;
static int (*orig_stat)(const char*, struct stat*) = nullptr;
static int (*orig_access)(const char*, int) = nullptr;
static long (*orig_ptrace)(int, pid_t, void*, void*) = nullptr;
static int (*orig_execve)(const char*, char* const[], char* const[]) = nullptr;

// ── Hooked Functions ───────────────────────────────────────────────

int hook_open(const char* pathname, int flags, ...) {
    const char* redirected = redirect_path(pathname);
    if (redirected) {
        LOGD("hook_open: %s -> %s", pathname, redirected);
        pathname = redirected;
    }
    if (orig_open) return orig_open(pathname, flags);
    // Fallback: use syscall
    return syscall(__NR_open, pathname, flags);
}

int hook_openat(int dirfd, const char* pathname, int flags, ...) {
    const char* redirected = redirect_path(pathname);
    if (redirected) {
        LOGD("hook_openat: %s -> %s", pathname, redirected);
        pathname = redirected;
    }
    if (orig_openat) return orig_openat(dirfd, pathname, flags);
    return syscall(__NR_openat, dirfd, pathname, flags);
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
        int ret = memory_bridge_read(pid, (uint64_t)(uintptr_t)addr, &result, sizeof(result));
        if (ret > 0) return result;
        return 0;
    }
    if (request == PTRACE_POKEDATA) {
        int ret = memory_bridge_write(pid, (uint64_t)(uintptr_t)addr, data, sizeof(long));
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
    // Intercept su execution for GameGuardian
    if (filename && strstr(filename, "/su") != nullptr) {
        LOGD("hook_execve: intercepted su call -> faking success");
        return su_stub_exec(argv ? argv[2] : nullptr);
    }
    if (orig_execve) return orig_execve(filename, argv, envp);
    return -1;
}
