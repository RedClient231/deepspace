#include "io_redirect.h"
#include <android/log.h>
#include <cstring>
#include <string>
#include <unordered_map>
#include <mutex>
#include <pthread.h>

#define TAG "io_redirect"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ── State ──────────────────────────────────────────────────────────

static std::string g_virtual_root;
static std::unordered_map<std::string, std::string> g_package_redirects;
static std::mutex g_redirect_mutex;

// Paths to hide from virtual apps (anti-detection)
static const char* HIDDEN_PATHS[] = {
    "com.vspace.app",
    "com.vspace.stub",
    "libvengine.so",
    "virtual_space",
    nullptr
};

// ── Initialization ─────────────────────────────────────────────────

void io_redirect_init(const char* virtual_root) {
    std::lock_guard<std::mutex> lock(g_redirect_mutex);
    g_virtual_root = virtual_root ? virtual_root : "";
    g_package_redirects.clear();
    LOGD("io_redirect_init: root=%s", g_virtual_root.c_str());
}

void io_redirect_add_package(const char* package_name, const char* data_dir) {
    if (!package_name || !data_dir) return;
    std::lock_guard<std::mutex> lock(g_redirect_mutex);
    g_package_redirects[package_name] = data_dir;
    LOGD("io_redirect_add_package: %s -> %s", package_name, data_dir);
}

// ── Path Redirection ───────────────────────────────────────────────

const char* redirect_path(const char* path) {
    if (!path) return nullptr;

    std::lock_guard<std::mutex> lock(g_redirect_mutex);

    // Check for /data/data/<pkg>/ or /data/user/0/<pkg>/
    const char* prefixes[] = {"/data/data/", "/data/user/0/", nullptr};

    for (int i = 0; prefixes[i] != nullptr; i++) {
        size_t prefix_len = strlen(prefixes[i]);
        if (strncmp(path, prefixes[i], prefix_len) != 0) continue;

        // Extract package name from path
        const char* rest = path + prefix_len;
        const char* slash = strchr(rest, '/');
        if (!slash) continue;

        std::string pkg(rest, slash - rest);
        auto it = g_package_redirects.find(pkg);
        if (it != g_package_redirects.end()) {
            // Build redirected path
            static thread_local char new_path[4096];
            snprintf(new_path, sizeof(new_path), "%s/%s%s",
                     it->second.c_str(), pkg.c_str(), slash);
            return new_path;
        }
    }

    // Check for /sdcard/Android/data/<pkg>/
    const char* sdcard_prefix = "/sdcard/Android/data/";
    size_t sdcard_len = strlen(sdcard_prefix);
    if (strncmp(path, sdcard_prefix, sdcard_len) == 0) {
        const char* rest = path + sdcard_len;
        const char* slash = strchr(rest, '/');
        if (slash) {
            std::string pkg(rest, slash - rest);
            auto it = g_package_redirects.find(pkg);
            if (it != g_package_redirects.end()) {
                static thread_local char new_path[4096];
                snprintf(new_path, sizeof(new_path),
                         "%s/virtual_sd/%s%s",
                         g_virtual_root.c_str(), pkg.c_str(), slash);
                return new_path;
            }
        }
    }

    return nullptr; // No redirect needed
}

// ── Path Hiding (Anti-Detection) ───────────────────────────────────

bool should_hide_path(const char* path) {
    if (!path) return false;
    for (int i = 0; HIDDEN_PATHS[i] != nullptr; i++) {
        if (strstr(path, HIDDEN_PATHS[i]) != nullptr) {
            return true;
        }
    }
    return false;
}
