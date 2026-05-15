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

#define TAG "vhook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// Use the NDK-provided ELF types directly (no redefining)
// On LP64: Elf64_Ehdr, Elf64_Sym, etc.  On 32-bit: Elf32_Ehdr, etc.
#if defined(__LP64__)
  typedef Elf64_Ehdr  VEHdr;
  typedef Elf64_Phdr  VPHdr;
  typedef Elf64_Dyn   VDyn;
  typedef Elf64_Sym   VSym;
  typedef Elf64_Rel   VRel;
  typedef Elf64_Rela  VRela;
#else
  typedef Elf32_Ehdr  VEHdr;
  typedef Elf32_Phdr  VPHdr;
  typedef Elf32_Dyn   VDyn;
  typedef Elf32_Sym   VSym;
  typedef Elf32_Rel   VRel;
  typedef Elf32_Rela  VRela;
#endif

// Relocation type for jump slots (architecture-specific)
#if defined(__aarch64__)
  #define V_R_JUMP_SLOT R_AARCH64_JUMP_SLOT
  #define V_R_GLOB_DAT  R_AARCH64_GLOB_DAT
  #define V_R_SYM(info) ELF64_R_SYM(info)
  #define V_R_TYPE(info) ELF64_R_TYPE(info)
#elif defined(__arm__)
  #define V_R_JUMP_SLOT R_ARM_JUMP_SLOT
  #define V_R_GLOB_DAT  R_ARM_GLOB_DAT
  #define V_R_SYM(info) ELF32_R_SYM(info)
  #define V_R_TYPE(info) ELF32_R_TYPE(info)
#elif defined(__x86_64__)
  #define V_R_JUMP_SLOT R_X86_64_JUMP_SLOT
  #define V_R_GLOB_DAT  R_X86_64_GLOB_DAT
  #define V_R_SYM(info) ELF64_R_SYM(info)
  #define V_R_TYPE(info) ELF64_R_TYPE(info)
#elif defined(__i386__)
  #define V_R_JUMP_SLOT R_386_JMP_SLOT
  #define V_R_GLOB_DAT  R_386_GLOB_DAT
  #define V_R_SYM(info) ELF32_R_SYM(info)
  #define V_R_TYPE(info) ELF32_R_TYPE(info)
#endif

// ── Forward declarations: original function pointers ────────────────
// These must be declared before hook_refresh() which references them.

static int (*orig_open)(const char*, int, ...) = nullptr;
static int (*orig_openat)(int, const char*, int, ...) = nullptr;
static FILE* (*orig_fopen)(const char*, const char*) = nullptr;
static int (*orig_stat)(const char*, struct stat*) = nullptr;
static int (*orig_access)(const char*, int) = nullptr;
static long (*orig_ptrace)(int, pid_t, void*, void*) = nullptr;
static int (*orig_execve)(const char*, char* const[], char* const[]) = nullptr;

// ── Hook Entry ─────────────────────────────────────────────────────

struct HookEntry {
    std::string lib_pattern;
    std::string func_name;
    void* hook_func;
    void** orig_func;
    void* original_addr;
};

static std::vector<HookEntry> g_hooks;
static std::mutex g_hooks_mutex;
static bool g_hooks_applied = false;

// ── /proc/self/maps Parsing ────────────────────────────────────────

struct MapEntry {
    uintptr_t start;
    uintptr_t end;
    uintptr_t offset;
    std::string path;
    bool is_exec;
};

static std::vector<MapEntry> parse_maps() {
    std::vector<MapEntry> entries;
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return entries;

    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        uintptr_t start, end, offset;
        char perms[5], path[256] = "";
        // Format: start-end perms offset dev inode pathname
        int n = sscanf(line, "%lx-%lx %4s %lx %*s %*s %255[^\n]",
                       &start, &end, perms, &offset, path);
        if (n >= 3) {
            MapEntry entry;
            entry.start = start;
            entry.end = end;
            entry.offset = (n >= 4) ? offset : 0;
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

// ── Find library base address ──────────────────────────────────────

static uintptr_t find_library_base(const char* lib_path) {
    auto maps = parse_maps();
    for (auto& m : maps) {
        // The first mapping of a library with offset 0 is the base
        if (m.path == lib_path && m.offset == 0) {
            return m.start;
        }
    }
    return 0;
}

// ── ELF GOT Patching ───────────────────────────────────────────────

static int patch_got_in_library(uintptr_t base_addr, const char* lib_path,
                                 const char* symbol_name, void* new_func, void** orig_out) {
    if (base_addr == 0 || !lib_path || !symbol_name || !new_func) return -1;

    // Read ELF header from memory — wrap in safety check
    VEHdr ehdr;
    memset(&ehdr, 0, sizeof(ehdr));
    memcpy(&ehdr, (void*)base_addr, sizeof(VEHdr));

    // Verify ELF magic
    if (memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0) {
        return -1;
    }

    // Sanity check phoff
    if (ehdr.e_phoff == 0 || ehdr.e_phoff > 0x10000) {
        LOGD("patch_got: invalid phoff 0x%x for %s", ehdr.e_phoff, lib_path);
        return -1;
    }

    // Find program headers to get dynamic section
    VPHdr* phdrs = (VPHdr*)(base_addr + ehdr.e_phoff);
    VDyn* dynamic = nullptr;

    for (int i = 0; i < ehdr.e_phnum; i++) {
        if (phdrs[i].p_type == PT_DYNAMIC) {
            dynamic = (VDyn*)(base_addr + phdrs[i].p_vaddr);
            break;
        }
    }

    if (!dynamic) return -1;

    // Parse dynamic entries
    VSym* symtab = nullptr;
    const char* strtab = nullptr;
    void* jmprel = nullptr;
    size_t jmprel_size = 0;
    void* rel = nullptr;
    size_t rel_size = 0;
    size_t relent_size = sizeof(VRel);
    size_t sym_ent_size = sizeof(VSym);
    bool is_rela = false;

    for (VDyn* d = dynamic; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_SYMTAB:
                symtab = (VSym*)(base_addr + d->d_un.d_ptr);
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
                is_rela = (d->d_un.d_val == DT_RELA);
                relent_size = is_rela ? sizeof(VRela) : sizeof(VRel);
                break;
            case DT_REL:
                rel = (void*)(base_addr + d->d_un.d_ptr);
                break;
            case DT_RELSZ:
                rel_size = d->d_un.d_val;
                break;
            case DT_RELENT:
                relent_size = d->d_un.d_val;
                break;
#ifdef DT_RELA
            case DT_RELA:
                rel = (void*)(base_addr + d->d_un.d_ptr);
                is_rela = true;
                break;
            case DT_RELASZ:
                rel_size = d->d_un.d_val;
                break;
            case DT_RELAENT:
                relent_size = d->d_un.d_val;
                break;
#endif
            case DT_SYMENT:
                sym_ent_size = d->d_un.d_val;
                break;
        }
    }

    if (!symtab || !strtab) {
        LOGD("patch_got: missing symtab or strtab for %s (non-fatal)", lib_path);
        return -1;
    }

    // Find the symbol index by name
    int target_sym_idx = -1;
    for (int i = 0; i < 4096; i++) {  // reasonable upper bound
        VSym* sym = (VSym*)((uintptr_t)symtab + i * sym_ent_size);
        // Safety: check if we're still in readable memory
        if (sym->st_name == 0) {
            if (i > 0 && ELF64_ST_BIND(sym->st_info) == STB_LOCAL) break;
            continue;
        }
        // Validate st_name is within reasonable range
        const char* name = strtab + sym->st_name;
        if (name[0] == '\0') break;
        // Safety: don't read beyond a reasonable string length
        if (strlen(name) > 256) break;
        if (strcmp(name, symbol_name) == 0) {
            target_sym_idx = i;
            break;
        }
    }

    if (target_sym_idx < 0) {
        LOGD("patch_got: symbol '%s' not found in %s", symbol_name, lib_path);
        return -1;
    }

    // Search relocation sections for the symbol
    void** got_entry = nullptr;
    void* original = nullptr;

    auto search_rel = [&](void* rel_start, size_t rel_sz) -> bool {
        size_t entry_size = relent_size;
        size_t count = rel_sz / entry_size;

        for (size_t i = 0; i < count; i++) {
            uintptr_t r_offset, r_info;

            if (is_rela) {
                VRela* r = (VRela*)((uintptr_t)rel_start + i * entry_size);
                r_offset = r->r_offset;
                r_info = r->r_info;
            } else {
                VRel* r = (VRel*)((uintptr_t)rel_start + i * entry_size);
                r_offset = r->r_offset;
                r_info = r->r_info;
            }

            // Only patch JUMP_SLOT and GLOB_DAT relocations
            unsigned int type = V_R_TYPE(r_info);
            if (type != V_R_JUMP_SLOT && type != V_R_GLOB_DAT) continue;

            if ((int)V_R_SYM(r_info) == target_sym_idx) {
                got_entry = (void**)(base_addr + r_offset);
                original = *got_entry;
                return true;
            }
        }
        return false;
    };

    // Try JMPREL (PLT) first, then REL/RELA (GLOB_DAT)
    bool found = false;
    if (jmprel && jmprel_size > 0) {
        found = search_rel(jmprel, jmprel_size);
    }
    if (!found && rel && rel_size > 0) {
        found = search_rel(rel, rel_size);
    }

    if (!found || !got_entry) {
        LOGD("patch_got: no GOT entry for '%s' in %s", symbol_name, lib_path);
        return -1;
    }

    if (original == nullptr || original == new_func) {
        LOGD("patch_got: GOT entry for '%s' already hooked or null", symbol_name);
        return -1;
    }

    // Save original
    if (orig_out) *orig_out = original;

    // Make GOT entry writable
    uintptr_t page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page = (uintptr_t)got_entry & ~(page_size - 1);
    if (mprotect((void*)page, page_size, PROT_READ | PROT_WRITE) != 0) {
        LOGE("patch_got: mprotect failed for '%s': %s", symbol_name, strerror(errno));
        return -1;
    }

    // Patch GOT entry
    *got_entry = new_func;

    // Restore read-only protection
    mprotect((void*)page, page_size, PROT_READ);

    LOGI("patch_got: hooked '%s' in %s: %p -> %p (GOT @ %p)",
         symbol_name, lib_path, original, new_func, got_entry);
    return 0;
}

// ── Helper: set orig_* pointer by name ─────────────────────────────

static void set_orig_ptr(const std::string& name, void* orig) {
    if (name == "open")        orig_open    = (int(*)(const char*, int, ...))orig;
    else if (name == "openat") orig_openat  = (int(*)(int, const char*, int, ...))orig;
    else if (name == "fopen")  orig_fopen   = (FILE*(*)(const char*, const char*))orig;
    else if (name == "stat")   orig_stat    = (int(*)(const char*, struct stat*))orig;
    else if (name == "access") orig_access  = (int(*)(const char*, int))orig;
    else if (name == "ptrace") orig_ptrace  = (long(*)(int, pid_t, void*, void*))orig;
    else if (name == "execve") orig_execve  = (int(*)(const char*, char* const[], char* const[]))orig;
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
            if (map.path.find("[") != std::string::npos) continue;
            // Match library pattern using fnmatch
            if (fnmatch(entry.lib_pattern.c_str(), map.path.c_str(), 0) != 0 &&
                map.path.find(".so") == std::string::npos) continue;
            // Skip our own library
            if (map.path.find("libvengine.so") != std::string::npos) continue;

            // Find the library's base address (first mapping with offset 0)
            uintptr_t base = find_library_base(map.path.c_str());
            if (base == 0) continue;

            void* orig = nullptr;
            int ret = patch_got_in_library(base, map.path.c_str(),
                                           entry.func_name.c_str(),
                                           entry.hook_func, &orig);
            if (ret == 0 && orig) {
                entry.original_addr = orig;
                if (entry.orig_func) *entry.orig_func = orig;
                set_orig_ptr(entry.func_name, orig);
                hooked_count++;
                symbol_found = true;
            }
        }

        if (!symbol_found) {
            // dlsym fallback — gives us the address but can't patch GOT
            void* orig = dlsym(RTLD_DEFAULT, entry.func_name.c_str());
            if (orig) {
                entry.original_addr = orig;
                if (entry.orig_func) *entry.orig_func = orig;
                set_orig_ptr(entry.func_name, orig);
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
    g_hooks_applied = false;
    LOGI("hook_unhook_all: hooks disabled");
    return 0;
}

// ── Hooked Functions ───────────────────────────────────────────────

int hook_open(const char* pathname, int flags, ...) {
    const char* redirected = redirect_path(pathname);
    if (redirected) {
        LOGD("hook_open: %s -> %s", pathname, redirected);
        pathname = redirected;
    }
    if (orig_open) return orig_open(pathname, flags);
    // Fallback: use openat (available on all architectures)
    return syscall(__NR_openat, AT_FDCWD, pathname, flags);
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
        return 0;
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
